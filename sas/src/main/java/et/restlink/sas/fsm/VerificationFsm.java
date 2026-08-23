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
     */
    public VerifyResult decide(String reqId,
                               ResolverResult resolver,
                               VerificationEvidence evidence,
                               String claimedMsisdn,
                               AssurancePolicy.RiskClass riskClass) {
        AssurancePolicy.RiskClass risk =
                riskClass == null ? AssurancePolicy.RiskClass.LOGIN : riskClass;
        if (resolver == null || !resolver.found()) {
            return VerifyResult.fallback(reqId,
                    resolver != null && resolver.miss() != null
                            ? resolver.miss() : FallbackReason.RESOLVER_ERROR);
        }

        // Mode A — claim asserted: resolved must equal claimed.
        if (claimedMsisdn != null && !claimedMsisdn.equals(resolver.msisdn())) {
            return VerifyResult.fallback(reqId, FallbackReason.MSISDN_MISMATCH);
        }

        // Fail closed on any verifier failure before touching the score.
        if (evidence == null) {
            return VerifyResult.fallback(reqId, FallbackReason.VERIFY_ERROR);
        }
        if (evidence.failed()) {
            return VerifyResult.fallback(reqId, evidence.failure());
        }
        if (!evidence.reachable()) {
            return VerifyResult.fallback(reqId, FallbackReason.PURGED);
        }
        if (!evidence.notSimSwapped()) {
            return VerifyResult.fallback(reqId, FallbackReason.SIM_SWAP_SUSPECT);
        }

        int score = policy.score(resolver, evidence);
        if (score < policy.thresholdScore(risk)) {
            return VerifyResult.fallback(reqId, FallbackReason.LOW_ASSURANCE);
        }

        return VerifyResult.approved(reqId, resolver.msisdn(), policy.assuranceFor(score, risk));
    }
}