/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector;

import java.util.Arrays;

/**
 * One EAP-AKA authentication vector as served by the operator HSS over SWx
 * (TS 29.273 §6.2.2 MAR/MAA) or MAP SAI (TS 29.002).
 *
 * <p>Under the D6 / Shape S decision the entitlement service is the EAP server, so
 * <strong>these bytes are secret the moment they arrive</strong>: {@code ck}/{@code ik}
 * are the session keys the rest of the AKA run is built from. Callers wipe them on
 * every exit path.</p>
 *
 * @param rand  16-octet RAND from the vector
 * @param autn  16-octet AUTN from the vector
 * @param xres  expected response (SWx {@code SIP-Authorization}, or the UMTS
 *              quintuplet {@code RES}); 4..16 octets
 * @param ck    16-octet ciphering key, or {@code null} when the source does not
 *              deliver it (SWx only carries CK/IK when the HSS populates the
 *              Confidentiality-Key/Integrity-Key AVPs)
 * @param ik    16-octet integrity key, same caveat as {@code ck}
 * @param scheme {@code "EAP-AKA"} or {@code "EAP-AKA'"} — the AKA method the
 *              vector belongs to; anything else must be refused upstream
 */
public record AuthVector(byte[] rand, byte[] autn, byte[] xres, byte[] ck, byte[] ik,
                         String scheme) {

    public static final String EAP_AKA = "EAP-AKA";
    public static final String EAP_AKA_PRIME = "EAP-AKA'";

    public AuthVector {
        rand = copy(rand);
        autn = copy(autn);
        xres = copy(xres);
        ck = copy(ck);
        ik = copy(ik);
    }

    @Override
    public byte[] rand() {
        return copy(rand);
    }

    @Override
    public byte[] autn() {
        return copy(autn);
    }

    @Override
    public byte[] xres() {
        return copy(xres);
    }

    @Override
    public byte[] ck() {
        return copy(ck);
    }

    @Override
    public byte[] ik() {
        return copy(ik);
    }

    /**
     * True when the vector can drive a full EAP-AKA exchange. A vector without
     * CK/IK can still verify {@code AT_RES} against {@code XRES}, but it cannot
     * derive session keys — so the caller must treat that as a degraded, and for
     * EAP-AKA' an unusable, vector rather than silently proceeding.
     */
    public boolean hasSessionKeys() {
        return ck != null && ck.length == 16 && ik != null && ik.length == 16;
    }

    /** Wipe every secret this record holds. Idempotent. */
    public void wipe() {
        Arrays.fill(rand, (byte) 0);
        Arrays.fill(autn, (byte) 0);
        Arrays.fill(xres, (byte) 0);
        if (ck != null) {
            Arrays.fill(ck, (byte) 0);
        }
        if (ik != null) {
            Arrays.fill(ik, (byte) 0);
        }
    }

    /** Never print key material. */
    @Override
    public String toString() {
        return "AuthVector[scheme=" + scheme
                + ", rand=<" + rand.length + "B>, autn=<" + autn.length + "B>"
                + ", xres=<" + xres.length + "B>"
                + ", ck=" + (ck == null ? "absent" : "<" + ck.length + "B>")
                + ", ik=" + (ik == null ? "absent" : "<" + ik.length + "B>") + "]";
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
