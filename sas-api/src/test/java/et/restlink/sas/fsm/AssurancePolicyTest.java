/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.fsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.api.dto.VerifyRequestDto;
import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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
        assertEquals(AssuranceLevel.HIGH,
                policy.assuranceFor(score, AssurancePolicy.RiskClass.LOGIN));
        assertEquals(AssuranceLevel.LOW,
                policy.assuranceFor(score, AssurancePolicy.RiskClass.HIGH_VALUE));
    }

    // ---- fromMap / fromRuntime (P2 #8: configurable weights + thresholds) --

    private static Map<String, String> customConfig() {
        Map<String, String> kv = new HashMap<>();
        kv.put(AssurancePolicy.KEY_W_IP_FRESH, "0.4");
        kv.put(AssurancePolicy.KEY_W_REACHABLE, "0.2");
        kv.put(AssurancePolicy.KEY_W_NOT_SIM_SWAP, "0.3");
        kv.put(AssurancePolicy.KEY_W_LOCATION, "0.1");
        kv.put(AssurancePolicy.KEY_THRESHOLD_LOGIN, "60");
        kv.put(AssurancePolicy.KEY_THRESHOLD_TRANSFER, "75");
        kv.put(AssurancePolicy.KEY_THRESHOLD_HIGH_VALUE, "95");
        return kv;
    }

    @Test
    void fromMapLoadsCustomValues() {
        AssurancePolicy custom = AssurancePolicy.fromMap(customConfig());
        assertEquals(60, custom.thresholdScore(AssurancePolicy.RiskClass.LOGIN));
        assertEquals(75, custom.thresholdScore(AssurancePolicy.RiskClass.TRANSFER));
        assertEquals(95, custom.thresholdScore(AssurancePolicy.RiskClass.HIGH_VALUE));
        // Fresh bearer + reachable only: 0.4*1.0 + 0.2 = 0.6 → 60 ≥ 60 ⇒ HIGH.
        int score = custom.score(
                ResolverResult.bound("+251911111111", "655010000000001", 10_000L),
                VerificationEvidence.ok(true, false, false, "S6a-IDR"));
        assertEquals(60, score);
        assertEquals(AssuranceLevel.HIGH,
                custom.assuranceFor(score, AssurancePolicy.RiskClass.LOGIN));
    }

    @Test
    void fromMapAbsentKeysFallBackToDefaults() {
        AssurancePolicy partial = AssurancePolicy.fromMap(Map.of(
                AssurancePolicy.KEY_THRESHOLD_TRANSFER, "85"));
        assertEquals(70, partial.thresholdScore(AssurancePolicy.RiskClass.LOGIN));
        assertEquals(85, partial.thresholdScore(AssurancePolicy.RiskClass.TRANSFER));
        assertEquals(90, partial.thresholdScore(AssurancePolicy.RiskClass.HIGH_VALUE));
        assertEquals(policy.score(
                        ResolverResult.bound("+251911111111", "655010000000001", 10_000L),
                        VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI")),
                partial.score(
                        ResolverResult.bound("+251911111111", "655010000000001", 10_000L),
                        VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI")));
    }

    @Test
    void fromRuntimeReadsViaKvSource() {
        Map<String, String> kv = customConfig();
        AssurancePolicy custom = AssurancePolicy.fromRuntime(kv::get);
        assertEquals(95, custom.thresholdScore(AssurancePolicy.RiskClass.HIGH_VALUE));
        AssurancePolicy fallback = AssurancePolicy.fromRuntime(key -> null);
        assertEquals(policy.thresholdScore(AssurancePolicy.RiskClass.TRANSFER),
                fallback.thresholdScore(AssurancePolicy.RiskClass.TRANSFER));
    }

    @Test
    void fromMapRejectsNonNormalizedWeights() {
        Map<String, String> kv = customConfig();
        kv.put(AssurancePolicy.KEY_W_LOCATION, "0.3"); // sum 1.2
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AssurancePolicy.fromMap(kv));
        assertTrue(ex.getMessage().contains("sum to 1.0"));
    }

    @Test
    void fromMapRejectsWeightOutOfRange() {
        Map<String, String> kv = customConfig();
        kv.put(AssurancePolicy.KEY_W_IP_FRESH, "1.5");
        assertThrows(IllegalArgumentException.class, () -> AssurancePolicy.fromMap(kv));
    }

    @Test
    void fromMapRejectsUnorderedThresholds() {
        Map<String, String> kv = customConfig();
        kv.put(AssurancePolicy.KEY_THRESHOLD_LOGIN, "80"); // > TRANSFER 75
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AssurancePolicy.fromMap(kv));
        assertTrue(ex.getMessage().contains("LOGIN <= TRANSFER <= HIGH_VALUE"));
    }

    @Test
    void fromMapRejectsThresholdOutOfRange() {
        Map<String, String> kv = customConfig();
        kv.put(AssurancePolicy.KEY_THRESHOLD_HIGH_VALUE, "101");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AssurancePolicy.fromMap(kv));
        assertTrue(ex.getMessage().contains(AssurancePolicy.KEY_THRESHOLD_HIGH_VALUE));

        kv.put(AssurancePolicy.KEY_THRESHOLD_HIGH_VALUE, "-1");
        assertThrows(IllegalArgumentException.class, () -> AssurancePolicy.fromMap(kv));
    }

    @Test
    void fromMapRejectsGarbageNumbersWithKeyName() {
        Map<String, String> badWeight = customConfig();
        badWeight.put(AssurancePolicy.KEY_W_NOT_SIM_SWAP, "thirty-percent");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AssurancePolicy.fromMap(badWeight));
        assertTrue(ex.getMessage().contains(AssurancePolicy.KEY_W_NOT_SIM_SWAP));

        Map<String, String> badThreshold = customConfig();
        badThreshold.put(AssurancePolicy.KEY_THRESHOLD_LOGIN, "70%");
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> AssurancePolicy.fromMap(badThreshold));
        assertTrue(ex2.getMessage().contains(AssurancePolicy.KEY_THRESHOLD_LOGIN));
    }

    // ---- VerifyRequestDto.parse (DTO risk-class helper; test owned here) —

    @Test
    void dtoParseHandlesAllCases() {
        assertNull(VerifyRequestDto.parse(null));
        assertNull(VerifyRequestDto.parse(""));
        assertNull(VerifyRequestDto.parse("   "));
        assertNull(VerifyRequestDto.parse("bogus"));
        assertNull(VerifyRequestDto.parse("high")); // prefix must not match
        assertEquals(AssurancePolicy.RiskClass.LOGIN, VerifyRequestDto.parse("login"));
        assertEquals(AssurancePolicy.RiskClass.TRANSFER, VerifyRequestDto.parse("TRANSFER"));
        assertEquals(AssurancePolicy.RiskClass.HIGH_VALUE,
                VerifyRequestDto.parse("  High_Value  "));
    }
}