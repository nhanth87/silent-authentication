/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * TS.43 {@code eap-aka'} / {@code eap-aka} bootstrap exchange, one event per hop.
 *
 * <p>The entitlement service is a <b>two-hop</b> protocol, not a request/response:
 * the UE first fetches a Challenge, then answers it. Both hops travel as events
 * inside the <b>same activity</b> (the {@code reqId} the REST layer minted), so the
 * SLEE entity carries the EAP state between them. That is why the REST surface
 * only ever <em>submits</em> and awaits: it never owns the session
 * (H24 — {@code slee_boundary}).</p>
 *
 * <ul>
 *   <li>{@link Phase#CHALLENGE} — fetch a vector from the network (MAP SAI /
 *       SWx), build {@code EAP-Request/AKA-Challenge} and return it. No EAP state
 *       exists yet.</li>
 *   <li>{@link Phase#RESPOND} — verify {@code AT_MAC} then {@code AT_RES} against
 *       the stored session, confirm the claimed MSISDN against the network, and
 *       mint the entitlement token on success.</li>
 * </ul>
 *
 * <p><strong>Privacy</strong>: {@link #imsi()} is the identity the SIM proved. It
 * stays inside the SAS — it is never echoed in a {@link Ts43Result}, and the
 * entitlement token, not the IMSI, is what the bank backend gets.</p>
 */
@EventType(name = "Ts43Request", vendor = "et.restlink.sas", version = "1.0")
public final class Ts43RequestEvent implements SleeEvent {

    /** Which hop of the TS.43 exchange this event carries. */
    public enum Phase {
        /** Fetch a Challenge. */
        CHALLENGE,
        /** Answer a previously issued Challenge. */
        RESPOND
    }

    private final String reqId;
    private final Phase phase;
    private final String imsi;
    private final String claimedMsisdn;
    private final String responseB64;

    public Ts43RequestEvent(String reqId, Phase phase, String imsi,
                            String claimedMsisdn, String responseB64) {
        this.reqId = reqId;
        this.phase = phase;
        this.imsi = imsi;
        this.claimedMsisdn = claimedMsisdn;
        this.responseB64 = responseB64;
    }

    public static Ts43RequestEvent challenge(String reqId, String imsi, String claimedMsisdn) {
        return new Ts43RequestEvent(reqId, Phase.CHALLENGE, imsi, claimedMsisdn, null);
    }

    public static Ts43RequestEvent respond(String reqId, String imsi, String claimedMsisdn,
                                           String responseB64) {
        return new Ts43RequestEvent(reqId, Phase.RESPOND, imsi, claimedMsisdn, responseB64);
    }

    public String reqId() {
        return reqId;
    }

    public Phase phase() {
        return phase;
    }

    /** SIM identity in digits; required on both hops so a hop cannot be swapped. */
    public String imsi() {
        return imsi;
    }

    /**
     * The number the bank claims. Optional on {@link Phase#CHALLENGE} when the
     * network source can discover the number by itself (SWx/SAR); required for
     * MAP {@code SendIMSI}, which is number-driven and can only confirm a claim.
     */
    public String claimedMsisdn() {
        return claimedMsisdn;
    }

    /** Base64 EAP-Response from the UE; {@link Phase#RESPOND} only. */
    public String responseB64() {
        return responseB64;
    }
}