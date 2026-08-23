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
 * Fail-closed decision engine tests. Mirrors the harness gates H6/H7/H11 and
 * the timeout budgets in {@code harness/gates.yaml}.
 */
class VerificationFsmTest {

    private final VerificationFsm fsm = new VerificationFsm(AssurancePolicy.defaults());

    private static ResolverResult bound() {
        return ResolverResult.bound("+251911111111", "655010000000001", 30_000L);
    }

    private static VerificationEvidence fullEvidence() {
        return VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI");
    }

    @Test
    void approvesOnFullEvidence() {
        VerifyResult r = fsm.decide("req-1", bound(), fullEvidence(), null);
        assertTrue(r.match());
        assertEquals("+251911111111", r.msisdn());
        assertEquals(AssuranceLevel.HIGH, r.assurance());
    }

    @Test
    void approvesWhenClaimMatches() {
        VerifyResult r = fsm.decide("req-2", bound(), fullEvidence(), "+251911111111");
        assertTrue(r.match());
    }

    @Test
    void fallsBackOnClaimMismatch() {
        VerifyResult r = fsm.decide("req-3", bound(), fullEvidence(), "+251922222222");
        assertFalse(r.match());
        assertEquals(FallbackReason.MSISDN_MISMATCH, r.fallbackReason());
    }

    @Test
    void fallsBackOnMissingResolver() {
        VerifyResult r = fsm.decide("req-4", ResolverResult.miss(FallbackReason.NO_BINDING),
                fullEvidence(), null);
        assertFalse(r.match());
        assertEquals(FallbackReason.NO_BINDING, r.fallbackReason());
    }

    @Test
    void fallsBackOnVerifierFailure() {
        VerifyResult r = fsm.decide("req-5", bound(),
                VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "MAP"), null);
        assertEquals(FallbackReason.VERIFY_TIMEOUT, r.fallbackReason());
    }

    @Test
    void fallsBackOnPurgedSubscriber() {
        VerifyResult r = fsm.decide("req-6", bound(),
                VerificationEvidence.ok(false, true, true, "MAP-PSI"), null);
        assertEquals(FallbackReason.PURGED, r.fallbackReason());
    }

    @Test
    void fallsBackOnSimSwapSuspect() {
        VerifyResult r = fsm.decide("req-7", bound(),
                VerificationEvidence.ok(true, false, true, "MAP-PSI+SAI"), null);
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, r.fallbackReason());
    }

    @Test
    void fallsBackOnStaleBindingLowAssurance() {
        // Old bearer (no IP-fresh credit) + reachable + non-swapped but location
        // implausible ⇒ 0 + 0.30 + 0.30 + 0.00 = 0.60 ⇒ score 60 < 75 ⇒ FALLBACK.
        ResolverResult stale = ResolverResult.bound("+251911111111", "655010000000001",
                AssurancePolicy.STALE_BEARER_MS + 1L);
        VerificationEvidence evidence = VerificationEvidence.ok(true, true, false, "MAP-PSI+SAI");
        VerifyResult r = fsm.decide("req-8", stale, evidence, null);
        assertEquals(FallbackReason.LOW_ASSURANCE, r.fallbackReason());
    }

    @Test
    void stageBudgetsStayUnderTotal() {
        assertTrue(SasTimeouts.RESOLVER_MS < SasTimeouts.TOTAL_MS);
        assertTrue(SasTimeouts.MAP_MS < SasTimeouts.TOTAL_MS);
        assertTrue(SasTimeouts.DIAMETER_MS < SasTimeouts.TOTAL_MS);
    }

    // ---- Risk-aware overload (P2 #8) -------------------------------------

    /** Score 75: stale bearer + reachable + non-swapped + plausible location. */
    private static ResolverResult staleBinding() {
        return ResolverResult.bound("+251911111111", "655010000000001",
                AssurancePolicy.STALE_BEARER_MS + 1L);
    }

    /** Score 85: fresh bearer + reachable + non-swapped, location implausible. */
    private static VerificationEvidence noLocationEvidence() {
        return VerificationEvidence.ok(true, true, false, "S6a-IDR+AIR");
    }

    @Test
    void transferThresholdRejectsLoginOnlyScore() {
        // Score 75 ≥ LOGIN(70) but < TRANSFER(80).
        VerifyResult login = fsm.decide("risk-1", staleBinding(),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"), null,
                AssurancePolicy.RiskClass.LOGIN);
        assertTrue(login.match());
        VerifyResult transfer = fsm.decide("risk-2", staleBinding(),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"), null,
                AssurancePolicy.RiskClass.TRANSFER);
        assertFalse(transfer.match());
        assertEquals(FallbackReason.LOW_ASSURANCE, transfer.fallbackReason());
    }

    @Test
    void highValueThresholdSitsAboveTransfer() {
        // Score 85 ≥ TRANSFER(80) but < HIGH_VALUE(90).
        VerifyResult transfer = fsm.decide("risk-3", bound(), noLocationEvidence(), null,
                AssurancePolicy.RiskClass.TRANSFER);
        assertTrue(transfer.match());
        VerifyResult highValue = fsm.decide("risk-4", bound(), noLocationEvidence(), null,
                AssurancePolicy.RiskClass.HIGH_VALUE);
        assertFalse(highValue.match());
        assertEquals(FallbackReason.LOW_ASSURANCE, highValue.fallbackReason());
    }

    @Test
    void nullRiskClassFallsBackToLogin() {
        VerifyResult viaNull = fsm.decide("risk-5", staleBinding(),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"), null, null);
        VerifyResult viaLogin = fsm.decide("risk-6", staleBinding(),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"), null,
                AssurancePolicy.RiskClass.LOGIN);
        assertEquals(viaLogin.match(), viaNull.match());
        assertTrue(viaNull.match());
    }

    @Test
    void legacyFourArgDecideUsesLoginThreshold() {
        // Score 75 passes only because the 4-arg overload pins LOGIN.
        VerifyResult r = fsm.decide("risk-7", staleBinding(),
                VerificationEvidence.ok(true, true, true, "MAP-PSI+SAI"), null);
        assertTrue(r.match());
        assertEquals(AssuranceLevel.HIGH, r.assurance());
    }
}