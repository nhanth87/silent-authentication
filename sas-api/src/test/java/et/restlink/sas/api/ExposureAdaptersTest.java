/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layered ADR §7.3: one mapping from exposure answers to the gateway's verification model. */
class ExposureAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final ExposureAdapters.LocationPolicy HOME = ExposureAdapters.LocationPolicy.of("63601", 60);

    private static ExposureOutcome<MonitoringEventReports> monitoring(boolean reachable, String mcc,
                                                                    String mnc, Integer age) {
        return ExposureOutcome.ok(new MonitoringEventReports(List.of(
                new MonitoringEventReport(ExposureApi.UE_REACHABILITY, "251911111111", NOW.toString(),
                        reachable, null, null),
                new MonitoringEventReport(ExposureApi.LOCATION_REPORTING, "251911111111", NOW.toString(),
                        null, mcc == null ? null : new PlmnId(mcc, mnc),
                        age == null ? null : new LocationInfo(age))), "MAP_PSI"));
    }

    private static ExposureOutcome<SimChangeInfo> simChange(Instant at) {
        return ExposureOutcome.ok(new SimChangeInfo("251911111111", at.toString(), NOW.toString(), "LAB_HLR"));
    }

    @Test
    void ueIdAnswerBecomesABoundResolverResultWithoutImsi() {
        ResolverResult r = ExposureAdapters.resolverResult(ExposureOutcome.ok(
                new UeIdInfo("msisdn-251911111111", "2026-10-01T05:59:30Z", "GX")), NOW.toEpochMilli());
        assertTrue(r.found());
        assertEquals("+251911111111", r.msisdn());
        assertNull(r.imsi());
        assertEquals(30_000L, r.bearerAgeMs());
    }

    @Test
    void ueIdProblemsMapToResolverMisses() {
        assertEquals(FallbackReason.NO_BINDING, miss(ExposureCause.NO_BINDING));
        assertEquals(FallbackReason.AMBIGUOUS_BINDING, miss(ExposureCause.AMBIGUOUS_BINDING));
        assertEquals(FallbackReason.RESOLVER_TIMEOUT, miss(ExposureCause.NETWORK_TIMEOUT));
        assertEquals(FallbackReason.RESOLVER_TIMEOUT, miss(ExposureCause.BUDGET_EXHAUSTED));
        assertEquals(FallbackReason.RESOLVER_ERROR, miss(ExposureCause.CLIENT_UNAUTHENTICATED));
        assertEquals(FallbackReason.RESOLVER_ERROR, ExposureAdapters.resolverResult(null, 0).miss());
        assertEquals(FallbackReason.RESOLVER_ERROR, ExposureAdapters.resolverResult(ExposureOutcome.ok(
                new UeIdInfo("msisdn-abc", NOW.toString(), "GX")), 0).miss(), "non-number answer");
    }

    private static FallbackReason miss(ExposureCause cause) {
        return ExposureAdapters.resolverResult(ExposureOutcome.problem(cause, "x"), 0).miss();
    }

    @Test
    void fullEvidenceApprovesEveryFactor() {
        VerificationEvidence e = ExposureAdapters.evidence(monitoring(true, "636", "01", 5),
                simChange(NOW.minus(Duration.ofDays(10))), NOW, HOME);
        assertFalse(e.failed());
        assertTrue(e.reachable());
        assertTrue(e.notSimSwapped());
        assertTrue(e.locationPlausible());
        assertEquals("MAP_PSI+LAB_HLR", e.protocol(), "internal sources, for the CDR");
        assertEquals(5, e.location().ageMinutes());
    }

    @Test
    void noSimEvidenceOrARecentChangeIsNeverNotSwapped() {
        assertFalse(ExposureAdapters.evidence(monitoring(true, "636", "01", 5),
                ExposureOutcome.problem(ExposureCause.NO_EVIDENCE, "x"), NOW, HOME).notSimSwapped());
        assertFalse(ExposureAdapters.evidence(monitoring(true, "636", "01", 5),
                simChange(NOW.minus(Duration.ofHours(2))), NOW, HOME).notSimSwapped());
        assertFalse(ExposureAdapters.evidence(monitoring(true, "636", "01", 5), null, NOW, HOME)
                .notSimSwapped());
    }

    @Test
    void locationPolicyDecidesFromMetadataOnly() {
        assertFalse(ExposureAdapters.evidence(monitoring(true, "001", "01", 5), null, NOW, HOME)
                .locationPlausible(), "visited network");
        assertFalse(ExposureAdapters.evidence(monitoring(true, "636", "01", 600), null, NOW, HOME)
                .locationPlausible(), "stale location");
        assertFalse(ExposureAdapters.evidence(monitoring(true, null, null, null), null, NOW, HOME)
                .locationPlausible(), "no location metadata");
    }

    @Test
    void monitoringProblemIsAVerifierFailure() {
        assertEquals(FallbackReason.VERIFY_TIMEOUT, ExposureAdapters.evidence(
                ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "x"), null, NOW, HOME).failure());
        assertEquals(FallbackReason.VERIFY_ERROR, ExposureAdapters.evidence(
                ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "x"), null, NOW, HOME).failure());
        assertEquals(FallbackReason.VERIFY_ERROR, ExposureAdapters.evidence(null, null, NOW, HOME).failure());
    }

    @Test
    void simSwapReadsOnlyEvidence() {
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        assertEquals(at, ExposureAdapters.latestSimChange(simChange(at)));
        assertNull(ExposureAdapters.latestSimChange(ExposureOutcome.problem(ExposureCause.NO_EVIDENCE, "x")));
        assertNull(ExposureAdapters.latestSimChange(null));
    }

    @Test
    void requestsUseTheInternalNumberForm() {
        assertEquals("251911111111", ExposureAdapters.monitoringRequest("+251 911 111 111").msisdn());
        assertEquals("2001:db8::1", ExposureAdapters.ueIdRequest("sas-camara", "2001:db8::1", 5, 0)
                .ueIpAddr().ipv6Addr());
    }
}
