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
 *   reachable        — PSI/IDR says attached+reachable (2G/3G) or registered (S6a).
 *   notSimSwapped    — SAI/AIR vector-set or lastUpdate/IMSI-change age >= cooldown.
 *   locationPlausible— VLR/MME region agrees with the resolver's IP geo window.
 *   protocol         — which signalling probe(s) produced the evidence (audit).
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
        FallbackReason failure) {

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