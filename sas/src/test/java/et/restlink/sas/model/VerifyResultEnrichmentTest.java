/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Enriched {@link VerifyResult}: decision label, score/threshold snapshot,
 * per-factor values + weights. Legacy factories stay source-compatible.
 */
class VerifyResultEnrichmentTest {

    private static final VerifyResult.Factor F_IP = new VerifyResult.Factor(1.0, 0.25);
    private static final VerifyResult.Factor F_REACH = new VerifyResult.Factor(1.0, 0.30);
    private static final VerifyResult.Factor F_SIM = new VerifyResult.Factor(1.0, 0.30);
    private static final VerifyResult.Factor F_LOC = new VerifyResult.Factor(0.5, 0.15);

    @Test
    void legacyApprovedFactoryKeepsOldShape() {
        VerifyResult r = VerifyResult.approved("req-a", "+251911111111", AssuranceLevel.HIGH);
        assertTrue(r.match());
        assertEquals("+251911111111", r.msisdn());
        assertEquals(AssuranceLevel.HIGH, r.assurance());
        assertNull(r.fallbackReason());
        assertFalse(r.hasAssurance());
        assertNull(r.score());
        assertNull(r.threshold());
        assertEquals(VerifyResult.DECISION_APPROVE, r.decision());
    }

    @Test
    void legacyFallbackFactoryKeepsOldShape() {
        VerifyResult r = VerifyResult.fallback("req-b", FallbackReason.NO_BINDING);
        assertFalse(r.match());
        assertNull(r.msisdn());
        assertEquals(AssuranceLevel.FALLBACK, r.assurance());
        assertEquals(FallbackReason.NO_BINDING, r.fallbackReason());
        assertFalse(r.hasAssurance());
        assertEquals(VerifyResult.DECISION_FALLBACK, r.decision());
    }

    @Test
    void legacyConstructorNormalizesDecision() {
        VerifyResult approve = new VerifyResult("req-c", true, null, AssuranceLevel.HIGH, null);
        assertEquals(VerifyResult.DECISION_APPROVE, approve.decision());
        VerifyResult fb = new VerifyResult("req-d", false, null,
                AssuranceLevel.FALLBACK, FallbackReason.PURGED);
        assertEquals(VerifyResult.DECISION_FALLBACK, fb.decision());
    }

    @Test
    void enrichedApprovedCarriesSnapshot() {
        VerifyResult r = VerifyResult.approved("req-e", "+251911111111",
                AssuranceLevel.HIGH, 85, 70, "LOGIN", F_IP, F_REACH, F_SIM, F_LOC);
        assertTrue(r.match());
        assertTrue(r.hasAssurance());
        assertEquals(85, r.score());
        assertEquals(70, r.threshold());
        assertEquals("LOGIN", r.riskClass());
        assertEquals(VerifyResult.DECISION_APPROVE, r.decision());
        assertNull(r.fallbackReason());
        assertEquals(1.0, r.ipBindingFresh().value());
        assertEquals(0.25, r.ipBindingFresh().weight());
        assertEquals(0.5, r.locationPlausible().value());
        assertEquals(0.15, r.locationPlausible().weight());
    }

    @Test
    void enrichedFallbackCarriesMeasurableSnapshot() {
        VerifyResult r = VerifyResult.fallback("req-f", FallbackReason.LOW_ASSURANCE,
                60, 70, "LOGIN", F_IP, F_REACH, F_SIM,
                new VerifyResult.Factor(0.0, 0.15));
        assertFalse(r.match());
        assertTrue(r.hasAssurance());
        assertEquals(AssuranceLevel.FALLBACK, r.assurance());
        assertEquals(FallbackReason.LOW_ASSURANCE, r.fallbackReason());
        assertEquals(VerifyResult.DECISION_FALLBACK, r.decision());
        assertEquals(60, r.score());
        assertEquals(70, r.threshold());
    }

    @Test
    void factorContributionMath() {
        // score == sum(value*weight*100), rounded.
        double sum = F_IP.contribution() + F_REACH.contribution()
                + F_SIM.contribution() + F_LOC.contribution();
        assertEquals(92.5, sum, 1e-9);
        assertEquals((int) Math.round(sum),
                VerifyResult.approved("req-g", null, AssuranceLevel.HIGH,
                        (int) Math.round(sum), 70, "LOGIN",
                        F_IP, F_REACH, F_SIM, F_LOC).score());
    }
}
