/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.fsm;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.model.VerifyResult;

/**
 * Pure, fail-closed decision engine for the SCORING stage.
 *
 * <p>The I/O stages (RESOLVING / VERIFYING) are driven by the SBB against the
 * Resource Adaptors with bounded dialogs; this class owns the deterministic
 * SCORING → APPROVED/FALLBACK transition.</p>
 *
 * <p>Fail-closed order matters (no short-circuit soft-pass):</p>
 * <ol>
 *   <li>claimedMSISDN mismatch → FALLBACK</li>
 *   <li>verifier failure/timeout → FALLBACK</li>
 *   <li>unreachable/purged → FALLBACK</li>
 *   <li>SIM-swap suspect → FALLBACK</li>
 *   <li>score &lt; threshold → FALLBACK</li>
 *   <li>otherwise → APPROVED</li>
 * </ol>
 */
public final class VerificationFsm {

    private final AssurancePolicy policy;

    public VerificationFsm(AssurancePolicy policy) {
        this.policy = policy;
    }

    public AssurancePolicy policy() {
        return policy;
    }

    public VerifyResult decide(String reqId,
                               ResolverResult resolver,
                               VerificationEvidence evidence,
                               String claimedMsisdn) {
        return decide(reqId, resolver, evidence, claimedMsisdn, AssurancePolicy.RiskClass.LOGIN);
    }

    /**
     * Risk-aware decision: the score is compared against
     * {@link AssurancePolicy#thresholdScore(AssurancePolicy.RiskClass)}. A
     * {@code null} {@code riskClass} falls back to LOGIN (the /verify flow is a
     * login; documented fail-safe default).
     *
     * <p>Every terminal result carries the assurance snapshot (score,
     * threshold, per-factor values and weights) so the bank backend can make
     * its own risk decision; the decision logic itself is unchanged.</p>
     */
    public VerifyResult decide(String reqId,
                               ResolverResult resolver,
                               VerificationEvidence evidence,
                               String claimedMsisdn,
                               AssurancePolicy.RiskClass riskClass) {
        AssurancePolicy.RiskClass risk =
                riskClass == null ? AssurancePolicy.RiskClass.LOGIN : riskClass;
        if (resolver == null || !resolver.found()) {
            return fallback(reqId,
                    resolver != null && resolver.miss() != null
                            ? resolver.miss() : FallbackReason.RESOLVER_ERROR,
                    resolver, evidence, risk);
        }

        // Mode A — claim asserted: resolved must equal claimed.
        if (claimedMsisdn != null && !claimedMsisdn.equals(resolver.msisdn())) {
            return fallback(reqId, FallbackReason.MSISDN_MISMATCH, resolver, evidence, risk);
        }

        // Fail closed on any verifier failure before touching the score.
        if (evidence == null) {
            return fallback(reqId, FallbackReason.VERIFY_ERROR, resolver, evidence, risk);
        }
        if (evidence.failed()) {
            return fallback(reqId, evidence.failure(), resolver, evidence, risk);
        }
        if (!evidence.reachable()) {
            return fallback(reqId, FallbackReason.PURGED, resolver, evidence, risk);
        }
        if (!evidence.notSimSwapped()) {
            return fallback(reqId, FallbackReason.SIM_SWAP_SUSPECT, resolver, evidence, risk);
        }

        int score = policy.score(resolver, evidence);
        int threshold = policy.thresholdScore(risk);
        if (score < threshold) {
            return fallbackWithSnapshot(reqId, FallbackReason.LOW_ASSURANCE,
                    score, threshold, risk, resolver, evidence);
        }
        VerifyResult.Factor[] f = factors(resolver, evidence);

        return VerifyResult.approved(reqId, resolver.msisdn(),
                policy.assuranceFor(score, risk), score, threshold, risk.name(),
                f[0], f[1], f[2], f[3]);
    }

    /**
     * Fail-closed result with whatever assurance was measurable — for SBB
     * early-exit branches outside the FSM (resolver miss, missing anchor).
     */
    public VerifyResult fallback(String reqId,
                                 FallbackReason reason,
                                 ResolverResult resolver,
                                 VerificationEvidence evidence,
                                 AssurancePolicy.RiskClass riskClass) {
        AssurancePolicy.RiskClass risk =
                riskClass == null ? AssurancePolicy.RiskClass.LOGIN : riskClass;
        return fallbackWithSnapshot(reqId, reason,
                policy.score(resolver, evidence), policy.thresholdScore(risk),
                risk, resolver, evidence);
    }

    private VerifyResult fallbackWithSnapshot(String reqId,
                                              FallbackReason reason,
                                              int score,
                                              int threshold,
                                              AssurancePolicy.RiskClass risk,
                                              ResolverResult resolver,
                                              VerificationEvidence evidence) {
        VerifyResult.Factor[] f = factors(resolver, evidence);
        return VerifyResult.fallback(reqId, reason, score, threshold, risk.name(),
                f[0], f[1], f[2], f[3]);
    }

    /** Per-factor evidence values with the configured weights (null-safe). */
    private VerifyResult.Factor[] factors(ResolverResult resolver,
                                          VerificationEvidence evidence) {
        double ipValue = resolver != null && resolver.found()
                ? AssurancePolicy.ipFreshFactor(resolver.bearerAgeMs()) : 0.0;
        return new VerifyResult.Factor[]{
                new VerifyResult.Factor(ipValue, policy.wIpFresh()),
                new VerifyResult.Factor(
                        evidence != null && evidence.reachable() ? 1.0 : 0.0, policy.wReachable()),
                new VerifyResult.Factor(
                        evidence != null && evidence.notSimSwapped() ? 1.0 : 0.0, policy.wNotSimSwap()),
                new VerifyResult.Factor(
                        evidence != null && evidence.locationPlausible() ? 1.0 : 0.0, policy.wLocation()),
        };
    }
}