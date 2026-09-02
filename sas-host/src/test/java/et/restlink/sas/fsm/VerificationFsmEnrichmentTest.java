/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.fsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.model.VerifyResult;

import org.junit.jupiter.api.Test;

/**
 * The FSM decision outcomes now carry the full assurance snapshot (score,
 * threshold, per-factor values + weights) without any change to the
 * fail-closed decision logic itself.
 */
class VerificationFsmEnrichmentTest {

    private final VerificationFsm fsm = new VerificationFsm(AssurancePolicy.defaults());

    private static ResolverResult bound() {
        return ResolverResult.bound("+251911111111", "655010000000001", 30_000L);
    }

    private static VerificationEvidence fullEvidence() {
        return VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI");
    }

    @Test
    void approvedResultCarriesFullSnapshot() {
        VerifyResult r = fsm.decide("enr-1", bound(), fullEvidence(), null);
        assertTrue(r.match());
        assertTrue(r.hasAssurance());
        assertEquals(100, r.score());
        assertEquals(70, r.threshold());
        assertEquals("LOGIN", r.riskClass());
        assertEquals(VerifyResult.DECISION_APPROVE, r.decision());
        assertEquals(AssuranceLevel.HIGH, r.assurance());

        // factor values: fresh bearer + reachable + not-swapped + plausible
        assertEquals(1.0, r.ipBindingFresh().value());
        assertEquals(1.0, r.reachable().value());
        assertEquals(1.0, r.notSimSwapped().value());
        assertEquals(1.0, r.locationPlausible().value());
        // default weights 0.25/0.30/0.30/0.15
        assertEquals(0.25, r.ipBindingFresh().weight());
        assertEquals(0.30, r.reachable().weight());
        assertEquals(0.30, r.notSimSwapped().weight());
        assertEquals(0.15, r.locationPlausible().weight());
    }

    @Test
    void scoreMatchesWeightedFactorSumRounded() {
        VerifyResult r = fsm.decide("enr-2", bound(), fullEvidence(), null,
                AssurancePolicy.RiskClass.TRANSFER);
        double sum = r.ipBindingFresh().contribution()
                + r.reachable().contribution()
                + r.notSimSwapped().contribution()
                + r.locationPlausible().contribution();
        assertEquals((int) Math.round(sum), r.score());
        assertEquals(100, r.score());
    }

    @Test
    void lowAssuranceFallbackCarriesScoreBelowThreshold() {
        ResolverResult stale = ResolverResult.bound("+251911111111", "655010000000001",
                AssurancePolicy.STALE_BEARER_MS + 1L);
        VerificationEvidence evidence = VerificationEvidence.ok(true, true, false, "S6A-ULR+Sh-UDR");
        VerifyResult r = fsm.decide("enr-3", stale, evidence, null);
        assertFalse(r.match());
        assertEquals(FallbackReason.LOW_ASSURANCE, r.fallbackReason());
        assertTrue(r.hasAssurance());
        // 0 (stale ip) + 0.30 + 0.30 + 0 = 60 < 70.
        assertEquals(60, r.score());
        assertEquals(70, r.threshold());
        assertEquals(VerifyResult.DECISION_FALLBACK, r.decision());
        assertEquals(AssuranceLevel.FALLBACK, r.assurance());
        assertEquals(0.0, r.ipBindingFresh().value());
        assertEquals(0.0, r.locationPlausible().value());
    }

    @Test
    void resolverMissFallbackStillCarriesThresholdAndZeroIpFactor() {
        VerifyResult r = fsm.decide("enr-4", ResolverResult.miss(FallbackReason.NO_BINDING),
                null, null);
        assertFalse(r.match());
        assertEquals(FallbackReason.NO_BINDING, r.fallbackReason());
        assertTrue(r.hasAssurance());
        assertEquals(70, r.threshold());
        assertEquals("LOGIN", r.riskClass());
        assertEquals(0.0, r.ipBindingFresh().value());
        assertEquals(0.0, r.reachable().value());
        assertEquals(VerifyResult.DECISION_FALLBACK, r.decision());
    }

    @Test
    void verifierFailureFallbackCarriesPartialEvidenceValues() {
        VerifyResult r = fsm.decide("enr-5", bound(),
                VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "MAP"), null);
        assertFalse(r.match());
        assertEquals(FallbackReason.VERIFY_TIMEOUT, r.fallbackReason());
        assertTrue(r.hasAssurance());
        // IP binding was fresh; verifier factors are all zero (failed).
        assertEquals(1.0, r.ipBindingFresh().value());
        assertEquals(0.0, r.reachable().value());
        assertEquals(0.0, r.notSimSwapped().value());
        // partial score: only the ip-fresh credit lands.
        assertEquals(25, r.score());
    }

    @Test
    void riskClassLabelFlowsIntoSnapshot() {
        VerifyResult transfer = fsm.decide("enr-6",
                ResolverResult.bound("+251911111111", "655010000000001",
                        AssurancePolicy.STALE_BEARER_MS + 1L),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"), null,
                AssurancePolicy.RiskClass.TRANSFER);
        assertFalse(transfer.match()); // 75 < TRANSFER threshold 80
        assertEquals("TRANSFER", transfer.riskClass());
        assertEquals(80, transfer.threshold());
        assertEquals(75, transfer.score());

        VerifyResult highValue = fsm.decide("enr-7", bound(), fullEvidence(), null,
                AssurancePolicy.RiskClass.HIGH_VALUE);
        assertTrue(highValue.match()); // 100 >= HIGH_VALUE threshold 90
        assertEquals("HIGH_VALUE", highValue.riskClass());
        assertEquals(90, highValue.threshold());
    }
}
