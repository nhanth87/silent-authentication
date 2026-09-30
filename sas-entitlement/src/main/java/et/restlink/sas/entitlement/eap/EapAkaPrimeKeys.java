/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * EAP-AKA' (RFC 9048) key derivation — the **server side** of a TS.43 EAP-AKA
 * exchange (TS.43 §2.8.1 / D6 Shape S: the entitlement service is the EAP
 * server). Pure computation, no I/O.
 *
 * <p>Two stages, both taken verbatim from the normative text:</p>
 * <ol>
 *   <li><strong>CK' / IK'</strong> — TS 33.402 Normative Annex A.2, using the generic
 *       KDF of TS 33.220 Annex B.2:
 *       <pre>
 *       Key = CK || IK
 *       S   = FC(0x20) || P0 || L0 || P1 || L1
 *       P0  = access network name (ASCII, from AT_KDF_INPUT)
 *       P1  = AK  (6 bytes)
 *       output = HMAC-SHA-256(Key, S);  MS 128 bits = CK', LS 128 bits = IK'
 *       </pre>
 *       ⚠ Annex A.2 prints {@code P1 = SQN AK} with {@code L1 = 0x0006}. The length
 *       fixes P1 at <em>6 octets</em>, i.e. <strong>AK only</strong> — SQN is not an input.
 *       Verified against RFC 9048 Appendix D Case 3/4: including SQN (11-byte P1) does
 *       not reproduce the published CK'/IK', AK-only does.</li>
 *   <li><strong>K_encr / K_aut / K_re / MSK / EMSK</strong> — RFC 9048 §3.3:
 *       <pre>
 *       MK      = PRF'(IK' || CK', "EAP-AKA'" || Identity)      [208 bytes]
 *       K_encr  = MK[  0 .. 127]  (16)
 *       K_aut   = MK[128 .. 383]  (32)
 *       K_re    = MK[384 .. 639]  (32)
 *       MSK     = MK[640 ..1151]  (64)
 *       EMSK    = MK[1152..1663]  (64)
 *       </pre>
 *       with PRF' the IKEv2 PRF (RFC 7296 §2.13): {@code T1 = HMAC-SHA-256(K, S|0x01)},
 *       {@code Tn = HMAC-SHA-256(K, T(n-1) | S | n)}.</li>
 * </ol>
 *
 * <p><strong>Why this matters for the plan.</strong> CK'/IK' are <em>not</em> produced by
 * this class — the operator HSS does that (TS 33.402 §6: the HSS "transforms this
 * authentication vector by computing CK' and IK'"). What the EAP server needs is
 * stage 2 only, given CK'/IK' from the vector. {@link #deriveSessionKeys} is therefore the
 * production entry point; {@link #derivePrime} exists so the test suite can prove the
 * stage-1 construction against RFC 9048 Appendix D.</p>
 *
 * <p><strong>Key handling.</strong> Every {@code byte[]} returned here is a fresh
 * allocation the caller owns; nothing is cached or interned. The caller is expected to
 * {@link #wipe} them at the end of the activity (plan §3.4, H25/H25M).</p>
 */
public final class EapAkaPrimeKeys {

    /** TS 33.402 Annex A.2: function code for the CK'/IK' derivation. */
    public static final int FC_CK_IK_DERIVATION = 0x20;

    /** RFC 9048 §3.3: the fixed label fed to PRF'. Eight ASCII chars, no NUL. */
    public static final byte[] LABEL_EAP_AKA_PRIME =
            "EAP-AKA'".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** RFC 9048 §3.3: fast re-authentication label. */
    public static final byte[] LABEL_EAP_AKA_PRIME_REAUTH =
            "EAP-AKA' re-auth".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static final int K_ENCR_LEN = 16;
    public static final int K_AUT_LEN = 32;
    public static final int K_RE_LEN = 32;
    public static final int MSK_LEN = 64;
    public static final int EMSK_LEN = 64;

    /** K_encr ‖ K_aut ‖ K_re ‖ MSK ‖ EMSK = 16+32+32+64+64. */
    public static final int MK_LEN = K_ENCR_LEN + K_AUT_LEN + K_RE_LEN + MSK_LEN + EMSK_LEN;

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private EapAkaPrimeKeys() {
    }

    /**
     * Immutable bundle of the RFC 9048 §3.3 output. {@code record} gives us a
     * value type; the components are mutable arrays, so the record is only safe
     * because {@link #wipe()} is the documented way to dispose of it.
     */
    public record SessionKeys(
            byte[] kEncr, byte[] kAut, byte[] kRe, byte[] msk, byte[] emsk) {

        /** Overwrite every key buffer. Idempotent; call on every exit path. */
        public void wipe() {
            EapAkaPrimeKeys.wipe(kEncr);
            EapAkaPrimeKeys.wipe(kAut);
            EapAkaPrimeKeys.wipe(kRe);
            EapAkaPrimeKeys.wipe(msk);
            EapAkaPrimeKeys.wipe(emsk);
        }

        /** Never let a key reach a log line or a stack trace. */
        @Override
        public String toString() {
            return "SessionKeys[kEncr=<" + kEncr.length + "B>, kAut=<" + kAut.length
                    + "B>, kRe=<" + kRe.length + "B>, msk=<" + msk.length
                    + "B>, emsk=<" + emsk.length + "B>]";
        }
    }

    /**
     * TS 33.402 Annex A.2 — derive CK' and IK' from CK, IK, AK and the access
     * network name. The HSS normally does this; it is here so the construction
     * can be proven against RFC 9048 Appendix D and so a lab HSS simulator can
     * reuse it.
     *
     * @param ck            16-byte ciphering key from the AKA vector
     * @param ik            16-byte integrity key from the AKA vector
     * @param ak            6-byte anonymity key from the AKA vector
     * @param networkName   access network name, exactly as carried in AT_KDF_INPUT
     *                      (no length prefix, no padding, no NUL)
     * @return {@code [0..127]} = CK', {@code [128..255]} = IK'
     */
    public static byte[] derivePrime(byte[] ck, byte[] ik, byte[] ak, byte[] networkName) {
        require(ck != null && ck.length == 16, "CK must be 16 bytes");
        require(ik != null && ik.length == 16, "IK must be 16 bytes");
        require(ak != null && ak.length == 6, "AK must be 6 bytes");
        require(networkName != null, "access network name is required");

        byte[] s = buildS(FC_CK_IK_DERIVATION, networkName, ak);
        byte[] key = new byte[32];
        System.arraycopy(ck, 0, key, 0, 16);
        System.arraycopy(ik, 0, key, 16, 16);
        try {
            byte[] out = hmacSha256(key, s);
            wipe(key);
            wipe(s);
            return out;
        } catch (GeneralSecurityException e) {
            wipe(key);
            wipe(s);
            throw new IllegalStateException("HMAC-SHA-256 unavailable — cannot derive CK'/IK'", e);
        }
    }

    /**
     * RFC 9048 §3.3 — the production entry point. Given the CK'/IK' the HSS
     * already computed, produce the EAP session keys from the peer's identity.
     *
     * @param ckPrime    16-byte CK' (MS half of the TS 33.402 KDF output)
     * @param ikPrime    16-byte IK' (LS half)
     * @param identity   peer identity exactly as sent in AT_IDENTITY / EAP-Response/Identity
     */
    public static SessionKeys deriveSessionKeys(byte[] ckPrime, byte[] ikPrime, byte[] identity) {
        require(ckPrime != null && ckPrime.length == 16, "CK' must be 16 bytes");
        require(ikPrime != null && ikPrime.length == 16, "IK' must be 16 bytes");
        require(identity != null && identity.length > 0, "identity is required");

        // RFC 9048 §3.3: MK = PRF'(IK' || CK', "EAP-AKA'" | Identity) — note the order.
        byte[] prfKey = new byte[32];
        System.arraycopy(ikPrime, 0, prfKey, 0, 16);
        System.arraycopy(ckPrime, 0, prfKey, 16, 16);
        byte[] seed = new byte[LABEL_EAP_AKA_PRIME.length + identity.length];
        System.arraycopy(LABEL_EAP_AKA_PRIME, 0, seed, 0, LABEL_EAP_AKA_PRIME.length);
        System.arraycopy(identity, 0, seed, LABEL_EAP_AKA_PRIME.length, identity.length);

        byte[] mk = null;
        try {
            mk = prfPrime(prfKey, seed, MK_LEN);
        } finally {
            wipe(prfKey);
            wipe(seed);
        }

        int at = 0;
        byte[] kEncr = slice(mk, at, K_ENCR_LEN);  at += K_ENCR_LEN;
        byte[] kAut = slice(mk, at, K_AUT_LEN);    at += K_AUT_LEN;
        byte[] kRe = slice(mk, at, K_RE_LEN);      at += K_RE_LEN;
        byte[] msk = slice(mk, at, MSK_LEN);       at += MSK_LEN;
        byte[] emsk = slice(mk, at, EMSK_LEN);
        wipe(mk);
        return new SessionKeys(kEncr, kAut, kRe, msk, emsk);
    }

    /**
     * RFC 9048 §3.4.1 PRF' — the IKEv2 PRF (RFC 7296 §2.13), HMAC-SHA-256 based.
     *
     * <pre>
     * T1 = HMAC-SHA-256(K, S | 0x01)
     * T2 = HMAC-SHA-256(K, T1 | S | 0x02)
     * T3 = HMAC-SHA-256(K, T2 | S | 0x03) ...
     * </pre>
     */
    public static byte[] prfPrime(byte[] key, byte[] seed, int length) {
        require(length > 0, "PRF' output length must be positive");
        require(length <= 255 * 32, "PRF' output length exceeds the counter range");
        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        int produced = 0;
        int counter = 1;
        while (produced < length) {
            byte[] input = new byte[previous.length + seed.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(seed, 0, input, previous.length, seed.length);
            input[input.length - 1] = (byte) counter;
            byte[] block;
            try {
                block = hmacSha256(key, input);
            } catch (GeneralSecurityException e) {
                wipe(out);
                throw new IllegalStateException("HMAC-SHA-256 unavailable — PRF' failed", e);
            }
            wipe(input);
            int take = Math.min(block.length, length - produced);
            System.arraycopy(block, 0, out, produced, take);
            produced += take;
            wipe(previous);
            previous = block;
            counter++;
        }
        wipe(previous);
        return out;
    }

    /**
     * TS 33.402 Annex A.1 / TS 33.220 Annex B.2 input string:
     * {@code S = FC || P0 || L0 || P1 || L1}, each {@code Li} a two-octet
     * big-endian length.
     */
    static byte[] buildS(int fc, byte[] p0, byte[] p1) {
        byte[] s = new byte[1 + p0.length + 2 + p1.length + 2];
        int at = 0;
        s[at++] = (byte) fc;
        System.arraycopy(p0, 0, s, at, p0.length);
        at += p0.length;
        s[at++] = (byte) ((p0.length >>> 8) & 0xFF);
        s[at++] = (byte) (p0.length & 0xFF);
        System.arraycopy(p1, 0, s, at, p1.length);
        at += p1.length;
        s[at++] = (byte) ((p1.length >>> 8) & 0xFF);
        s[at] = (byte) (p1.length & 0xFF);
        return s;
    }

    private static byte[] slice(byte[] source, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, from, out, 0, length);
        return out;
    }

    private static byte[] hmacSha256(byte[] key, byte[] message)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_SHA_256);
        mac.init(new SecretKeySpec(key, HMAC_SHA_256));
        return mac.doFinal(message);
    }

    /** Overwrite a key buffer in place. Null-safe so it can be used in a finally block. */
    public static void wipe(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
