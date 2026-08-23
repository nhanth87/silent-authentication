/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Terminal result of one {@code /verify} request.
 *
 * <p>Privacy rule: {@link #msisdn()} is only populated on an APPROVED match
 * and is delivered to the <em>bank backend</em> over mTLS only — never to the
 * mobile app, and never serialised northbound. A {@link #fallbackReason()}
 * other than {@code null} means the request failed closed.</p>
 *
 * <p>Enrichment (P2): besides the CAMARA boolean, the result carries the
 * assurance snapshot — {@link #score()} vs {@link #threshold()}, the risk
 * class label and the per-factor evidence values plus their configured
 * weights — so the bank backend can make its own risk decision. Components
 * are {@code null} when the stage never ran (legacy constructors, northbound
 * budget timeouts).</p>
 */
public record VerifyResult(
        String reqId,
        boolean match,
        String msisdn,
        AssuranceLevel assurance,
        FallbackReason fallbackReason,
        String decision,
        Integer score,
        Integer threshold,
        String riskClass,
        Factor ipBindingFresh,
        Factor reachable,
        Factor notSimSwapped,
        Factor locationPlausible) {

    /** Bank-visible decision label for an approved request. */
    public static final String DECISION_APPROVE = "APPROVE";

    /** Bank-visible decision label for a fail-closed request. */
    public static final String DECISION_FALLBACK = "FALLBACK";

    /** One assurance factor: evidence value 0..1 and its configured weight. */
    public record Factor(double value, double weight) {

        /** Contribution of this factor to the 0..100 score. */
        public double contribution() {
            return value * weight * 100.0;
        }
    }

    /** Normalises a blank decision from the fail-closed outcome. */
    public VerifyResult {
        if (decision == null || decision.isBlank()) {
            decision = fallbackReason != null ? DECISION_FALLBACK : DECISION_APPROVE;
        }
    }

    /** Legacy five-field view for pre-enrichment callers and tests. */
    public VerifyResult(String reqId,
                        boolean match,
                        String msisdn,
                        AssuranceLevel assurance,
                        FallbackReason fallbackReason) {
        this(reqId, match, msisdn, assurance, fallbackReason,
                null, null, null, null, null, null, null, null);
    }

    /** True when the score/threshold snapshot was computed for this result. */
    public boolean hasAssurance() {
        return score != null && threshold != null;
    }

    // ---- factories -------------------------------------------------------

    /** Legacy approved factory (no assurance snapshot). */
    public static VerifyResult approved(String reqId, String msisdn, AssuranceLevel assurance) {
        return new VerifyResult(reqId, true, msisdn, assurance, null,
                DECISION_APPROVE, null, null, null, null, null, null, null);
    }

    /** Approved factory carrying the full assurance snapshot. */
    public static VerifyResult approved(String reqId,
                                        String msisdn,
                                        AssuranceLevel assurance,
                                        int score,
                                        int threshold,
                                        String riskClass,
                                        Factor ipBindingFresh,
                                        Factor reachable,
                                        Factor notSimSwapped,
                                        Factor locationPlausible) {
        return new VerifyResult(reqId, true, msisdn, assurance, null,
                DECISION_APPROVE, score, threshold, riskClass,
                ipBindingFresh, reachable, notSimSwapped, locationPlausible);
    }

    /** Legacy fail-closed factory (no assurance snapshot). */
    public static VerifyResult fallback(String reqId, FallbackReason reason) {
        return new VerifyResult(reqId, false, null, AssuranceLevel.FALLBACK, reason,
                DECISION_FALLBACK, null, null, null, null, null, null, null);
    }

    /** Fail-closed factory carrying whatever assurance was measurable. */
    public static VerifyResult fallback(String reqId,
                                        FallbackReason reason,
                                        int score,
                                        int threshold,
                                        String riskClass,
                                        Factor ipBindingFresh,
                                        Factor reachable,
                                        Factor notSimSwapped,
                                        Factor locationPlausible) {
        return new VerifyResult(reqId, false, null, AssuranceLevel.FALLBACK, reason,
                DECISION_FALLBACK, score, threshold, riskClass,
                ipBindingFresh, reachable, notSimSwapped, locationPlausible);
    }
}
