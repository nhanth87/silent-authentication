/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wire-format tests for {@link EapPacket} (RFC 3748) and {@link EapAkaAttributes}. */
class EapPacketTest {

    private static final HexFormat HEX = HexFormat.of();

    private static byte[] hex(String s) {
        return HEX.parseHex(s.replace(" ", ""));
    }

    // ---- RFC 3748 §4.2 legacy Nak: Code 2, Type 3, "one octet per Type" ----

    /**
     * RFC 3748 §4.2 specifies the legacy Nak Response precisely enough to build
     * byte-exactly: Code = 2, one octet Identifier, Length = 6 (header + Type +
     * one alternative), Type = 3, Type-Data = one octet per desired method Type.
     *
     * <pre>
     *   02 0a 0006 03 04
     *   ^^ ^^ ^^^^^ ^^ ^^
     *   |  |  |     |  +-- desired alternative: Type 4 (MD5-Challenge)
     *   |  |  |     +----- Type 3 (Nak)
     *   |  |  +----------- Length = 6
     *   |  +-------------- Identifier = 10
     *   +----------------- Code 2 (Response)
     * </pre>
     *
     * ⚠ The RFC also prints an <em>Expanded</em> Nak (Type 254) as an ASCII diagram
     * with Length = 28. That diagram is not byte-exact — its 5-octets-per-expanded-type
     * layout cannot reach 24 octets of Type-Data — so it is deliberately NOT used as a
     * known-answer here. Round-trip coverage for Type 254 is in
     * {@link #expandedTypeRoundTrips()}.
     */
    private static final byte[] RFC3748_LEGACY_NAK = hex("02 0a 0006 03 04");

    @Test
    @DisplayName("RFC 3748 legacy Nak decodes to the exact field values")
    void decodesLegacyNak() {
        EapPacket packet = EapPacket.parse(RFC3748_LEGACY_NAK, 0);
        assertEquals(EapPacket.Code.RESPONSE, packet.code());
        assertEquals(0x0A, packet.identifier());
        assertEquals(6, packet.length(), "RFC 3748 requires Length >= 6 here");
        assertEquals(3, packet.type(), "Type 3 = legacy Nak");
        assertArrayEquals(new byte[] {4}, packet.data(), "one octet per desired Type");
        assertArrayEquals(RFC3748_LEGACY_NAK, packet.encode(), "re-encode is byte-identical");
    }

    @Test
    @DisplayName("RFC 3748: the Nak alternative list may also be the zero octet")
    void legacyNakWithNoAlternative() {
        byte[] bytes = hex("02 0a 0006 03 00");
        EapPacket packet = EapPacket.parse(bytes, 0);
        assertArrayEquals(new byte[] {0}, packet.data(), "0 = no proposed alternative");
        assertArrayEquals(bytes, packet.encode());
    }

    @Test
    @DisplayName("Expanded Type 254 round-trips even though the RFC's diagram is not byte-exact")
    void expandedTypeRoundTrips() {
        byte[] typeData = hex("00000003 00000005 00001406");
        EapPacket packet = EapPacket.of(EapPacket.Code.RESPONSE, 0x0A, 254, typeData);
        assertEquals(4 + 1 + typeData.length, packet.length());
        assertEquals(packet, EapPacket.parse(packet.encode(), 0));
    }

    @Test
    @DisplayName("Success/Failure carry no method Type and are exactly 4 octets")
    void resultPackets() {
        EapPacket success = EapPacket.ofResult(EapPacket.Code.SUCCESS, 7);
        assertArrayEquals(hex("03070004"), success.encode());
        assertEquals(-1, success.type());

        EapPacket failure = EapPacket.ofResult(EapPacket.Code.FAILURE, 7);
        assertArrayEquals(hex("04070004"), failure.encode());
        assertEquals(failure, EapPacket.parse(failure.encode(), 0));
    }

    @Test
    @DisplayName("A Request with a method Type round-trips")
    void requestRoundTrip() {
        byte[] payload = hex("0110 534e454d");
        EapPacket packet = EapPacket.of(EapPacket.Code.REQUEST, 42, 23, payload);
        assertArrayEquals(hex("012a000b 17 0110 534e454d"), packet.encode());
        assertEquals(packet, EapPacket.parse(packet.encode(), 0));
    }

    @Test
    @DisplayName("Octets beyond the declared Length are ignored (RFC 3748 §4.1)")
    void ignoresTrailingOctets() {
        byte[] buffer = hex("012a000b 17 0110 534e454d ffff");
        EapPacket packet = EapPacket.parse(buffer, 0);
        assertEquals(11, packet.length());
        assertArrayEquals(hex("0110 534e454d"), packet.data());
    }

    @Test
    @DisplayName("Malformed headers fail closed")
    void malformedHeaders() {
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapPacket.parse(hex("012a"), 0), "truncated header");
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapPacket.parse(hex("012a0002"), 0), "Length below header size");
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapPacket.parse(hex("012a00ff 17 00"), 0), "Length past the buffer");
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapPacket.parse(hex("092a0006 17"), 0), "unknown Code");
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapPacket.parse(hex("012a0005 17"), 0), "Request too short for a Type");
    }

    @Test
    @DisplayName("A hostile random buffer must never produce a packet")
    void fuzzNeverThrowsAnythingButMalformed() {
        Random random = new Random(20260930L);
        for (int i = 0; i < 20_000; i++) {
            byte[] buffer = new byte[1 + random.nextInt(64)];
            random.nextBytes(buffer);
            try {
                EapPacket packet = EapPacket.parse(buffer, 0);
                // If it parsed, it must re-encode to the same declared length.
                assertEquals(packet.length(), packet.encode().length);
            } catch (EapPacket.MalformedPacketException expected) {
                // the only acceptable refusal
            }
        }
    }

    @Test
    @DisplayName("toString never dumps the payload")
    void toStringIsSafe() {
        EapPacket packet = EapPacket.of(EapPacket.Code.REQUEST, 1, 23,
                hex("0110 534e454d"));
        assertTrue(packet.toString().contains("data=<6 octets>"), packet.toString());
    }

    // ---- EAP-AKA attribute TLVs ----

    @Test
    @DisplayName("AT_RAND/AT_AUTN/AT_MAC round-trip through the TLV codec")
    void attributeRoundTrip() {
        List<EapAkaAttributes.Attribute> attributes = List.of(
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RAND, hex("81e92b6c0ee0e12ebceba8d92a99dfa5")),
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTN, hex("bb52e91c747ac3ab2a5c23d15ee351d5")),
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, hex("0842ea722ff6835bfa2032499fc3ec23")));
        byte[] wire = EapAkaAttributes.serialize(attributes);
        assertEquals(EapAkaAttributes.AT_RAND, wire[0] & 0xFF);
        assertEquals(16, wire[1] & 0xFF, "Length counts the Value only, not the 2-octet header");
        assertEquals(attributes, EapAkaAttributes.parse(wire));
    }

    @Test
    @DisplayName("AT_RAND is 16 octets and AT_AUTN is 16 — the AKA-Challenge minimum")
    void akaChallengeShape() {
        List<EapAkaAttributes.Attribute> attributes = List.of(
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RAND, new byte[16]),
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTN, new byte[16]));
        byte[] payload = EapAkaAttributes.serialize(attributes);
        assertEquals(2 + 16 + 2 + 16, payload.length);
        EapPacket challenge = EapPacket.of(EapPacket.Code.REQUEST, 3,
                23 /* EAP-AKA */, payload);
        assertEquals(5 + 36, challenge.length(), "4-octet header + Type + 36 octets of attributes");
    }

    @Test
    @DisplayName("Attribute types match the RFC / IANA assignments")
    void attributeTypeNumbers() {
        assertEquals(1, EapAkaAttributes.AT_RAND);
        assertEquals(2, EapAkaAttributes.AT_AUTN);
        assertEquals(3, EapAkaAttributes.AT_RES);
        assertEquals(4, EapAkaAttributes.AT_AUTS);
        assertEquals(11, EapAkaAttributes.AT_MAC);
        assertEquals(14, EapAkaAttributes.AT_IDENTITY);
        assertEquals(16, EapAkaAttributes.AT_ENCR_KEYS);
        assertEquals(23, EapAkaAttributes.AT_KDF_INPUT);
        assertEquals(24, EapAkaAttributes.AT_KDF);
    }

    @Test
    @DisplayName("Truncated or over-long attribute lengths fail closed")
    void malformedAttributes() {
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapAkaAttributes.parse(hex("0110 0011")), "Length runs past the end");
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapAkaAttributes.parse(hex("01")), "truncated attribute header");
        assertThrows(EapPacket.MalformedPacketException.class,
                () -> EapAkaAttributes.parse(hex("0100 00"), 0, false), "zero length when refused");
    }

    @Test
    @DisplayName("A hostile random attribute buffer never yields a silent misparse")
    void fuzzAttributes() {
        Random random = new Random(4242L);
        for (int i = 0; i < 20_000; i++) {
            byte[] buffer = new byte[random.nextInt(48)];
            random.nextBytes(buffer);
            try {
                List<EapAkaAttributes.Attribute> attributes = EapAkaAttributes.parse(buffer);
                assertArrayEquals(buffer, EapAkaAttributes.serialize(attributes),
                        "anything that parses must re-serialize identically");
            } catch (EapPacket.MalformedPacketException expected) {
                // acceptable
            }
        }
    }

    @Test
    @DisplayName("AT_MAC coverage excludes the MAC value and includes the attribute headers")
    void macCoverage() {
        List<EapAkaAttributes.Attribute> attributes = new ArrayList<>(List.of(
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_RAND, hex("0102")),
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_AUTN, hex("0304")),
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_MAC, new byte[16])));
        byte[] mac = hex("aabbccddeeff00112233445566778899");
        byte[] coverage = EapAkaAttributes.Attribute.macCoverage(attributes, 2, mac);
        assertArrayEquals(hex("01 02 0102 02 02 0304 0b 10" + "00".repeat(16)), coverage);
    }

    @Test
    @DisplayName("Lookup helpers behave")
    void lookups() {
        List<EapAkaAttributes.Attribute> attributes = List.of(
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_KDF, new byte[] {1}),
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_KDF, new byte[] {2}));
        assertArrayEquals(new byte[] {1},
                EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_KDF),
                "first occurrence wins");
        assertEquals(1, EapAkaAttributes.byType(attributes).size(),
                "byType keys by type, so the duplicate AT_KDF collapses");
        assertNull(EapAkaAttributes.firstValue(attributes, EapAkaAttributes.AT_RES));
        assertNotNull(EapAkaAttributes.first(attributes, EapAkaAttributes.AT_KDF));
    }

    @Test
    @DisplayName("AT_IDENTITY carries a UTF-8 / NAI-style opaque value without a charset claim")
    void identityAttribute() {
        byte[] nai = "0555444333222111@restlink.et".getBytes(StandardCharsets.UTF_8);
        byte[] wire = EapAkaAttributes.serialize(List.of(
                new EapAkaAttributes.Attribute(EapAkaAttributes.AT_IDENTITY, nai)));
        assertEquals(nai.length, wire[1] & 0xFF);
        assertArrayEquals(nai,
                EapAkaAttributes.firstValue(EapAkaAttributes.parse(wire), EapAkaAttributes.AT_IDENTITY));
    }
}
