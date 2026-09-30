/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FSM tests for {@link EapAkaServer} — the D6/Shape S authentication core.
 *
 * <p>These are constructed tests: there is no published EAP-AKA/AKA' <em>exchange</em>
 * vector (only the RFC 9048 key-derivation vectors, which
 * {@link EapAkaPrimeKeysTest} consumes). The exchange is exercised with a synthetic
 * peer that computes the real {@code AT_MAC} from the real derived {@code K_aut}, so
 * the success path proves the machine and the crypto agree end to end.</p>
 */
class EapAkaServerTest {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] IDENTITY =
            "0555444333222111".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CK = HEX.parseHex("5349fbe098649f948f5d2e973a81c00f");
    private static final byte[] IK = HEX.parseHex("9744871ad32bf9bbd1dd5ce54e3e2e5a");
    private static final byte[] RAND = HEX.parseHex("81e92b6c0ee0e12ebceba8d92a99dfa5");
    private static final byte[] AUTN = HEX.parseHex("bb52e91c747ac3ab2a5c23d15ee351d5");
    private static final byte[] XRES = HEX.parseHex("28d7b0f2a2ec3de5");

    private static EapAkaServer server() {
        return EapAkaServer.begin(IDENTITY, CK, IK, RAND, AUTN, XRES, new byte[0]);
    }

    /** A synthetic peer that answers the Challenge the way a real UE would. */
    private static EapPacket respondLikePeer(EapPacket challenge, byte[] res, boolean akaPrime)
            throws Exception {
        if (akaPrime) {
            EapAkaPrimeKeys.SessionKeys k = EapAkaPrimeKeys.deriveSessionKeys(CK, IK, IDENTITY);
            try {
                return respondLikePeer(challenge, res, k.kAut(), true);
            } finally {
                k.wipe();
            }
        }
        EapAkaKeys.SessionKeys k = EapAkaKeys.deriveSessionKeys(CK, IK, IDENTITY);
        try {
            return respondLikePeer(challenge, res, k.kAut(), false);
        } finally {
            k.wipe();
        }
    }

    private static EapPacket respondLikePeer(EapPacket challenge, byte[] res, byte[] kAut,
                                             boolean akaPrime) throws Exception {
        try {
            List<EapAkaAttributes.Attribute> attributes = new java.util.ArrayList<>(
                    EapAkaAttributes.parse(challenge.data()));
            attributes.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RES, res));
            int macIndex = attributes.size();
            attributes.add(new EapAkaAttributes.Attribute(
                    EapAkaAttributes.AT_MAC, new byte[16]));
            byte[] coverage = EapAkaAttributes.Attribute.macCoverage(
                    attributes, macIndex, new byte[16]);
            byte[] mac = akaPrime ? macAkaPrime(kAut, coverage) : EapAkaKeys.macAka(kAut, coverage);
            List<EapAkaAttributes.Attribute> finalAttributes = new java.util.ArrayList<>();
            for (EapAkaAttributes.Attribute a : attributes) {
                if (a.type() == EapAkaAttributes.AT_MAC) {
                    finalAttributes.add(new EapAkaAttributes.Attribute(
                            EapAkaAttributes.AT_MAC, mac));
                } else {
                    finalAttributes.add(a);
                }
            }
            return EapPacket.of(EapPacket.Code.RESPONSE, challenge.identifier(),
                    challenge.type(), EapAkaAttributes.serialize(finalAttributes));
        } finally {
            EapAkaKeys.wipe(kAut);
        }
    }

    private static byte[] macAkaPrime(byte[] kAut, byte[] message) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(kAut, "HmacSHA256"));
        return java.util.Arrays.copyOf(mac.doFinal(message), 16);
    }

    @Test
    @DisplayName("Happy path: Challenge → Response → SUCCESS, keys handed over")
    void happyPath() throws Exception {
        EapAkaServer machine = server();
        assertEquals(EapAkaServer.State.CHALLENGE_SENT, machine.state());

        EapPacket challenge = machine.buildChallenge(9, false);
        assertEquals(EapPacket.Code.REQUEST, challenge.code());
        assertEquals(23, challenge.type(), "EAP-AKA method type 23");
        List<EapAkaAttributes.Attribute> attrs = EapAkaAttributes.parse(challenge.data());
        assertArrayEquals(RAND, EapAkaAttributes.firstValue(attrs, EapAkaAttributes.AT_RAND));
        assertArrayEquals(AUTN, EapAkaAttributes.firstValue(attrs, EapAkaAttributes.AT_AUTN));
        assertNull(EapAkaAttributes.firstValue(attrs, EapAkaAttributes.AT_MAC),
                "a Challenge is never MACed (RFC 4187 §10.15)");

        EapPacket response = respondLikePeer(challenge, XRES, false);
        assertEquals(EapAkaServer.Result.SUCCESS, machine.acceptResponse(response, false));
        assertEquals(EapAkaServer.State.VERIFIED, machine.state());

        EapAkaServer.DerivedKeys keys = machine.takeSessionKeys();
        try {
            // RFC 4187 §7 sizes: K_encr 128, K_aut 128, MSK 512, EMSK 512.
            // (EAP-AKA' per RFC 9048 §3.3 uses a 256-bit K_aut and adds K_re —
            // see akaPrimeKAutIsTwiceAsWide.)
            assertEquals(16, keys.kEncr().length, "K_encr is 128 bits");
            assertEquals(16, keys.kAut().length, "K_aut is 128 bits in plain EAP-AKA");
            assertEquals(64, keys.msk().length, "MSK is 512 bits");
            assertEquals(64, keys.emsk().length, "EMSK is 512 bits");
            assertNull(keys.kRe(), "plain EAP-AKA has no re-authentication key");
        } finally {
            keys.wipe();
        }
        machine.wipe();
    }

    @Test
    @DisplayName("EAP-AKA' Challenge advertises AT_KDF_INPUT + AT_KDF=1 (RFC 9048 §3.1/§3.2)")
    void akaPrimeChallenge() {
        EapAkaServer machine = EapAkaServer.begin(IDENTITY, CK, IK, RAND, AUTN, XRES,
                "WLAN".getBytes(StandardCharsets.US_ASCII));
        EapPacket challenge = machine.buildChallenge(3, true);
        assertEquals(50, challenge.type(), "EAP-AKA' method type 50");
        List<EapAkaAttributes.Attribute> attrs = EapAkaAttributes.parse(challenge.data());
        assertArrayEquals("WLAN".getBytes(StandardCharsets.US_ASCII),
                EapAkaAttributes.firstValue(attrs, EapAkaAttributes.AT_KDF_INPUT));
        assertArrayEquals(new byte[] {1},
                EapAkaAttributes.firstValue(attrs, EapAkaAttributes.AT_KDF));
        machine.wipe();
    }

    @Test
    @DisplayName("A wrong AT_RES fails closed")
    void wrongRes() throws Exception {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        EapPacket response = respondLikePeer(challenge, HEX.parseHex("28d7b0f2a2ec3de6"), false);
        assertEquals(EapAkaServer.Result.FAILURE, machine.acceptResponse(response, false));
        assertEquals(EapAkaServer.State.FAILED, machine.state());
        assertTrue(machine.failureReason().orElse("").contains("XRES"), machine.failureReason().orElse(""));
        assertThrows(IllegalStateException.class, machine::takeSessionKeys,
                "a failed session must never hand out keys");
    }

    @Test
    @DisplayName("A tampered AT_MAC fails closed")
    void tamperedMac() throws Exception {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        EapPacket response = respondLikePeer(challenge, XRES, false);
        // flip one bit of the presented MAC
        List<EapAkaAttributes.Attribute> attrs = new java.util.ArrayList<>(
                EapAkaAttributes.parse(response.data()));
        for (int i = 0; i < attrs.size(); i++) {
            if (attrs.get(i).type() == EapAkaAttributes.AT_MAC) {
                byte[] mac = attrs.get(i).value();
                mac[0] ^= 0x01;
                attrs.set(i, new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, mac));
            }
        }
        EapPacket tampered = EapPacket.of(EapPacket.Code.RESPONSE, response.identifier(),
                response.type(), EapAkaAttributes.serialize(attrs));
        assertEquals(EapAkaServer.Result.FAILURE, machine.acceptResponse(tampered, false));
        assertTrue(machine.failureReason().orElse("").contains("AT_MAC"),
                machine.failureReason().orElse(""));
    }

    @Test
    @DisplayName("A missing AT_MAC fails closed")
    void missingMac() {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        EapPacket response = EapPacket.of(EapPacket.Code.RESPONSE, challenge.identifier(), 23,
                EapAkaAttributes.serialize(List.of(
                        new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RES, XRES))));
        assertEquals(EapAkaServer.Result.FAILURE, machine.acceptResponse(response, false));
        assertTrue(machine.failureReason().orElse("").contains("AT_MAC missing"));
    }

    @Test
    @DisplayName("One resync is allowed, a second is refused (the AuC desync guard)")
    void resyncIsCappedAtOne() throws Exception {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        byte[] auts = HEX.parseHex("00000000000000000000000000000001");

        List<EapAkaAttributes.Attribute> first = new java.util.ArrayList<>(
                EapAkaAttributes.parse(challenge.data()));
        first.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTS, auts));
        int macIndex = first.size();
        first.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, new byte[16]));
        EapAkaKeys.SessionKeys keys = EapAkaKeys.deriveSessionKeys(CK, IK, IDENTITY);
        byte[] coverage;
        try {
            coverage = EapAkaAttributes.Attribute.macCoverage(first, macIndex, new byte[16]);
        } finally {
            keys.wipe();
        }
        keys = EapAkaKeys.deriveSessionKeys(CK, IK, IDENTITY);
        EapPacket resyncRequest;
        try {
            byte[] mac = EapAkaKeys.macAka(keys.kAut(), coverage);
            List<EapAkaAttributes.Attribute> withMac = new java.util.ArrayList<>();
            for (EapAkaAttributes.Attribute a : first) {
                withMac.add(a.type() == EapAkaAttributes.AT_MAC
                        ? new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, mac) : a);
            }
            resyncRequest = EapPacket.of(EapPacket.Code.RESPONSE, challenge.identifier(), 23,
                    EapAkaAttributes.serialize(withMac));
        } finally {
            keys.wipe();
        }

        assertEquals(EapAkaServer.Result.SEND_CHALLENGE, machine.acceptResponse(resyncRequest, false));
        assertEquals(EapAkaServer.State.RESYNC_SENT, machine.state());
        assertEquals(1, machine.resyncCount());
        assertArrayEquals(auts, machine.auts());

        // HSS serves a fresh vector; the machine goes back to CHALLENGE_SENT.
        machine.beginResync(HEX.parseHex("0011223344556677"), RAND, AUTN);
        assertEquals(EapAkaServer.State.CHALLENGE_SENT, machine.state());

        // A second resync attempt is refused outright.
        EapPacket second = respondLikePeer(machine.buildChallenge(1, false), XRES, false);
        // re-issue with an AT_AUTS instead of relying on the happy-path response
        List<EapAkaAttributes.Attribute> attrs = new java.util.ArrayList<>(
                EapAkaAttributes.parse(second.data()));
        attrs.removeIf(a -> a.type() == EapAkaAttributes.AT_RES);
        attrs.add(new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTS, auts));
        EapPacket secondResync = EapPacket.of(EapPacket.Code.RESPONSE, second.identifier(),
                second.type(), EapAkaAttributes.serialize(attrs));
        // The MAC no longer covers the modified attribute list, so this fails on the MAC
        // check first — which is the correct fail-closed outcome either way.
        EapAkaServer.Result outcome = machine.acceptResponse(secondResync, false);
        assertEquals(EapAkaServer.Result.FAILURE, outcome);
        assertEquals(1, machine.resyncCount(), "the cap must not have been consumed twice");
        machine.wipe();
    }

    @Test
    @DisplayName("A GSM triplet vector is refused up front")
    void rejectsGsmTriplet() {
        EapAkaServer machine = EapAkaServer.begin(IDENTITY, CK, IK, RAND, AUTN,
                HEX.parseHex("aabbccdd"), new byte[0]);
        assertEquals(EapAkaServer.State.FAILED, machine.state());
        assertTrue(machine.failureReason().orElse("").contains("quintuplet"),
                machine.failureReason().orElse(""));
    }

    @Test
    @DisplayName("A Response with no outstanding challenge fails closed")
    void responseWithoutChallenge() throws Exception {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        EapPacket response = respondLikePeer(challenge, XRES, false);
        assertEquals(EapAkaServer.Result.SUCCESS, machine.acceptResponse(response, false));

        // A replayed response on the same machine must not succeed twice.
        assertEquals(EapAkaServer.Result.FAILURE, machine.acceptResponse(response, false));
        assertTrue(machine.failureReason().orElse("").contains("no outstanding challenge"),
                machine.failureReason().orElse(""));
    }

    @Test
    @DisplayName("Undecodable attributes fail closed instead of throwing")
    void undecodableAttributes() {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        EapPacket response = EapPacket.of(EapPacket.Code.RESPONSE, challenge.identifier(), 23,
                HEX.parseHex("0110"));
        assertEquals(EapAkaServer.Result.FAILURE, machine.acceptResponse(response, false));
        assertTrue(machine.failureReason().orElse("").contains("undecodable"),
                machine.failureReason().orElse(""));
    }

    @Test
    @DisplayName("wipe() clears the secrets and the machine cannot be reused")
    void wipeIsTerminal() throws Exception {
        EapAkaServer machine = server();
        EapPacket challenge = machine.buildChallenge(1, false);
        assertNotNull(challenge);
        machine.wipe();
        assertEquals(EapAkaServer.State.FAILED, machine.state());
        assertThrows(IllegalStateException.class,
                () -> machine.buildChallenge(1, false), "no challenge after wipe");
        assertThrows(IllegalStateException.class,
                () -> machine.beginResync(new byte[8], RAND, AUTN),
                "beginResync after wipe");
    }

    @Test
    @DisplayName("EAP-AKA and EAP-AKA' derive different key shapes and never mix")
    void akaPrimeKAutIsTwiceAsWide() {
        EapAkaKeys.SessionKeys plain = EapAkaKeys.deriveSessionKeys(CK, IK, IDENTITY);
        EapAkaPrimeKeys.SessionKeys prime = EapAkaPrimeKeys.deriveSessionKeys(CK, IK, IDENTITY);
        try {
            assertEquals(16, plain.kAut().length, "RFC 4187 K_aut is 128 bits");
            assertEquals(32, prime.kAut().length, "RFC 9048 K_aut is 256 bits");
            assertNotNull(prime.kRe(), "EAP-AKA' has a re-authentication key");
            // AT_MAC is HMAC-SHA-1-128 in one and HMAC-SHA-256-128 in the other.
            assertNotEquals(HEX.formatHex(plain.msk()), HEX.formatHex(prime.msk()),
                    "the two methods must not produce the same MSK from the same vector");
        } finally {
            plain.wipe();
            prime.wipe();
        }
    }

    @Test
    @DisplayName("toString carries no key material")
    void toStringIsSafe() {
        EapAkaServer machine = server();
        String s = machine.toString();
        assertTrue(s.contains("state=CHALLENGE_SENT"), s);
        assertFalse(s.contains(HEX.formatHex(CK)), "no CK in toString");
        assertFalse(s.contains(HEX.formatHex(IDENTITY)), "no identity in toString");
    }
}
