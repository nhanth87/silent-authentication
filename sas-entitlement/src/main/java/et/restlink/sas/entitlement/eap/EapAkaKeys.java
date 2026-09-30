/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * EAP-AKA (RFC 4187) key generation — the plain-EAP-AKA sibling of
 * {@link EapAkaPrimeKeys}. Pure computation, no I/O.
 *
 * <p>RFC 4187 §7, transcribed:</p>
 * <pre>
 * MK = SHA1( Identity || IK || CK )
 * then the FIPS 186-2 PRF (RFC 4187 Annex A) with XKEY = MK, b = 160,
 * whose concatenated output is partitioned in this order:
 *     K_encr (128 bits) | K_aut (128 bits) | MSK (64 bytes) | EMSK (64 bytes)
 * </pre>
 *
 * <p>⚠ <strong>AT_MAC uses HMAC-SHA-1-128</strong> here (RFC 4187 §10.15), keyed with
 * {@code K_aut}; EAP-AKA' replaced it with HMAC-SHA-256-128 (RFC 9048 §3.4.2). The two
 * methods are not interchangeable — {@link #macAka} and {@link EapAkaPrimeKeys} must never
 * be mixed for one transaction.</p>
 *
 * <p><strong>Test-vector status.</strong> RFC 4187 publishes <em>no</em> known-answer
 * vectors for this construction, so {@code EapAkaKeysTest} pins the two pieces that can be
 * pinned independently (SHA-1 of the documented input string, and the FIPS 186-2 PRF's
 * {@code G} function) and the rest is UAT-only. EAP-AKA' is the KAT-covered path
 * (RFC 9048 Appendix D); treat plain EAP-AKA as the fallback until a real UE has been
 * run against it in Phase 1e.</p>
 */
public final class EapAkaKeys {

    public static final int K_ENCR_LEN = 16;
    public static final int K_AUT_LEN = 16;
    public static final int MSK_LEN = 64;
    public static final int EMSK_LEN = 64;

    /** K_encr ‖ K_aut ‖ MSK ‖ EMSK = 16+16+64+64. */
    public static final int MK_LEN = K_ENCR_LEN + K_AUT_LEN + MSK_LEN + EMSK_LEN;

    /** RFC 4187 Annex A step 2: the FIPS SHS initial value for the PRF's G function. */
    private static final byte[] PRF_T = {
            (byte) 0x67, (byte) 0x45, (byte) 0x23, (byte) 0x01,
            (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89,
            (byte) 0x98, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE,
            (byte) 0x10, (byte) 0x32, (byte) 0x54, (byte) 0x76,
            (byte) 0xC3, (byte) 0xD2, (byte) 0xE1, (byte) 0xF0
    };

    private static final String HMAC_SHA_1 = "HmacSHA1";

    private EapAkaKeys() {
    }

    /** RFC 4187 §7 output bundle. Wipe on every exit path. */
    public record SessionKeys(byte[] kEncr, byte[] kAut, byte[] msk, byte[] emsk) {

        public void wipe() {
            EapAkaKeys.wipe(kEncr);
            EapAkaKeys.wipe(kAut);
            EapAkaKeys.wipe(msk);
            EapAkaKeys.wipe(emsk);
        }

        @Override
        public String toString() {
            return "SessionKeys[kEncr=<" + kEncr.length + "B>, kAut=<" + kAut.length
                    + "B>, msk=<" + msk.length + "B>, emsk=<" + emsk.length + "B>]";
        }
    }

    /**
     * RFC 4187 §7.
     *
     * @param ck       16-byte ciphering key from the AKA vector
     * @param ik       16-byte integrity key from the AKA vector
     * @param identity peer identity, no trailing NULs (AT_IDENTITY or EAP-Response/Identity)
     */
    public static SessionKeys deriveSessionKeys(byte[] ck, byte[] ik, byte[] identity) {
        if (ck == null || ck.length != 16) {
            throw new IllegalArgumentException("CK must be 16 bytes");
        }
        if (ik == null || ik.length != 16) {
            throw new IllegalArgumentException("IK must be 16 bytes");
        }
        if (identity == null || identity.length == 0) {
            throw new IllegalArgumentException("identity is required");
        }

        byte[] mkSeed = new byte[identity.length + 32];
        System.arraycopy(identity, 0, mkSeed, 0, identity.length);
        System.arraycopy(ik, 0, mkSeed, identity.length, 16);
        System.arraycopy(ck, 0, mkSeed, identity.length + 16, 16);
        byte[] masterKey = sha1(mkSeed);
        wipe(mkSeed);

        byte[] mk = null;
        try {
            mk = fips186Prf(masterKey, MK_LEN);
        } finally {
            wipe(masterKey);
        }

        int at = 0;
        byte[] kEncr = slice(mk, at, K_ENCR_LEN); at += K_ENCR_LEN;
        byte[] kAut = slice(mk, at, K_AUT_LEN);   at += K_AUT_LEN;
        byte[] msk = slice(mk, at, MSK_LEN);      at += MSK_LEN;
        byte[] emsk = slice(mk, at, EMSK_LEN);
        wipe(mk);
        return new SessionKeys(kEncr, kAut, msk, emsk);
    }

    /**
     * RFC 4187 §10.15 — the {@code AT_MAC} value: HMAC-SHA-1-128 over the
     * octets preceding the AT_MAC attribute (Type + Length + Value), truncated
     * to 16 bytes.
     *
     * <p>Note the RFC 4187 rule that the recipient MUST process AT_MAC before
     * any other attribute (except inside a Challenge); {@link EapAkaServer}
     * enforces that ordering.</p>
     *
     * @param kAut      16-byte authentication key
     * @param message   the byte range covered by the MAC
     */
    public static byte[] macAka(byte[] kAut, byte[] message) {
        if (kAut == null || kAut.length != K_AUT_LEN) {
            throw new IllegalArgumentException("K_aut must be 16 bytes");
        }
        if (message == null) {
            throw new IllegalArgumentException("MAC input is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_1);
            mac.init(new SecretKeySpec(kAut, HMAC_SHA_1));
            return Arrays.copyOf(mac.doFinal(message), 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-1 unavailable — cannot compute AT_MAC", e);
        }
    }

    /** Constant-time comparison for {@code AT_MAC} / RES-XRES. Length-safe. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(
                a == null ? new byte[0] : a,
                b == null ? new byte[0] : b);
    }

    /**
     * The FIPS 186-2 PRF exactly as cited by RFC 4187 Annex A. The counter-mode
     * construction with the modified {@code G} function, {@code b = 160}.
     *
     * <pre>
     * Step 1: XKEY = master key
     * Step 2: t = 67452301 EFCDAB89 98BADCFE 10325476 C3D2E1F0
     * Step 3: for j = 0 .. m-1
     *           XSEED_j = 0
     *           for i = 0 .. 1
     *               XVAL = (XKEY + XSEED_j) mod 2^160
     *               w_i  = G(t, XVAL)
     *               XKEY = (1 + XKEY + w_i) mod 2^160
     *           x_j = w_0 | w_1
     * </pre>
     */
    static byte[] fips186Prf(byte[] xkey, int lengthBytes) {
        if (xkey.length != 20) {
            throw new IllegalArgumentException("FIPS 186-2 PRF seed must be 20 bytes");
        }
        if (lengthBytes <= 0) {
            throw new IllegalArgumentException("PRF output length must be positive");
        }
        int blocks = (lengthBytes + 39) / 40;
        byte[] out = new byte[lengthBytes];
        byte[] key = Arrays.copyOf(xkey, xkey.length);
        int produced = 0;
        for (int j = 0; j < blocks; j++) {
            byte[] w0 = null;
            byte[] w1 = null;
            for (int i = 0; i < 2; i++) {
                // XSEED_j = 0, so XVAL is simply the current XKEY.
                byte[] g = g(PRF_T, key);
                if (i == 0) {
                    w0 = g;
                } else {
                    w1 = g;
                }
                // XKEY = (1 + XKEY + w_i) mod 2^160
                addOnePlus(key, g);
            }
            // x_j = w_0 | w_1, and each w_i is a 160-bit G() output — 20 octets,
            // not 16. The MK partition is 16+16+64+64, which is NOT a multiple of 40,
            // so the last block is copied in two slices.
            int take = Math.min(40, lengthBytes - produced);
            int fromW0 = Math.min(20, take);
            System.arraycopy(w0, 0, out, produced, fromW0);
            if (take > 20) {
                System.arraycopy(w1, 0, out, produced + 20, take - 20);
            }
            produced += take;
            wipe(w0);
            wipe(w1);
        }
        wipe(key);
        return out;
    }

    /**
     * FIPS 186-2 {@code G(X, Y)} with {@code Z} absent: the SHA-1 compression
     * function over {@code X || Y} with SHA-1's initial value, standard SHA-1
     * padding, and the length field set to the bit length of {@code X || Y}
     * (320 bits) rather than the true message length. {@code Z} is omitted by
     * construction — the PRF of RFC 4187 Annex A passes no third parameter.
     */
    static byte[] g(byte[] x, byte[] y) {
        if (x.length != 20 || y.length != 20) {
            throw new IllegalArgumentException("G takes two 160-bit inputs");
        }
        // 40 bytes of message + 0x80 + zero fill to 56 + 8-byte big-endian length (320).
        byte[] block = new byte[64];
        System.arraycopy(x, 0, block, 0, 20);
        System.arraycopy(y, 0, block, 20, 20);
        block[40] = (byte) 0x80;
        long bits = 320L;
        for (int i = 0; i < 8; i++) {
            block[63 - i] = (byte) (bits >>> (8 * i));
        }
        return sha1CompressOneBlock(block);
    }

    /** SHA-1 over an arbitrary message. */
    static byte[] sha1(byte[] message) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(message);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable — EAP-AKA cannot run", e);
        }
    }

    /**
     * One SHA-1 compression round set over a single, already-padded 64-byte
     * block, seeded with SHA-1's initial hash value. Written out because
     * {@link MessageDigest} cannot be told to use a non-standard IV with a
     * caller-supplied length field.
     */
    private static byte[] sha1CompressOneBlock(byte[] block) {
        int[] h = {
                0x67452301, 0xEFCDAB89, (int) 0x98BADCFE, 0x10325476, (int) 0xC3D2E1F0
        };
        int[] w = new int[80];
        for (int i = 0; i < 16; i++) {
            w[i] = ((block[i * 4] & 0xFF) << 24)
                    | ((block[i * 4 + 1] & 0xFF) << 16)
                    | ((block[i * 4 + 2] & 0xFF) << 8)
                    | (block[i * 4 + 3] & 0xFF);
        }
        for (int i = 16; i < 80; i++) {
            w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        }
        int a = h[0];
        int b = h[1];
        int c = h[2];
        int d = h[3];
        int e = h[4];
        for (int i = 0; i < 80; i++) {
            int f;
            int k;
            if (i < 20) {
                f = (b & c) | (~b & d);
                k = 0x5A827999;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = (int) 0x8F1BBCDC;
            } else {
                f = b ^ c ^ d;
                k = (int) 0xCA62C1D6;
            }
            int temp = Integer.rotateLeft(a, 5) + f + e + k + w[i];
            e = d;
            d = c;
            c = Integer.rotateLeft(b, 30);
            b = a;
            a = temp;
        }
        byte[] out = new byte[20];
        putInt(out, 0, h[0] + a);
        putInt(out, 4, h[1] + b);
        putInt(out, 8, h[2] + c);
        putInt(out, 12, h[3] + d);
        putInt(out, 16, h[4] + e);
        return out;
    }

    /** {@code XKEY = (XKEY + w + 1) mod 2^160}, in place. */
    private static void addOnePlus(byte[] key, byte[] addend) {
        int carry = 1;
        for (int i = key.length - 1; i >= 0; i--) {
            int sum = (key[i] & 0xFF) + (addend[i] & 0xFF) + carry;
            key[i] = (byte) (sum & 0xFF);
            carry = sum >>> 8;
        }
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static byte[] slice(byte[] source, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, from, out, 0, length);
        return out;
    }

    /** Overwrite a key buffer in place. Null-safe for use in a finally block. */
    public static void wipe(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }
}
