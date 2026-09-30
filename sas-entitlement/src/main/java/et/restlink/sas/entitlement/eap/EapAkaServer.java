/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * EAP-AKA / EAP-AKA' <strong>server</strong> state machine — the D6/Shape S core.
 * Pure: it owns no timer, no socket and no thread; the caller
 * ({@code EntitlementSbb}, plan §3.4) drives the transitions and owns the
 * timeouts.
 *
 * <p>Flow (RFC 4187 §4 / RFC 9048 §3):</p>
 * <pre>
 *   NEW ──begin(identity, vector)──► CHALLENGE_SENT
 *        │                              │
 *        │                    ──acceptResponse(packet)──►
 *        │                              ├─ AT_AUTS present ─► RESYNC_SENT ─(one retry)─► CHALLENGE_SENT
 *        │                              ├─ RES == XRES + MAC ok ─► VERIFIED
 *        │                              └─ anything else ─► FAILED
 *        └─ failure(any stage) ─► FAILED
 * </pre>
 *
 * <p><strong>Fail-closed.</strong> Every non-happy path ends in
 * {@link State#FAILED} and never leaks a boolean. A caller that receives
 * {@link Result#SUCCESS} is the only path that proves the peer holds the SIM key.</p>
 *
 * <p><strong>Resync cap.</strong> RFC 4187 §4.4 allows an {@code AT_AUTS}
 * resynchronisation; this machine permits <strong>at most one</strong>, because each
 * retry moves the AuC's sequence number and a loop against a live network is how a
 * third-party EAP server desynchronises a subscriber (plan §2.1.1, D6 consequence).</p>
 *
 * <p><strong>Key hygiene.</strong> The vector's key material is held only for the
 * duration of {@link #acceptResponse} and wiped on every exit path, including
 * failures. The derived session keys are handed to the caller once, in
 * {@link Result}; the caller is responsible for wiping them.</p>
 */
public final class EapAkaServer {

    /** FSM states. Terminal states never transition again. */
    public enum State {
        /** No vector loaded. */
        NEW,
        /** A Challenge has been handed to the peer; awaiting the Response. */
        CHALLENGE_SENT,
        /** An AT_AUTS resynchronisation is outstanding. */
        RESYNC_SENT,
        /** RES matched XRES and the MAC verified. */
        VERIFIED,
        /** Terminal: authentication did not succeed. */
        FAILED
    }

    /** Outcome of one transition. */
    public enum Result {
        /** Caller must send a Challenge to the peer. */
        SEND_CHALLENGE,
        /** Caller must send an EAP-Success and may read the session keys. */
        SUCCESS,
        /** Caller must send an EAP-Failure. */
        FAILURE
    }

    /** CAPACITY_PERMANENT and friends are peer-supplied {@code AT_CLIENT_ERROR_CODE} values. */
    public static final int AT_CLIENT_ERROR_CODE_CAPACITY_PERMANENT = 27;

    private static final int MAX_RESYNC = 1;

    private State state = State.NEW;

    /** Session keys; non-null only between a SUCCESS and takeSessionKeys()/wipe(). */
    private DerivedKeys sessionKeys;

    /**
     * Method-independent view of the derived session keys, so the machine can serve
     * plain EAP-AKA (RFC 4187: {@code K_aut} 16 octets) and EAP-AKA' (RFC 9048:
     * {@code K_aut} 32 octets) through one accessor. {@code kRe} is null for
     * plain EAP-AKA, which has no re-authentication key.
     */
    public record DerivedKeys(byte[] kEncr, byte[] kAut, byte[] kRe, byte[] msk, byte[] emsk) {

        public void wipe() {
            EapAkaKeys.wipe(kEncr);
            EapAkaKeys.wipe(kAut);
            EapAkaKeys.wipe(kRe);
            EapAkaKeys.wipe(msk);
            EapAkaKeys.wipe(emsk);
        }

        @Override
        public String toString() {
            return "DerivedKeys[kEncr=<" + kEncr.length + "B>, kAut=<" + kAut.length
                    + "B>, kRe=" + (kRe == null ? "n/a" : "<" + kRe.length + "B>")
                    + ", msk=<" + msk.length + "B>, emsk=<" + emsk.length + "B>]";
        }
    }

    /** The HSS-supplied expected response. */
    private byte[] xres;

    /** AT_MAC from the Challenge, verified against the peer's Response. */
    private byte[] challengeMac;

    private byte[] res;

    private int resyncCount;

    private String failureReason;

    private EapAkaServer() {
    }

    /**
     * Start a session.
     *
     * @param identity  peer identity the Challenge is being issued to
     * @param ckPrime   16-byte CK' (EAP-AKA' — TS 33.402 Annex A.2, HSS-derived), or
     *                  16-byte CK for plain EAP-AKA
     * @param ikPrime   16-byte IK' / IK
     * @param rand      16-byte RAND from the AKA vector
     * @param autn      16-byte AUTN from the AKA vector
     * @param xres      expected response from the AKA vector (UMTS quintuplet {@code RES}
     *                  or SWx {@code SIP-Authorization}); GSM {@code SRES} triplets are
     *                  not acceptable to this path
     * @param atKdfInput access network name for EAP-AKA' (empty for plain EAP-AKA)
     */
    public static EapAkaServer begin(byte[] identity, byte[] ckPrime, byte[] ikPrime,
                                     byte[] rand, byte[] autn, byte[] xres,
                                     byte[] atKdfInput) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ckPrime, "ckPrime");
        Objects.requireNonNull(ikPrime, "ikPrime");
        Objects.requireNonNull(rand, "rand");
        Objects.requireNonNull(autn, "autn");
        Objects.requireNonNull(xres, "xres");
        EapAkaServer server = new EapAkaServer();
        server.identity = identity.clone();
        server.ckPrime = ckPrime.clone();
        server.ikPrime = ikPrime.clone();
        server.rand = rand.clone();
        server.autn = autn.clone();
        server.xres = xres.clone();
        server.atKdfInput = atKdfInput == null ? new byte[0] : atKdfInput.clone();
        // GSM triplet: only 4 octets of SRES and no 16-octet CK/IK. If the vector
        // cannot support EAP-AKA there is no point starting a session.
        if (xres.length < 8 || xres.length > 16) {
            server.fail("vector is not a UMTS quintuplet/SWx EAP-AKA response");
        } else {
            server.state = State.CHALLENGE_SENT;
        }
        return server;
    }

    private byte[] identity;
    private byte[] ckPrime;
    private byte[] ikPrime;
    private byte[] rand;
    private byte[] autn;
    private byte[] atKdfInput;

    public State state() {
        return state;
    }

    /** Failure reason, or empty when the session is healthy. */
    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public int resyncCount() {
        return resyncCount;
    }

    /**
     * Build the {@code EAP-Request/AKA-Challenge} for the current state.
     *
     * <p>Carries {@code AT_RAND}, {@code AT_AUTN} and — for EAP-AKA' —
     * {@code AT_KDF_INPUT} plus {@code AT_KDF} = 1 (RFC 9048 §3.1/§3.2). No
     * {@code AT_MAC}: a Challenge is not MACed (RFC 4187 §10.15).</p>
     */
    public EapPacket buildChallenge(int identifier, boolean akaPrime) {
        if (state != State.CHALLENGE_SENT) {
            throw new IllegalStateException("challenge is only valid in CHALLENGE_SENT, not " + state);
        }
        List<EapAkaAttributes.Attribute> attributes = new java.util.ArrayList<>();
        attributes.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RAND, rand));
        attributes.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTN, autn));
        if (akaPrime) {
            attributes.add(new EapAkaAttributes.Attribute(
                    EapAkaAttributes.AT_KDF_INPUT, atKdfInput));
            attributes.add(new EapAkaAttributes.Attribute(
                    EapAkaAttributes.AT_KDF, new byte[] {1}));
        }
        return EapPacket.of(EapPacket.Code.REQUEST, identifier,
                akaPrime ? 50 : 23, EapAkaAttributes.serialize(attributes));
    }

    /**
     * Process the peer's {@code EAP-Response/AKA-Challenge}.
     *
     * <p>Ordering is normative: {@code AT_MAC} must be processed before any other
     * attribute (RFC 4187 §10.15). Only then is {@code AT_RES} compared to
     * {@code XRES} in constant time.</p>
     *
     * @param packet     the peer's response, already decoded
     * @param akaPrime   true to use HMAC-SHA-256-128 for AT_MAC (RFC 9048 §3.4.2),
     *                   false for HMAC-SHA-1-128 (RFC 4187 §10.15)
     */
    public Result acceptResponse(EapPacket packet, boolean akaPrime) {
        Objects.requireNonNull(packet, "packet");
        if (state != State.CHALLENGE_SENT && state != State.RESYNC_SENT) {
            return fail("no outstanding challenge (state " + state + ")");
        }
        if (packet.code() != EapPacket.Code.RESPONSE) {
            return fail("expected an EAP Response, got " + packet.code());
        }
        if (packet.type() != 23 && packet.type() != 50) {
            return fail("unexpected EAP method type " + packet.type());
        }

        List<EapAkaAttributes.Attribute> attributes;
        try {
            attributes = EapAkaAttributes.parse(packet.data());
        } catch (EapPacket.MalformedPacketException e) {
            return fail("undecodable attributes: " + e.getMessage());
        }

        // 1. AT_MAC first, per RFC 4187 §10.15.
        int macIndex = indexOf(attributes, EapAkaAttributes.AT_MAC);
        if (macIndex < 0) {
            return fail("AT_MAC missing");
        }
        byte[] presentedMac = attributes.get(macIndex).value();
        if (presentedMac.length != 16) {
            return fail("AT_MAC must be 16 octets, got " + presentedMac.length);
        }
        sessionKeys = deriveKeys(akaPrime);
        if (sessionKeys == null) {
            return fail("key derivation failed");
        }
        byte[] coverage = EapAkaAttributes.Attribute.macCoverage(attributes, macIndex, presentedMac);
        byte[] expectedMac = akaPrime
                ? macAkaPrime(sessionKeys.kAut(), coverage)
                : EapAkaKeys.macAka(sessionKeys.kAut(), coverage);
        if (!EapAkaKeys.constantTimeEquals(expectedMac, presentedMac)) {
            EapAkaKeys.wipe(expectedMac);
            wipeKeys();
            return fail("AT_MAC mismatch");
        }
        EapAkaKeys.wipe(expectedMac);

        // 2. AT_AUTS is a resync request and takes precedence over AT_RES.
        byte[] auts = EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_AUTS);
        if (auts != null) {
            if (auts.length != 16) {
                wipeKeys();
                return fail("AT_AUTS must be 16 octets");
            }
            if (resyncCount >= MAX_RESYNC) {
                wipeKeys();
                return fail("resynchronisation refused: the " + MAX_RESYNC
                        + "-retry cap stops AuC sequence desync");
            }
            this.auts = auts;
            resyncCount++;
            state = State.RESYNC_SENT;
            // The caller refetches a fresh vector and calls beginResync().
            return Result.SEND_CHALLENGE;
        }

        // 3. AT_RES against XRES.
        byte[] res = EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_RES);
        if (res == null) {
            wipeKeys();
            return fail("AT_RES missing");
        }
        if (!EapAkaKeys.constantTimeEquals(res, xres)) {
            wipeKeys();
            return fail("AT_RES does not match XRES");
        }
        this.res = res;

        // 4. Done — keys stay in the machine until takeSessionKeys()/wipe().
        EapAkaKeys.wipe(xres);
        state = State.VERIFIED;
        return Result.SUCCESS;
    }

    /** The {@code AT_AUTS} the peer sent, valid only in {@link State#RESYNC_SENT}. */
    public byte[] auts() {
        return auts == null ? null : auts.clone();
    }

    /**
     * Complete a resynchronisation: the caller has fetched a fresh vector from the
     * HSS and calls this to return the machine to {@link State#CHALLENGE_SENT}.
     */
    public void beginResync(byte[] freshXres, byte[] freshRand, byte[] freshAutn) {
        if (state != State.RESYNC_SENT) {
            throw new IllegalStateException("no outstanding resync (state " + state + ")");
        }
        Objects.requireNonNull(freshXres, "freshXres");
        Objects.requireNonNull(freshRand, "freshRand");
        Objects.requireNonNull(freshAutn, "freshAutn");
        EapAkaKeys.wipe(xres);
        xres = freshXres.clone();
        rand = freshRand.clone();
        autn = freshAutn.clone();
        auts = null;
        state = State.CHALLENGE_SENT;
    }

    /**
     * Hand over the derived session keys, transferring ownership to the caller.
     * Only legal in {@link State#VERIFIED}.
     */
    public DerivedKeys takeSessionKeys() {
        if (state != State.VERIFIED || sessionKeys == null) {
            throw new IllegalStateException("no verified session keys (state " + state + ")");
        }
        DerivedKeys out = sessionKeys;
        sessionKeys = null;
        return out;
    }

    /** Wipe every secret this machine holds. Idempotent; call on every exit path. */
    public void wipe() {
        wipeKeys();
        EapAkaKeys.wipe(xres);
        EapAkaKeys.wipe(identity);
        EapAkaKeys.wipe(ckPrime);
        EapAkaKeys.wipe(ikPrime);
        EapAkaKeys.wipe(rand);
        EapAkaKeys.wipe(autn);
        EapAkaKeys.wipe(auts);
        EapAkaKeys.wipe(res);
        state = State.FAILED;
    }

    /** Peer identity as a UTF-8 string, for logging/attribution only. */
    public String identityAsString() {
        return new String(identity, StandardCharsets.UTF_8);
    }

    /**
     * RFC 4187 §7 for plain EAP-AKA, RFC 9048 §3.3 for EAP-AKA'. The two differ in
     * hash function, key size and label, so they must not share a code path.
     */
    private DerivedKeys deriveKeys(boolean akaPrime) {
        // ⚠ Ownership: the two SessionKeys records expose their internal arrays
        // directly, so a plain hand-off would alias them and the wipe() below would
        // zero the keys the caller is about to use. DerivedKeys therefore takes a
        // private copy and the derivation result is destroyed immediately.
        try {
            if (akaPrime) {
                EapAkaPrimeKeys.SessionKeys k =
                        EapAkaPrimeKeys.deriveSessionKeys(ckPrime, ikPrime, identity);
                try {
                    return new DerivedKeys(k.kEncr().clone(), k.kAut().clone(),
                            k.kRe().clone(), k.msk().clone(), k.emsk().clone());
                } finally {
                    k.wipe();
                }
            }
            EapAkaKeys.SessionKeys k = EapAkaKeys.deriveSessionKeys(ckPrime, ikPrime, identity);
            try {
                return new DerivedKeys(k.kEncr().clone(), k.kAut().clone(), null,
                        k.msk().clone(), k.emsk().clone());
            } finally {
                k.wipe();
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] macAkaPrime(byte[] kAut, byte[] message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(kAut, "HmacSHA256"));
            // RFC 9048 §3.4.2: HMAC-SHA-256 truncated to the first 16 octets.
            return java.util.Arrays.copyOf(mac.doFinal(message), 16);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", e);
        }
    }

    private static int indexOf(List<EapAkaAttributes.Attribute> attributes, int type) {
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }

    private byte[] auts;

    private Result fail(String reason) {
        failureReason = reason;
        wipeKeys();
        EapAkaKeys.wipe(xres);
        state = State.FAILED;
        return Result.FAILURE;
    }

    private void wipeKeys() {
        if (sessionKeys != null) {
            sessionKeys.wipe();
            sessionKeys = null;
        }
    }

    @Override
    public String toString() {
        return "EapAkaServer[state=" + state + ", resync=" + resyncCount
                + (failureReason == null ? "" : ", reason=" + failureReason) + "]";
    }
}
