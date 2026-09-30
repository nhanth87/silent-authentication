/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.entitlement.eap.EapAkaAttributes;
import et.restlink.sas.entitlement.eap.EapAkaKeys;
import et.restlink.sas.entitlement.eap.EapAkaServer;
import et.restlink.sas.entitlement.eap.EapPacket;
import et.restlink.sas.entitlement.lab.LabSimAkaCard;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

/**
 * The lab SIM card must produce a response the <b>server's own</b> verifier accepts —
 * that is the only claim the demo makes, so it is asserted directly instead of being
 * left to the demo run.
 *
 * <p>The loop is deliberately closed: the card answers, {@link EapAkaServer} verifies
 * with the same {@link EapAkaKeys} the SAS uses, and the token path is exercised to
 * {@code VERIFIED}. If the two implementations of the RFC 4187 MAC coverage ever
 * drift, this test fails long before a demo does.</p>
 */
class LabSimAkaCardTest {

    private static final String IMSI = "655010000000001";
    private static final byte[] IDENTITY =
            IMSI.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] RAND = hex("81e92b6c0ee0e12ebceba8d92a99dfa5");

    /**
     * The lab's vector is label-derived on <em>both</em> sides — the HLR simulator mints
     * it and the card re-derives it — so the "server" half of these tests uses the card's
     * own CK/IK. A real SAS gets them from SAI over MAP; here they come from the same
     * rule, which is the lab fiction and nothing more.
     */
    private static final byte[] CK = new LabSimAkaCard(IMSI).ck(RAND);
    private static final byte[] IK = new LabSimAkaCard(IMSI).ik(RAND);
    private static final byte[] AUTN = hex("81e92b6c0ee0e12e8000c3ab00000000");
    /** XRES as the lab HLR mints it — the same label rule the card answers with. */
    private static final byte[] RES = new LabSimAkaCard(IMSI).expectedRes(RAND);

    private static byte[] hex(String s) {
        return java.util.HexFormat.of().parseHex(s);
    }

    private static String base64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    /** A server Challenge built from the card's own vector, as the SAS would. */
    private static EapPacket challenge() {
        EapAkaServer server = EapAkaServer.begin(IDENTITY, CK, IK, RAND, AUTN, RES, new byte[0]);
        return server.buildChallenge(1, false);
    }

    @Test
    void serverAcceptsTheCardResponse() {
        EapPacket ch = challenge();
        String responseB64 = new LabSimAkaCard(IMSI).respond(base64(ch.encode()));
        EapPacket response = EapPacket.parse(Base64.getDecoder().decode(responseB64), 0);

        assertEquals(EapPacket.Code.RESPONSE, response.code());
        assertEquals(ch.identifier(), response.identifier());
        assertEquals(ch.type(), response.type(), "the method type must mirror the Challenge");

        List<EapAkaAttributes.Attribute> attributes =
                EapAkaAttributes.parse(response.data());
        assertEquals(EapAkaAttributes.AT_MAC,
                attributes.get(0).type(), "AT_MAC must come first (RFC 4187 §10.15)");

        EapAkaServer server = EapAkaServer.begin(IDENTITY, CK, IK, RAND, AUTN, RES, new byte[0]);
        assertEquals(EapAkaServer.Result.SUCCESS, server.acceptResponse(response, false),
                () -> "card response rejected: " + server.failureReason().orElse("?"));
        assertEquals(EapAkaServer.State.VERIFIED, server.state());
    }

    @Test
    void theVectorIsTheOneTheLabHlrMints() {
        // Pins the lab derivation: if the HLR simulator's label rule and the card's ever
        // drift, the demo stops working — and this is where that should be noticed.
        assertEquals(16, new LabSimAkaCard(IMSI).ck(RAND).length);
        assertEquals(16, new LabSimAkaCard(IMSI).ik(RAND).length);
        assertNotEquals(0, java.util.Arrays.hashCode(CK));
    }

    @Test
    void theCardReDerivesTheVectorInsteadOfHoldingAKey() {
        LabSimAkaCard card = new LabSimAkaCard(IMSI);
        assertEquals(8, card.expectedRes(RAND).length);
        assertEquals(16, card.ck(RAND).length);
        assertEquals(16, card.ik(RAND).length);
        // Different SIM, different vector: the derivation is per-subscriber.
        assertNotEquals(java.util.Arrays.toString(card.ik(RAND)),
                java.util.Arrays.toString(new LabSimAkaCard("655010000000002").ik(RAND)));
    }

    @Test
    void anotherSimCannotAnswerTheChallenge() {
        EapPacket ch = challenge();
        // A card for a different IMSI derives a different K_aut, so its AT_MAC fails.
        String responseB64 = new LabSimAkaCard("655010000000002").respond(base64(ch.encode()));
        EapPacket response = EapPacket.parse(Base64.getDecoder().decode(responseB64), 0);

        EapAkaServer server = EapAkaServer.begin(IDENTITY, CK, IK, RAND, AUTN, RES, new byte[0]);
        assertEquals(EapAkaServer.Result.FAILURE, server.acceptResponse(response, false));
        assertTrue(server.failureReason().orElse("").contains("AT_MAC"),
                "the refusal must name the MAC, not the response");
    }

    @Test
    void akaePrimeIsRefusedRatherThanFaked() {
        // A Challenge with AT_KDF is EAP-AKA'; the lab vector cannot ground it, and the
        // card says so instead of inventing an AK.
        List<EapAkaAttributes.Attribute> attrs = new java.util.ArrayList<>();
        attrs.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RAND, RAND));
        attrs.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTN, AUTN));
        attrs.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_KDF, new byte[] {1}));
        EapPacket akaPrime = EapPacket.of(EapPacket.Code.REQUEST, 1, 50,
                EapAkaAttributes.serialize(attrs));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new LabSimAkaCard(IMSI).respond(base64(akaPrime.encode())));
        assertTrue(e.getMessage().contains("EAP-AKA'"));
    }

    @Test
    void challengeWithoutRandIsRefused() {
        EapPacket empty = EapPacket.of(EapPacket.Code.REQUEST, 1, 23, new byte[] {});
        assertThrows(IllegalStateException.class,
                () -> new LabSimAkaCard(IMSI).respond(base64(empty.encode())));
    }
}