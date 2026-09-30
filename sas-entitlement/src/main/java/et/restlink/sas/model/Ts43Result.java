/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Terminal answer of one TS.43 hop.
 *
 * <p>Three outcomes, and no fourth:</p>
 * <ul>
 *   <li>{@link Outcome#CHALLENGE} — an {@code EAP-Request/AKA-Challenge} the UE must
 *       answer. Carries a base64 EAP packet. Also returned after a resync, when the
 *       peer sent {@code AT_AUTS}.</li>
 *   <li>{@link Outcome#SUCCESS} — the peer proved SIM possession and the claimed
 *       number was confirmed against the network. Carries the entitlement token.</li>
 *   <li>{@link Outcome#FAILURE} — everything else. Missing evidence, a refused
 *       vector, a bad {@code AT_MAC}, a mismatched number: all fail closed.</li>
 * </ul>
 *
 * <p><strong>No soft pass.</strong> There is no {@code CHALLENGE} that doubles as
 * success and no success that carries a partially verified identity, because a
 * caller that mishandles a failure would otherwise see a usable token.</p>
 *
 * <p><strong>Privacy (H8)</strong>: the IMSI is deliberately absent from this record.
 * The peer proved it; the bank backend needs the MSISDN, which it gets by exchanging
 * the token on the authenticated {@code /entitlement/exchange} surface.</p>
 *
 * @param reqId            idempotency key; the same id replays the same answer
 * @param outcome          see {@link Outcome}
 * @param challengeB64     base64 EAP packet for {@link Outcome#CHALLENGE}, else null
 * @param token            entitlement token for {@link Outcome#SUCCESS}, else null
 * @param expiresInSeconds token lifetime for {@link Outcome#SUCCESS}, else 0
 * @param message          operator-readable reason, never an identity
 */
public record Ts43Result(String reqId, Outcome outcome, String challengeB64, String token,
                         long expiresInSeconds, String message) {

    /** The only three answers the TS.43 surface can give. */
    public enum Outcome {
        CHALLENGE,
        SUCCESS,
        FAILURE
    }

    public static Ts43Result challenge(String reqId, String challengeB64, String message) {
        return new Ts43Result(reqId, Outcome.CHALLENGE, challengeB64, null, 0L, message);
    }

    public static Ts43Result success(String reqId, String token, long expiresInSeconds) {
        return new Ts43Result(reqId, Outcome.SUCCESS, null, token, expiresInSeconds, null);
    }

    /**
     * A refusal. The reason is phrased for an operator reading a log — it names the
     * stage that failed, never a subscriber identity.
     */
    public static Ts43Result failure(String reqId, String message) {
        return new Ts43Result(reqId, Outcome.FAILURE, null, null, 0L, message);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}