/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;

import et.restlink.sas.entitlement.OperatorTokenRedemption;
import et.restlink.sas.events.SubscriberQueryEvent;
import et.restlink.sas.exposure.ExposureCoordinator;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.LocationEvidence;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.ras.binding.SubscriberBinding;
import et.restlink.sas.ras.binding.command.AbortBindingCommand;
import et.restlink.sas.ras.binding.command.LookupBindingCommand;
import et.restlink.sas.ras.mapverifier.command.AbortMapCommand;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;
import et.restlink.sas.ras.resolver.command.ResolveCommand;
import et.restlink.sas.ras.s6averifier.command.AbortS6aCommand;
import et.restlink.sas.ras.s6averifier.command.S6aVerifyCommand;
import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.dto.LocationInfo;
import et.restlink.sas.subscriber.dto.MonitoringEventReport;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.PlmnId;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Subscriber Services of the entitlement server (layered ADR R1): answers the four
 * exposure operations from the operator adapter RAs, and decides the data source itself.
 *
 * <pre>
 *   UE ID        resolver-ra (Gx/Sd · RADIUS · CGNAT; lab: in-memory)        300 ms
 *   monitoring   [subscriber-binding-ra SendIMSI, real MAP only] → map-verifier-ra PSI
 *                or s6a-verifier-ra ULR + Sh UDR                             2 s
 *   SIM change   configured evidence source (lab backends; none on live transports yet)
 *   redeem       OperatorTokenRedemption (own key, own ledger) — no signalling
 * </pre>
 *
 * <p>Every stage waits at most {@code min(stage budget, caller budget)} and aborts its
 * dialog on expiry; a caller budget that cannot cover a stage is answered
 * {@code BUDGET_EXHAUSTED} without signalling. Fail-closed: anything but evidence is a
 * problem answer, never a negative-but-successful one.</p>
 */
public final class SubscriberSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(SubscriberSbb.class);

    /** Below these the stage cannot run meaningfully: answer BUDGET_EXHAUSTED instead. */
    static final long MIN_RESOLVER_MS = 20L;
    static final long MIN_NETWORK_MS = 100L;
    /** Slack for an RA to deliver its own timeout answer before the SBB aborts. */
    private static final long RA_SLACK_MS = 50L;

    /** Where SIM-change evidence comes from; empty = no evidence (fail closed). */
    @FunctionalInterface
    public interface SimChangeSource {
        Optional<SimChange> latest(String msisdnE164);
    }

    public record SimChange(Instant at, String evidenceSource) {
    }

    /**
     * How this ECS reaches the subscriber, fixed at bootstrap.
     *
     * @param resolverSource  evidenceSource label of the resolver (GX, RADIUS, CGNAT_LOG, LAB_RESOLVER)
     * @param monitoringViaS6a answer monitoring from S6a instead of MAP
     * @param mapNeedsImsi    the MAP verifier is live and needs the IMSI (SendIMSI first)
     * @param mapSource       evidenceSource label of the MAP verifier
     * @param s6aSource       evidenceSource label of the S6a verifier
     */
    public record Sources(String resolverSource, boolean monitoringViaS6a, boolean mapNeedsImsi,
                          String mapSource, String s6aSource, SimChangeSource simChange) {
    }

    private final ExposureCoordinator coordinator;
    private final Sources sources;
    private final OperatorTokenRedemption redemption;

    @InjectRa(name = "resolver-ra")
    private volatile RaCommandPort resolverRa;

    @InjectRa(name = "map-verifier-ra")
    private volatile RaCommandPort mapVerifierRa;

    @InjectRa(name = "s6a-verifier-ra")
    private volatile RaCommandPort s6aVerifierRa;

    @InjectRa(name = "subscriber-binding-ra")
    private volatile RaCommandPort bindingRa;

    public SubscriberSbb(ExposureCoordinator coordinator, Sources sources,
                         OperatorTokenRedemption redemption) {
        this.coordinator = coordinator;
        this.sources = sources;
        this.redemption = redemption;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof SubscriberQueryEvent evt)) {
            return;
        }
        ExposureOutcome<?> outcome;
        try {
            outcome = switch (evt.operation()) {
                case UE_ID -> ueId(evt, evt.request(UeIdReq.class));
                case MONITORING -> monitoring(evt, evt.request(MonitoringEventSubscription.class));
                case SIM_CHANGE -> simChange(evt.request(SimChangeReq.class));
                case REDEEM -> redeem(evt.context(), evt.request(OperatorTokenRedeemReq.class));
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = ExposureOutcome.problem(ExposureCause.INTERNAL_ERROR, "interrupted");
        } catch (RuntimeException e) {
            LOG.warn("[subscriber] {} failed — fail closed", evt, e);
            outcome = ExposureOutcome.problem(ExposureCause.INTERNAL_ERROR, "subscriber service failed");
        }
        coordinator.complete(evt.answerKey(), outcome);
        LOG.info("[subscriber] {} -> {}", evt, outcome.ok() ? "200" : outcome.cause());
    }

    // ---- UE ID ---------------------------------------------------------------

    private ExposureOutcome<UeIdInfo> ueId(SubscriberQueryEvent evt, UeIdReq req)
            throws InterruptedException {
        long wait = Math.min(evt.context().budgetMs(), SasTimeouts.RESOLVER_MS + RA_SLACK_MS);
        if (wait < MIN_RESOLVER_MS) {
            return ExposureOutcome.problem(ExposureCause.BUDGET_EXHAUSTED, "budget below the resolver stage");
        }
        RaCommandPort port = resolverRa;
        if (port == null) {
            return ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE, "no resolver bound");
        }
        String ip = req.ueIpAddr().ipv4Addr() != null ? req.ueIpAddr().ipv4Addr() : req.ueIpAddr().ipv6Addr();
        long ts = OffsetDateTime.parse(req.observedAt()).toInstant().toEpochMilli();
        CompletableFuture<ResolverResult> reply = new CompletableFuture<>();
        port.sendCommand(new ResolveCommand(evt.activityId(), ip, req.portNumber(), ts, reply));
        ResolverResult r;
        try {
            r = reply.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "resolver did not answer");
        } catch (ExecutionException e) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "resolver failed");
        }
        if (r == null) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "resolver failed");
        }
        if (!r.found()) {
            return switch (r.miss() == null ? FallbackReason.RESOLVER_ERROR : r.miss()) {
                case NO_BINDING -> ExposureOutcome.problem(ExposureCause.NO_BINDING,
                        "no subscriber holds this IP:port at that time");
                case AMBIGUOUS_BINDING -> ExposureOutcome.problem(ExposureCause.AMBIGUOUS_BINDING,
                        "more than one subscriber matches (CGNAT)");
                case RESOLVER_TIMEOUT -> ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT,
                        "resolver did not answer");
                default -> ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "resolver failed");
            };
        }
        Optional<String> gpsi = PhoneNumberNormalizer.toGpsi(r.msisdn());
        if (gpsi.isEmpty()) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "binding source returned a non-number");
        }
        Instant observed = Instant.ofEpochMilli(ts - Math.max(0L, r.bearerAgeMs()));
        return ExposureOutcome.ok(new UeIdInfo(gpsi.get(), observed.toString(), sources.resolverSource()));
    }

    // ---- monitoring ----------------------------------------------------------

    private ExposureOutcome<MonitoringEventReports> monitoring(SubscriberQueryEvent evt,
                                                               MonitoringEventSubscription req)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + evt.context().budgetMs();
        if (evt.context().budgetMs() < MIN_NETWORK_MS) {
            return ExposureOutcome.problem(ExposureCause.BUDGET_EXHAUSTED, "budget below one network stage");
        }
        String e164 = "+" + req.msisdn();
        String imsi = null;
        boolean viaS6a = sources.monitoringViaS6a();
        if (!viaS6a && sources.mapNeedsImsi()) {
            ExposureOutcome<String> found = imsiOf(evt, e164, deadline);
            if (!found.ok()) {
                return ExposureOutcome.problem(found.problem());
            }
            imsi = found.value();
        }
        long wait = Math.min(deadline - System.currentTimeMillis(),
                (viaS6a ? SasTimeouts.DIAMETER_MS : SasTimeouts.MAP_MS) + RA_SLACK_MS);
        if (wait < MIN_NETWORK_MS) {
            return ExposureOutcome.problem(ExposureCause.BUDGET_EXHAUSTED, "budget spent before the verifier stage");
        }
        RaCommandPort port = viaS6a ? s6aVerifierRa : mapVerifierRa;
        if (port == null) {
            return ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE, "no verifier bound");
        }
        CompletableFuture<VerificationEvidence> reply = new CompletableFuture<>();
        if (viaS6a) {
            port.sendCommand(new S6aVerifyCommand(evt.activityId(), e164, imsi, AccessTech.LTE, reply));
        } else {
            port.sendCommand(new MapVerifyCommand(evt.activityId(), e164, imsi, AccessTech.GS_2G3G, reply));
        }
        VerificationEvidence ev;
        try {
            ev = reply.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            port.sendCommand(viaS6a
                    ? new AbortS6aCommand(evt.activityId(), "session:" + evt.activityId())
                    : new AbortMapCommand(evt.activityId(), "dialog:" + evt.activityId()));
            return ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "verifier did not answer; dialog aborted");
        } catch (ExecutionException e) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "verifier failed");
        }
        if (ev == null) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "verifier failed");
        }
        String now = Instant.now().toString();
        if (ev.failed() && ev.failure() != FallbackReason.PURGED) {
            return ev.failure() == FallbackReason.VERIFY_TIMEOUT
                    ? ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "verifier timed out")
                    : ExposureOutcome.problem(ExposureCause.NETWORK_ERROR,
                            "the core answered with an error, reject or abort");
        }
        boolean purged = ev.failed();
        List<MonitoringEventReport> reports = new ArrayList<>();
        for (String type : requestedTypes(req)) {
            if (ExposureApi.UE_REACHABILITY.equals(type)) {
                // A purged subscriber is known but not reachable.
                reports.add(new MonitoringEventReport(type, req.msisdn(), now,
                        !purged && ev.reachable(), null, null));
            } else {
                LocationEvidence where = purged ? null : ev.location();
                PlmnId plmn = where != null && where.hasPlmn() ? new PlmnId(where.mcc(), where.mnc()) : null;
                LocationInfo age = where != null && where.ageMinutes() != null
                        ? new LocationInfo(where.ageMinutes()) : null;
                reports.add(new MonitoringEventReport(type, req.msisdn(), now, null, plmn, age));
            }
        }
        return ExposureOutcome.ok(new MonitoringEventReports(reports,
                viaS6a ? sources.s6aSource() : sources.mapSource()));
    }

    /** MSISDN → IMSI through SendIMSI (own HLR, SSN 3), for a live MAP verifier. */
    private ExposureOutcome<String> imsiOf(SubscriberQueryEvent evt, String e164, long deadline)
            throws InterruptedException {
        RaCommandPort port = bindingRa;
        if (port == null) {
            return ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE, "no binding source bound");
        }
        long wait = Math.min(deadline - System.currentTimeMillis(), SasTimeouts.MAP_MS + RA_SLACK_MS);
        if (wait < MIN_NETWORK_MS) {
            return ExposureOutcome.problem(ExposureCause.BUDGET_EXHAUSTED, "budget below the binding stage");
        }
        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        port.sendCommand(new LookupBindingCommand(evt.activityId(), null, e164, reply));
        try {
            SubscriberBinding b = reply.get(wait, TimeUnit.MILLISECONDS);
            if (b != null && b.resolved() && b.imsi() != null) {
                return ExposureOutcome.ok(b.imsi());
            }
            if (b != null && "timeout".equals(b.source())) {
                return ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "binding source did not answer");
            }
            return ExposureOutcome.problem(ExposureCause.SUBSCRIBER_NOT_FOUND,
                    "the own HLR/HSS does not know the subscriber");
        } catch (TimeoutException e) {
            port.sendCommand(new AbortBindingCommand(evt.activityId(), "exposure:" + evt.activityId()));
            return ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "binding source did not answer");
        } catch (ExecutionException e) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "binding source failed");
        }
    }

    private static List<String> requestedTypes(MonitoringEventSubscription req) {
        List<String> types = new ArrayList<>();
        types.add(req.monitoringType());
        if (req.addnMonTypes() != null) {
            types.addAll(req.addnMonTypes());
        }
        return types;
    }

    // ---- SIM change ----------------------------------------------------------

    private ExposureOutcome<SimChangeInfo> simChange(SimChangeReq req) {
        Optional<SimChange> found = sources.simChange() == null ? Optional.empty()
                : sources.simChange().latest("+" + req.msisdn());
        if (found.isEmpty()) {
            return ExposureOutcome.problem(ExposureCause.NO_EVIDENCE,
                    "no source could say when the SIM last changed");
        }
        return ExposureOutcome.ok(new SimChangeInfo(req.msisdn(), found.get().at().toString(),
                Instant.now().toString(), found.get().evidenceSource()));
    }

    // ---- operator token ------------------------------------------------------

    private ExposureOutcome<SubscriberVerificationResult> redeem(ExposureContext ctx,
                                                                 OperatorTokenRedeemReq req) {
        if (redemption == null) {
            return ExposureOutcome.problem(ExposureCause.INVALID_TOKEN, "operator token refused");
        }
        return redemption.redeem(req.operatorToken(), ctx.requestId())
                .map(r -> ExposureOutcome.ok(new SubscriberVerificationResult(r.msisdn(),
                        ExposureApi.AUTH_METHOD_EAP_AKA, Instant.ofEpochSecond(r.issuedAtEpochSec()).toString(),
                        true, r.jti())))
                .orElseGet(() -> ExposureOutcome.problem(ExposureCause.INVALID_TOKEN,
                        "operator token refused"));
    }
}
