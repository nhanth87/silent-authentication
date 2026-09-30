/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.lab;

import et.restlink.sas.entitlement.eap.EapAkaAttributes;
import et.restlink.sas.entitlement.eap.EapAkaKeys;
import et.restlink.sas.entitlement.eap.EapPacket;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The lab's stand-in for a SIM card, used by {@link Ts43LabDemo} to drive a real
 * EAP-AKA exchange against {@code /ts43}.
 *
 * <p>Lives in {@code src/test} on purpose: a class that answers an authentication
 * challenge without a SIM has no business in a shipped artifact, and gate H24 keeps
 * raw HTTP clients out of the runtime modules. The demo drives a <em>running</em>
 * {@code dist/run.sh} over HTTP, so it is a harness, not runtime code.
 *
 * <p><strong>Read this before believing anything the demo prints.</strong> A real
 * device holds {@code K} inside the SIM and computes
 * {@code RES = f2(K, RAND‖RES‖SQN‖AMF)} plus {@code CK}/{@code IK} through Milenage;
 * it never transmits a key and never publishes the expected response. This class does
 * none of that: it re-derives the vector with the same label-based SHA-256 rule the
 * lab HLR simulator uses
 * ({@code SHA-256("TS43-LAB-QUINTUPLET"|IMSI|RAND|<label>)}), because a demo cannot
 * carry a subscriber key. That single substitution is the entire fiction.</p>
 *
 * <p><strong>Everything after that substitution is real.</strong> The MAC coverage,
 * the {@code K_aut} derivation (RFC 4187 Annex A: {@code SHA-1(Identity|IK|CK)} then
 * the FIPS 186-2 PRF), the attribute ordering and the {@code HMAC-SHA-1-128}
 * truncation come from the same {@link EapAkaKeys}/{@link EapAkaAttributes} code the
 * server verifies with. A mismatch in the demo is therefore a real bug rather than a
 * demo artefact — which is the whole point.</p>
 *
 * <p>Lab-only. This class must never ship in the device SDK: it is a way to answer an
 * authentication challenge without a SIM, which is exactly the capability an attacker
 * wants.</p>
 */
public final class LabSimAkaCard {

    /** Prefix the lab HLR simulator and this card share. */
    private static final String LABEL = "TS43-LAB-QUINTUPLET";

    /** Placeholder length of {@code AT_MAC} (RFC 4187 §10.15). */
    private static final int MAC_LEN = 16;

    private final String imsi;
    private final byte[] identity;

    public LabSimAkaCard(String imsi) {
        this.imsi = imsi.trim();
        this.identity = this.imsi.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public String imsi() {
        return imsi;
    }

    /** The {@code RES} the lab HLR minted for this RAND — what the card must return. */
    public byte[] expectedRes(byte[] rand) {
        return derive(rand, "RES", 8);
    }

    /** The CK the lab HLR minted; plain EAP-AKA needs it to derive {@code K_aut}. */
    public byte[] ck(byte[] rand) {
        return derive(rand, "CK", 16);
    }

    /** The IK the lab HLR minted; the root of {@code K_aut}. */
    public byte[] ik(byte[] rand) {
        return derive(rand, "IK", 16);
    }

    /**
     * Build the {@code EAP-Response/AKA-Challenge} for a server Challenge.
     *
     * @param challengeB64 base64 EAP-Request from {@code /ts43/challenge}
     * @return base64 EAP-Response, ready for {@code /ts43/respond}
     * @throws IllegalStateException on anything the card cannot answer honestly
     */
    public String respond(String challengeB64) {
        EapPacket challenge;
        List<EapAkaAttributes.Attribute> attributes;
        try {
            challenge = EapPacket.parse(Base64.getDecoder().decode(challengeB64), 0);
            attributes = EapAkaAttributes.parse(challenge.data());
        } catch (IllegalArgumentException e) {
            // A malformed Challenge is a refusal, not a parse exception the caller has
            // to understand: the card either answers or says why it cannot.
            throw new IllegalStateException("challenge is not a decodable EAP packet: "
                    + e.getMessage(), e);
        }
        byte[] rand = EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_RAND);
        byte[] autn = EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_AUTN);
        if (rand == null || rand.length != 16 || autn == null || autn.length != 16) {
            throw new IllegalStateException("challenge carries no usable RAND/AUTN");
        }
        if (EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_KDF) != null) {
            // EAP-AKA' needs AK, which MAP SAI cannot deliver (plan §3.2). The lab
            // vector is a plain EAP-AKA one; say so instead of faking the KDF.
            throw new IllegalStateException(
                    "server asked for EAP-AKA' but the lab vector cannot ground it");
        }

        // RFC 4187 Annex A: K_aut comes from Identity|IK|CK. A real SIM gets IK and CK
        // from Milenage; the lab card re-derives them with the HLR's own rule.
        byte[] kAut = EapAkaKeys.deriveSessionKeys(ck(rand), ik(rand), identity).kAut();

        // AT_MAC comes first (RFC 4187 §10.15 ordering); AT_RES carries the response.
        // No nonce round, so AT_RES is the plain RES.
        List<EapAkaAttributes.Attribute> response = new ArrayList<>();
        response.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, new byte[MAC_LEN]));
        response.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RES, expectedRes(rand)));
        response.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_IDENTITY, identity));

        byte[] mac = EapAkaKeys.macAka(kAut, coverage(response, 0));
        response.set(0, new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, mac));

        EapPacket packet = EapPacket.of(EapPacket.Code.RESPONSE, challenge.identifier(),
                challenge.type(), EapAkaAttributes.serialize(response));
        return Base64.getEncoder().encodeToString(packet.encode());
    }

    /**
     * The byte range {@code AT_MAC} covers, mirrored from
     * {@link EapAkaAttributes.Attribute#macCoverage}: every attribute in order, with
     * the {@code AT_MAC} value itself zeroed. The server recomputes exactly this.
     */
    private static byte[] coverage(List<EapAkaAttributes.Attribute> attributes, int macIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < attributes.size(); i++) {
            byte[] value = (i == macIndex) ? new byte[MAC_LEN] : attributes.get(i).value();
            out.write(attributes.get(i).type());
            out.write(value.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    /** The vector material the lab HLR would have minted, per label. */
    private byte[] derive(byte[] rand, String label, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LABEL.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) '|');
            digest.update(identity);
            digest.update((byte) '|');
            digest.update(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(rand);
            return Arrays.copyOf(digest.digest(), length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}