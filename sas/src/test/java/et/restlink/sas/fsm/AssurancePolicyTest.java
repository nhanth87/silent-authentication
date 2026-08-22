/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.fsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;

import org.junit.jupiter.api.Test;

/**
 * Weighted scoring tests (w1=0.25, w2=0.30, w3=0.30, w4=0.15, threshold=0.75).
 */
class AssurancePolicyTest {

    private final AssurancePolicy policy = AssurancePolicy.defaults();

    @Test
    void fullEvidenceScores100() {
        int score = policy.score(
                ResolverResult.bound("+251911111111", "655010000000001", 10_000L),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"));
        assertEquals(100, score);
    }

    @Test
    void staleBindingDropsIpFreshCredit() {
        int score = policy.score(
                ResolverResult.bound("+251911111111", "655010000000001",
                        AssurancePolicy.STALE_BEARER_MS + 1L),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"));
        // 0.25*0 + 0.30 + 0.30 + 0.15 = 0.75 → 75
        assertEquals(75, score);
    }

    @Test
    void ipFreshFactorStagesByAge() {
        assertEquals(1.0, AssurancePolicy.ipFreshFactor(10_000L), 1e-9);
        assertEquals(0.5, AssurancePolicy.ipFreshFactor(120_000L), 1e-9);
        assertEquals(0.0, AssurancePolicy.ipFreshFactor(AssurancePolicy.STALE_BEARER_MS + 1L), 1e-9);
    }

    @Test
    void weightsMustSumToOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssurancePolicy(0.25, 0.30, 0.30, 0.30, 0.70, 0.80, 0.90));
    }

    @Test
    void thresholdMapsToScore() {
        assertTrue(policy.thresholdScore() > 0 && policy.thresholdScore() < 100);
    }

    @Test
    void riskClassThresholdsAreOrdered() {
        assertTrue(policy.thresholdScore(AssurancePolicy.RiskClass.LOGIN)
                <= policy.thresholdScore(AssurancePolicy.RiskClass.TRANSFER));
        assertTrue(policy.thresholdScore(AssurancePolicy.RiskClass.TRANSFER)
                <= policy.thresholdScore(AssurancePolicy.RiskClass.HIGH_VALUE));
    }

    @Test
    void highValueRequiresHigherScore() {
        // Score 75 passes LOGIN (threshold 70) but fails HIGH_VALUE (threshold 90).
        int score = policy.score(
                ResolverResult.bound("+251911111111", "655010000000001",
                        AssurancePolicy.STALE_BEARER_MS + 1L),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"));
        assertEquals(75, score);
        assertEquals(et.restlink.sas.model.AssuranceLevel.HIGH,
                policy.assuranceFor(score, AssurancePolicy.RiskClass.LOGIN));
        assertEquals(et.restlink.sas.model.AssuranceLevel.LOW,
                policy.assuranceFor(score, AssurancePolicy.RiskClass.HIGH_VALUE));
    }
}