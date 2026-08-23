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

import et.restlink.sas.cdr.SasCdrService;
import et.restlink.sas.cdr.VerifyFlowJson;
import et.restlink.sas.coordinator.VerifyCoordinator;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.fsm.VerificationFsm;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.ras.mapverifier.command.AbortMapCommand;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;
import et.restlink.sas.ras.resolver.command.ResolveCommand;
import et.restlink.sas.ras.s6averifier.command.AbortS6aCommand;
import et.restlink.sas.ras.s6averifier.command.S6aVerifyCommand;
import et.restlink.sas.ras.swxverifier.command.AbortSwxCommand;
import et.restlink.sas.ras.swxverifier.command.SwxVerifyCommand;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The entitlement-service SBB. Runs the per-request FSM:
 *
 * <pre>
 * RESOLVING → VERIFYING → SCORING → APPROVED | FALLBACK
 * </pre>
 *
 * <p>Drive order is fail-closed end-to-end: resolver miss/timeout, verifier
 * failure/timeout, SIM-swap suspect or a low score all terminate in FALLBACK.
 * Missing evidence never approves.</p>
 *
 * <p>Exactly one full-flow CDR row is written per request, at this terminal
 * point ({@code phase=FLOW}): decision, assurance snapshot, stage outcomes and
 * total latency. The MSISDN is masked before persisting; the IMSI never
 * reaches the CDR at all. CDR failure is logged and swallowed — it must never
 * alter the fail-closed verification outcome.</p>
 */
public final class VerifySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(VerifySbb.class);

    private final VerifyCoordinator coordinator;
    private final VerificationFsm fsm;

    @InjectRa(name = "resolver-ra")
    private volatile RaCommandPort resolverRa;

    @InjectRa(name = "map-verifier-ra")
    private volatile RaCommandPort mapVerifierRa;

    @InjectRa(name = "s6a-verifier-ra")
    private volatile RaCommandPort s6aVerifierRa;

    @InjectRa(name = "swx-verifier-ra")
    private volatile RaCommandPort swxVerifierRa;

    public VerifySbb(VerifyCoordinator coordinator, VerificationFsm fsm) {
        this.coordinator = coordinator;
        this.fsm = fsm;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) throws Exception {
        if (!(event instanceof VerifyRequestEvent evt)) {
            return;
        }
        LOG.info("[SAS] verify start reqId={} ip={}:{} ts={} claimed={} access={} risk={}",
                evt.reqId(), evt.srcIp(), evt.srcPort(), evt.tsEpochMs(),
                evt.claimedMsisdn(), evt.accessTech(),
                evt.riskClass() == null
                        ? AssurancePolicy.RiskClass.LOGIN : evt.riskClass());

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

    private DriveOutcome drive(VerifyRequestEvent evt) throws Exception {
        // RESOLVING — Wi-Fi has no cellular bearer; the SIM/TS.43 EAP-AKA path
        // uses the SWx verifier (TS 29.273) instead of the IP resolver.
        if (evt.accessTech() == AccessTech.WIFI) {
            return verifySwx(evt);
        }

        ResolverResult resolver = resolve(evt);
        if (resolver == null || !resolver.found()) {
            FallbackReason why = resolver != null && resolver.miss() != null
                    ? resolver.miss() : FallbackReason.RESOLVER_ERROR;
            return new DriveOutcome(
                    fsm.fallback(evt.reqId(), why, resolver, null, riskClass(evt)),
                    statusOf(resolver), null);
        }

        // VERIFYING — 2G/3G rides MAP PSI/SAI; LTE/NR rides Diameter S6a.
        VerificationEvidence evidence = verify(evt, resolver);

        // SCORING → terminal
        return new DriveOutcome(
                fsm.decide(evt.reqId(), resolver, evidence, evt.claimedMsisdn(),
                        riskClass(evt)),
                statusOf(resolver),
                evidence == null ? null : evidence.protocol());
    }

    /** Resolver stage outcome label for the flow CDR. */
    private static String statusOf(ResolverResult resolver) {
        if (resolver == null) {
            return FallbackReason.RESOLVER_ERROR.name();
        }
        return resolver.found() ? "BOUND" : resolver.miss().name();
    }

    /**
     * Unknown/absent risk class maps to LOGIN — documented fail-safe default,
     * since {@code /verify} is itself a login flow (lowest bar).
     */
    private static AssurancePolicy.RiskClass riskClass(VerifyRequestEvent evt) {
        return evt.riskClass() == null ? AssurancePolicy.RiskClass.LOGIN : evt.riskClass();
    }

    /** Route the identity-plane verify to the correct RA by access technology. */
    private VerificationEvidence verify(VerifyRequestEvent evt, ResolverResult resolver)
            throws Exception {
        return switch (evt.accessTech()) {
            case LTE, NR -> verifyS6a(evt, resolver);
            case WIFI -> VerificationEvidence.fail(FallbackReason.WIFI_NOT_READY, "SWX");
            case GS_2G3G -> verifyMap(evt, resolver);
        };
    }

    private ResolverResult resolve(VerifyRequestEvent evt) throws Exception {
        RaCommandPort port = resolverRa;
        if (port == null) {
            return ResolverResult.miss(FallbackReason.RESOLVER_ERROR);
        }
        CompletableFuture<ResolverResult> reply = new CompletableFuture<>();
        port.sendCommand(new ResolveCommand(evt.reqId(), evt.srcIp(), evt.srcPort(),
                evt.tsEpochMs(), reply));
        try {
            return reply.get(SasTimeouts.RESOLVER_MS + 50L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            return ResolverResult.miss(FallbackReason.RESOLVER_TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ResolverResult.miss(FallbackReason.RESOLVER_ERROR);
        } catch (ExecutionException ee) {
            return ResolverResult.miss(FallbackReason.RESOLVER_ERROR);
        }
    }

    private VerificationEvidence verifyMap(VerifyRequestEvent evt, ResolverResult resolver)
            throws Exception {
        RaCommandPort port = mapVerifierRa;
        if (port == null) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP");
        }
        CompletableFuture<VerificationEvidence> reply = new CompletableFuture<>();
        port.sendCommand(new MapVerifyCommand(evt.reqId(), resolver.msisdn(), resolver.imsi(),
                evt.accessTech(), reply));
        try {
            return reply.get(SasTimeouts.MAP_MS + 100L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // Safety net: the RA already aborts on its own budget; this covers
            // the case where the backend stalls past the RA's completeOnTimeout.
            port.sendCommand(new AbortMapCommand(evt.reqId(), "dialog:" + evt.reqId()));
            return VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "MAP");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP");
        } catch (ExecutionException ee) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP");
        }
    }

    private VerificationEvidence verifyS6a(VerifyRequestEvent evt, ResolverResult resolver)
            throws Exception {
        RaCommandPort port = s6aVerifierRa;
        if (port == null) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A");
        }
        CompletableFuture<VerificationEvidence> reply = new CompletableFuture<>();
        port.sendCommand(new S6aVerifyCommand(evt.reqId(), resolver.msisdn(), resolver.imsi(),
                evt.accessTech(), reply));
        try {
            return reply.get(SasTimeouts.DIAMETER_MS + 100L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // Safety net: the RA already aborts on its own budget.
            port.sendCommand(new AbortS6aCommand(evt.reqId(), "session:" + evt.reqId()));
            return VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "S6A");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A");
        } catch (ExecutionException ee) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A");
        }
    }

    private DriveOutcome verifySwx(VerifyRequestEvent evt) throws Exception {
        // Wi-Fi path: the claimed MSISDN is the identity anchor (no IP resolver).
        // Missing anchor ⇒ fail closed before touching the SWx RA.
        if (evt.claimedMsisdn() == null || evt.claimedMsisdn().isBlank()) {
            return new DriveOutcome(
                    fsm.fallback(evt.reqId(), FallbackReason.INVALID_REQUEST, null, null,
                            riskClass(evt)),
                    "SKIPPED_WIFI", null);
        }
        RaCommandPort port = swxVerifierRa;
        if (port == null) {
            return new DriveOutcome(
                    fsm.fallback(evt.reqId(), FallbackReason.VERIFY_ERROR, null, null,
                            riskClass(evt)),
                    "SKIPPED_WIFI", null);
        }
        CompletableFuture<VerificationEvidence> reply = new CompletableFuture<>();
        // B2: claimed IMSI (from the TS.43 entitlement token) rides with the
        // SWx verify; the backend treats a mismatch as SIM-swap suspect.
        port.sendCommand(new SwxVerifyCommand(evt.reqId(), evt.claimedMsisdn(),
                evt.claimedImsi(), evt.accessTech(), reply));
        VerificationEvidence evidence;
        try {
            evidence = reply.get(SasTimeouts.DIAMETER_MS + 100L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            port.sendCommand(new AbortSwxCommand(evt.reqId(), "session:" + evt.reqId()));
            evidence = VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "SWX");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            evidence = VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX");
        } catch (ExecutionException ee) {
            evidence = VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX");
        }
        // Wi-Fi path: no resolver — use claimed MSISDN as the identity anchor.
        ResolverResult resolver = ResolverResult.bound(evt.claimedMsisdn(), null, 0L);
        return new DriveOutcome(
                fsm.decide(evt.reqId(), resolver, evidence, evt.claimedMsisdn(),
                        riskClass(evt)),
                "SKIPPED_WIFI",
                evidence == null ? null : evidence.protocol());
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
                            (int) Math.min(Integer.MAX_VALUE, totalMs)));
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
