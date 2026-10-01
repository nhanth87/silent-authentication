/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.scenario;

import et.restlink.sas.api.ExposureAdapters;
import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.fsm.VerificationFsm;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.dto.LocationInfo;
import et.restlink.sas.subscriber.dto.MonitoringEventReport;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.PlmnId;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.UeIdInfo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code silent-auth-standard-flow.md} scenario on the layered architecture: every
 * fact is an exposure answer from the entitlement server, the gateway's FSM scores it.
 *
 * <pre>
 *   Step 1  RESOLVING — UE ID retrieval: IP:port:ts → GPSI
 *   Step 2  VERIFYING — monitoring (reachability + location metadata) ∥ SIM change
 *   Step 3  SCORING   — APPROVE, or FALLBACK with the reason
 * </pre>
 */
class SasVerifyScenarioExposureTest {

    private static final String MSISDN = "+251911111111";
    private static final String REQ = "scenario-1";
    private static final Instant NOW = Instant.now();
    private static final ExposureAdapters.LocationPolicy HOME = ExposureAdapters.LocationPolicy.of("63601", 1440);

    private final VerificationFsm fsm = new VerificationFsm(AssurancePolicy.defaults());

    private static ResolverResult resolve(ExposureOutcome<UeIdInfo> answer, long ageMs) {
        return ExposureAdapters.resolverResult(answer, NOW.toEpochMilli() + ageMs);
    }

    private static ExposureOutcome<UeIdInfo> bound() {
        return ExposureOutcome.ok(new UeIdInfo("msisdn-251911111111", NOW.toString(), "GX"));
    }

    private static ExposureOutcome<MonitoringEventReports> monitoring(boolean reachable, String mcc) {
        return ExposureOutcome.ok(new MonitoringEventReports(List.of(
                new MonitoringEventReport(ExposureApi.UE_REACHABILITY, "251911111111", NOW.toString(),
                        reachable, null, null),
                new MonitoringEventReport(ExposureApi.LOCATION_REPORTING, "251911111111", NOW.toString(),
                        null, new PlmnId(mcc, "01"), new LocationInfo(5))), "MAP_PSI"));
    }

    private static ExposureOutcome<SimChangeInfo> simChangedAgo(Duration ago) {
        return ExposureOutcome.ok(new SimChangeInfo("251911111111", NOW.minus(ago).toString(),
                NOW.toString(), "HLR_SIM_DB"));
    }

    private static VerificationEvidence evidence(ExposureOutcome<MonitoringEventReports> m,
                                                 ExposureOutcome<SimChangeInfo> s) {
        return ExposureAdapters.evidence(m, s, NOW, HOME);
    }

    @Test
    void step1_ueIdBindsTheSessionTupleWithoutAnImsi() {
        ResolverResult r = resolve(bound(), 0);
        assertTrue(r.found());
        assertEquals(MSISDN, r.msisdn());
        assertNull(r.imsi(), "the gateway never learns the IMSI");
    }

    @Test
    void step3_fullScenarioApproves() {
        VerifyResult result = fsm.decide(REQ, resolve(bound(), 0),
                evidence(monitoring(true, "636"), simChangedAgo(Duration.ofDays(10))), null);
        assertNull(result.fallbackReason(), String.valueOf(result.fallbackReason()));
        assertTrue(result.match());
        assertEquals(MSISDN, result.msisdn());
    }

    @Test
    void claimMatchApprovesAndMismatchFallsBack() {
        VerificationEvidence ev = evidence(monitoring(true, "636"), simChangedAgo(Duration.ofDays(10)));
        assertNull(fsm.decide(REQ, resolve(bound(), 0), ev, MSISDN).fallbackReason());
        assertEquals(FallbackReason.MSISDN_MISMATCH,
                fsm.decide(REQ, resolve(bound(), 0), ev, "+251911111112").fallbackReason());
    }

    @Test
    void noBindingAndCgnatAmbiguityFallBack() {
        assertEquals(FallbackReason.NO_BINDING, fsm.decide(REQ,
                resolve(ExposureOutcome.problem(ExposureCause.NO_BINDING, "x"), 0), null, null).fallbackReason());
        assertEquals(FallbackReason.AMBIGUOUS_BINDING, fsm.decide(REQ,
                resolve(ExposureOutcome.problem(ExposureCause.AMBIGUOUS_BINDING, "x"), 0), null, null)
                .fallbackReason());
    }

    @Test
    void entitlementServerSilenceIsNeverAnApproval() {
        assertEquals(FallbackReason.RESOLVER_TIMEOUT, fsm.decide(REQ,
                resolve(ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "x"), 0), null, null)
                .fallbackReason());
        assertEquals(FallbackReason.VERIFY_TIMEOUT, fsm.decide(REQ, resolve(bound(), 0),
                evidence(ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "x"),
                        simChangedAgo(Duration.ofDays(10))), null).fallbackReason());
        assertEquals(FallbackReason.VERIFY_ERROR, fsm.decide(REQ, resolve(bound(), 0),
                evidence(ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE, "x"), null), null)
                .fallbackReason());
    }

    @Test
    void unreachableSubscriberFallsBackPurged() {
        assertEquals(FallbackReason.PURGED, fsm.decide(REQ, resolve(bound(), 0),
                evidence(monitoring(false, "636"), simChangedAgo(Duration.ofDays(10))), null).fallbackReason());
    }

    @Test
    void recentOrUnknownSimChangeIsASwapSuspect() {
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, fsm.decide(REQ, resolve(bound(), 0),
                evidence(monitoring(true, "636"), simChangedAgo(Duration.ofHours(1))), null).fallbackReason());
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, fsm.decide(REQ, resolve(bound(), 0),
                evidence(monitoring(true, "636"), ExposureOutcome.problem(ExposureCause.NO_EVIDENCE, "x")),
                null).fallbackReason(), "no evidence is never 'not swapped'");
    }

    @Test
    void staleBearerAndVisitedNetworkFallBackLowAssurance() {
        ResolverResult stale = resolve(bound(), AssurancePolicy.STALE_BEARER_MS + 1L);
        assertEquals(FallbackReason.LOW_ASSURANCE, fsm.decide(REQ, stale,
                evidence(monitoring(true, "001"), simChangedAgo(Duration.ofDays(10))), null).fallbackReason());
    }
}
