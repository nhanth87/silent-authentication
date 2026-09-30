/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Result of the Resolver (data-plane) stage: IP:port:ts → MSISDN/IMSI.
 *
 * <p>Exactly one of {@link #found()} / {@link #miss()} is populated. A miss
 * carries the concrete {@link FallbackReason} so the FSM can fail closed with
 * a precise, auditable outcome.</p>
 */
public record ResolverResult(
        String msisdn,
        String imsi,
        long bearerAgeMs,
        FallbackReason miss) {

    public static ResolverResult bound(String msisdn, String imsi, long bearerAgeMs) {
        return new ResolverResult(msisdn, imsi, bearerAgeMs, null);
    }

    public static ResolverResult miss(FallbackReason why) {
        return new ResolverResult(null, null, 0L, why);
    }

    public boolean found() {
        return msisdn != null;
    }
}