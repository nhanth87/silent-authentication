/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Evidence returned by the Verifier (identity-plane) stage.
 *
 * <pre>
 *   reachable        — PSI says attached (2G/3G) or ULR/ULA registered (4G/5G).
 *   notSimSwapped    — MAP SAI (2G/3G) or read-only Sh UDR binding age (4G/5G).
 *   locationPlausible— VLR/MME region agrees with the resolver's IP geo window.
 *   protocol         — which signalling probe(s) produced the evidence (audit).
 *   location         — optional location evidence metadata (age + serving PLMN), only
 *                      when the probe returned it; never a cell, TA or coordinate.
 * </pre>
 *
 * <p>On any failure the {@link #failure()} reason is set and all booleans are
 * meaningless — the caller MUST fail closed.</p>
 */
public record VerificationEvidence(
        boolean reachable,
        boolean notSimSwapped,
        boolean locationPlausible,
        String protocol,
        FallbackReason failure,
        LocationEvidence location) {

    /** Evidence without location metadata — what every probe produced before M2. */
    public VerificationEvidence(boolean reachable, boolean notSimSwapped,
                                boolean locationPlausible, String protocol,
                                FallbackReason failure) {
        this(reachable, notSimSwapped, locationPlausible, protocol, failure, null);
    }

    /** Same evidence, carrying the location metadata the probe returned. */
    public VerificationEvidence withLocation(LocationEvidence where) {
        return new VerificationEvidence(reachable, notSimSwapped, locationPlausible, protocol,
                failure, where);
    }

    public static VerificationEvidence ok(boolean reachable,
                                          boolean notSimSwapped,
                                          boolean locationPlausible,
                                          String protocol) {
        return new VerificationEvidence(reachable, notSimSwapped, locationPlausible,
                protocol, null);
    }

    public static VerificationEvidence fail(FallbackReason why, String protocol) {
        return new VerificationEvidence(false, false, false, protocol, why);
    }

    public boolean failed() {
        return failure != null;
    }
}