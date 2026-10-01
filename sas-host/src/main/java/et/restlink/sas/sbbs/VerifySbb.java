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

import et.restlink.sas.api.ExposureAdapters;
import et.restlink.sas.cdr.SasCdrService;
import et.restlink.sas.cdr.VerifyFlowJson;
import et.restlink.sas.coordinator.VerifyCoordinator;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.fsm.VerificationFsm;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.ras.exposure.command.SubscriberCallCommand;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.UeIdInfo;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The verification SBB of the CAMARA gateway. Runs the per-request FSM:
 *
 * <pre>
 * RESOLVING → VERIFYING → SCORING → APPROVED | FALLBACK
 * </pre>
 *
 * <p>Layered ADR §6/§7: every stage is a subscriber exposure call through
 * {@code exposure-ra}; this SBB owns the orchestration and the scoring, the entitlement
 * server owns the signalling.</p>
 * <ul>
 *   <li>RESOLVING — cellular: UE ID retrieval of {@code IP:port:ts} (400 ms). Wi-Fi: the
 *       number of a redeemed operator token; Wi-Fi without one has no proof → FALLBACK.</li>
 *   <li>VERIFYING — monitoring (reachability + location metadata) ∥ SIM change (2.2 s).</li>
 *   <li>SCORING — {@link VerificationFsm} with the gateway's location policy.</li>
 * </ul>
 *
 * <p>Fail-closed end-to-end: any exposure problem, timeout or transport error, a recent
 * or unknown SIM change, or a low score terminates in FALLBACK. Exactly one flow CDR row
 * is written per request; it carries the internal evidence sources (ADR R4), the CAMARA
 * response never does.</p>
 */
public final class VerifySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(VerifySbb.class);
    private static final long WAIT_GRACE_MS = 200L;

    private final VerifyCoordinator coordinator;
    private final VerificationFsm fsm;
    private final String afId;
    private final ExposureAdapters.LocationPolicy locationPolicy;

    @InjectRa(name = "exposure-ra")
    private volatile RaCommandPort exposureRa;

    public VerifySbb(VerifyCoordinator coordinator, VerificationFsm fsm, String afId,
                     ExposureAdapters.LocationPolicy locationPolicy) {
        this.coordinator = coordinator;
        this.fsm = fsm;
        this.afId = afId;
        this.locationPolicy = locationPolicy;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) throws Exception {
        if (!(event instanceof VerifyRequestEvent evt)) {
            return;
        }
        LOG.info("[SAS] verify start reqId={} ip={}:{} ts={} access={} anchored={} risk={}",
                evt.reqId(), evt.srcIp(), evt.srcPort(), evt.tsEpochMs(), evt.accessTech(),
                evt.operatorAnchored(),
                evt.riskClass() == null ? AssurancePolicy.RiskClass.LOGIN : evt.riskClass());

        long startNanos = System.nanoTime();
        DriveOutcome outcome = drive(evt);
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000L;

        coordinator.complete(evt.reqId(), outcome.result());
        recordFlowCdr(evt, outcome, totalMs);

        LOG.info("[SAS] verify end reqId={} match={} assurance={} fallback={} totalMs={}",
                outcome.result().reqId(), outcome.result().match(),
                outcome.result().assurance(), outcome.result().fallbackReason(), totalMs);
    }

    /** Terminal result plus the stage provenance needed for the flow CDR. */
    private record DriveOutcome(VerifyResult result, String resolverStatus,
                                String evidenceSource) {
    }

    private DriveOutcome drive(VerifyRequestEvent evt) throws InterruptedException {
        ResolverResult resolver;
        String resolverStatus;
        if (evt.accessTech() == AccessTech.WIFI) {
            // No cellular bearer: the only proof of the subscriber is a redeemed operator
            // token (EAP-AKA at the entitlement server). A Wi-Fi claim without one proves nothing.
            if (!evt.operatorAnchored() || evt.claimedMsisdn() == null) {
                return new DriveOutcome(fsm.fallback(evt.reqId(), FallbackReason.WIFI_NOT_READY,
                        null, null, riskClass(evt)), "WIFI_UNANCHORED", null);
            }
            resolver = ResolverResult.bound(evt.claimedMsisdn(), null, 0L);
            resolverStatus = "OPERATOR_TOKEN";
        } else {
            resolver = resolve(evt);
            if (resolver == null || !resolver.found()) {
                FallbackReason why = resolver != null && resolver.miss() != null
                        ? resolver.miss() : FallbackReason.RESOLVER_ERROR;
                return new DriveOutcome(fsm.fallback(evt.reqId(), why, resolver, null, riskClass(evt)),
                        why.name(), null);
            }
            resolverStatus = "BOUND";
        }
        VerificationEvidence evidence = verify(evt, resolver.msisdn());
        return new DriveOutcome(
                fsm.decide(evt.reqId(), resolver, evidence, evt.claimedMsisdn(), riskClass(evt)),
                resolverStatus, evidence == null ? null : evidence.protocol());
    }

    /**
     * Unknown/absent risk class maps to LOGIN — documented fail-safe default,
     * since {@code /verify} is itself a login flow (lowest bar).
     */
    private static AssurancePolicy.RiskClass riskClass(VerifyRequestEvent evt) {
        return evt.riskClass() == null ? AssurancePolicy.RiskClass.LOGIN : evt.riskClass();
    }

    // ---- RESOLVING: UE ID retrieval ------------------------------------------

    private ResolverResult resolve(VerifyRequestEvent evt) throws InterruptedException {
        ExposureOutcome<UeIdInfo> answer = call(evt.reqId() + ":ue-id",
                svc -> svc.retrieveUeId(ExposureAdapters.context("ueid", ExposureAdapters.UE_ID_BUDGET_MS),
                        ExposureAdapters.ueIdRequest(afId, evt.srcIp(), evt.srcPort(), evt.tsEpochMs())),
                ExposureAdapters.UE_ID_BUDGET_MS);
        return ExposureAdapters.resolverResult(answer, evt.tsEpochMs());
    }

    // ---- VERIFYING: monitoring ∥ SIM change ----------------------------------

    private VerificationEvidence verify(VerifyRequestEvent evt, String msisdnE164)
            throws InterruptedException {
        String digits;
        try {
            digits = ExposureAdapters.digits(msisdnE164);
        } catch (IllegalArgumentException e) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "EXPOSURE");
        }
        int budget = ExposureAdapters.EVIDENCE_BUDGET_MS;
        CompletableFuture<ExposureOutcome<MonitoringEventReports>> monitoring = send(
                evt.reqId() + ":monitoring",
                svc -> svc.monitor(ExposureAdapters.context("mon", budget), afId,
                        ExposureAdapters.monitoringRequest(msisdnE164)));
        CompletableFuture<ExposureOutcome<SimChangeInfo>> simChange = send(
                evt.reqId() + ":sim-change",
                svc -> svc.retrieveSimChange(ExposureAdapters.context("sim", budget),
                        new SimChangeReq(afId, digits)));
        return ExposureAdapters.evidence(await(monitoring, budget), await(simChange, budget),
                Instant.now(), locationPolicy);
    }

    // ---- exposure-ra plumbing ------------------------------------------------

    private <T> CompletableFuture<ExposureOutcome<T>> send(
            String id, java.util.function.Function<et.restlink.sas.subscriber.SubscriberService,
                    CompletableFuture<ExposureOutcome<T>>> call) {
        CompletableFuture<ExposureOutcome<T>> reply = new CompletableFuture<>();
        RaCommandPort port = exposureRa;
        if (port == null) {
            reply.complete(ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE, "no exposure RA bound"));
        } else {
            port.sendCommand(new SubscriberCallCommand<>(id, call, reply));
        }
        return reply;
    }

    private <T> ExposureOutcome<T> call(
            String id, java.util.function.Function<et.restlink.sas.subscriber.SubscriberService,
                    CompletableFuture<ExposureOutcome<T>>> call, int budgetMs) throws InterruptedException {
        return await(send(id, call), budgetMs);
    }

    /** Bounded wait; a silent entitlement server is a timeout, never a pass. */
    private static <T> ExposureOutcome<T> await(CompletableFuture<ExposureOutcome<T>> answer, int budgetMs)
            throws InterruptedException {
        try {
            return answer.get(budgetMs + WAIT_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "no answer within the budget");
        } catch (ExecutionException e) {
            return ExposureOutcome.problem(ExposureCause.INTERNAL_ERROR, "exposure call failed");
        }
    }

    // ---- full-flow CDR ----------------------------------------------------

    /**
     * Writes the one flow CDR row for this request. Resolves the CDI service
     * lazily (the SBB is constructed by the container factory, not by Arc);
     * outside a CDI environment (pure unit tests) the write is skipped —
     * never fatal.
     */
    private void recordFlowCdr(VerifyRequestEvent evt, DriveOutcome outcome, long totalMs) {
        try {
            Instance<SasCdrService> instances =
                    CDI.current().select(SasCdrService.class);
            if (!instances.isResolvable()) {
                LOG.debug("[SAS] flow CDR skipped reqId={} (service absent)", evt.reqId());
                return;
            }
            VerifyResult r = outcome.result();
            instances.get().recordFlow(evt.reqId(), evt.claimedMsisdn(),
                    new SasCdrService.FlowDetail(
                            r.match(),
                            r.decision(),
                            r.score(),
                            r.threshold(),
                            r.assurance() == null ? null : r.assurance().name(),
                            r.riskClass(),
                            evt.accessTech().name(),
                            r.fallbackReason() == null ? null : r.fallbackReason().name(),
                            outcome.resolverStatus(),
                            outcome.evidenceSource(),
                            VerifyFlowJson.build(r, stageNotes(outcome)),
                            (int) Math.min(Integer.MAX_VALUE, totalMs),
                            evt.tenantId()));
        } catch (IllegalStateException stateless) {
            LOG.debug("[SAS] flow CDR skipped reqId={} (no CDI)", evt.reqId());
        } catch (RuntimeException ex) {
            LOG.warn("[SAS] flow CDR failed reqId={}: {}", evt.reqId(), ex.toString());
        }
    }

    private static List<String> stageNotes(DriveOutcome outcome) {
        VerifyResult r = outcome.result();
        return List.of(
                "resolver:" + outcome.resolverStatus(),
                "verifier:" + (outcome.evidenceSource() == null ? "NONE" : outcome.evidenceSource()),
                "decision:" + r.decision(),
                "fallback:" + (r.fallbackReason() == null ? "NONE" : r.fallbackReason().name()));
    }
}
