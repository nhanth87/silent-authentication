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
 * mobile app. A {@link #fallbackReason()} other than {@code null} means the
 * request failed closed.</p>
 */
public record VerifyResult(
        String reqId,
        boolean match,
        String msisdn,
        AssuranceLevel assurance,
        FallbackReason fallbackReason) {

    public static VerifyResult approved(String reqId, String msisdn, AssuranceLevel assurance) {
        return new VerifyResult(reqId, true, msisdn, assurance, null);
    }

    public static VerifyResult fallback(String reqId, FallbackReason reason) {
        return new VerifyResult(reqId, false, null, AssuranceLevel.FALLBACK, reason);
    }
}