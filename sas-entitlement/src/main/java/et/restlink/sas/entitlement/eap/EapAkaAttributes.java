/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EAP-AKA / EAP-AKA' attribute codec (RFC 4187 §11, RFC 5216, RFC 9048 §3).
 * Pure, no I/O, no crypto.
 *
 * <p>The method payload of an EAP Request/Response is a flat sequence of
 * attributes:</p>
 * <pre>
 *  0                   1                   2                   3
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     Type      |    Length     |            Value...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>Per RFC 4187 §11 the Length counts the Value only — the Type and Length octets
 * are excluded. This is the single most common codec bug in EAP-AKA
 * implementations, so {@link #parse} is the authority and {@link #serialize} is
 * always exercised against it in the tests.</p>
 *
 * <p><strong>Attribute types.</strong> Verified against the IANA EAP registry
 * (method types 23 = EAP-AKA [RFC 4187], 50 = EAP-AKA' [RFC 9048]) and the attribute
 * number assignments in RFC 4187 §11, RFC 5216 and RFC 9048 §3:</p>
 *
 * <pre>
 * AT_RAND(1)  AT_AUTN(2)  AT_RES(3)  AT_AUTS(4)  AT_NONCE_SEND(5)
 * AT_NONCE_RECEIVE(6)  AT_SOURCE_IDENTIFIER(7)  AT_AUTHORIZATION_IDENTIFIER(8)
 * AT_AUTHENTICATOR_INFO(9)  AT_MAC(11)  AT_IDENTITY(14)  AT_ENCR_KEYS(16)
 * AT_SELECTED_CIPHER_SUITE(20)  AT_VENDOR_SPECIFIC(21)  AT_CLIENT_ERROR_CODE(22)
 * AT_KDF_INPUT(23)  AT_KDF(24)
 * </pre>
 *
 * <p>There is <strong>no</strong> {@code AT_ENCR_DATA(130)}: 130 is {@code NAS-Identifier}
 * [RFC 6696] in the IANA registry. Plain EAP-AKA encrypted key transport used
 * {@code AT_ENCR_DATA}, carried inside {@code AT_ENCR_KEYS}; EAP-AKA' over TLS needs
 * neither, which is why {@link #AT_ENCR_KEYS} is the only encryption-related type
 * modelled here.</p>
 */
public final class EapAkaAttributes {

    // ---- EAP-AKA attribute types (RFC 4187 §11 / RFC 5216) ----
    public static final int AT_RAND = 1;
    public static final int AT_AUTN = 2;
    public static final int AT_RES = 3;
    public static final int AT_AUTS = 4;
    public static final int AT_NONCE_SEND = 5;
    public static final int AT_NONCE_RECEIVE = 6;
    public static final int AT_SOURCE_IDENTIFIER = 7;
    public static final int AT_AUTHORIZATION_IDENTIFIER = 8;
    public static final int AT_AUTHENTICATOR_INFO = 9;
    public static final int AT_MAC = 11;
    public static final int AT_IDENTITY = 14;
    public static final int AT_ENCR_KEYS = 16;
    public static final int AT_SELECTED_CIPHER_SUITE = 20;
    public static final int AT_VENDOR_SPECIFIC = 21;
    public static final int AT_CLIENT_ERROR_CODE = 22;

    // ---- EAP-AKA' additions (RFC 9048 §3) ----
    public static final int AT_KDF_INPUT = 23;
    public static final int AT_KDF = 24;

    /** Type + Length octets preceding every attribute Value. */
    public static final int ATTRIBUTE_HEADER_LEN = 2;

    /** Largest attribute Value this codec will accept (16-bit Length field). */
    public static final int MAX_VALUE_LEN = 0xFFFF;

    private EapAkaAttributes() {
    }

    /** One decoded attribute. The Value is copied on the way in and out. */
    public record Attribute(int type, byte[] value) {

        public Attribute {
            Objects.requireNonNull(value, "value");
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        /**
         * The octets an {@code AT_MAC} covers: every attribute before this one,
         * in wire order, Type and Length included (RFC 4187 §10.15 — the MAC is
         * computed over the message with the MAC's own Value zeroed out).
         */
        public static byte[] macCoverage(List<Attribute> attributes, int macIndex,
                                         byte[] macValue) {
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(macValue, "macValue");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < attributes.size(); i++) {
                byte[] value = (i == macIndex) ? new byte[macValue.length] : attributes.get(i).value();
                out.write(attributes.get(i).type());
                out.write(value.length);
                out.write(value, 0, value.length);
            }
            return out.toByteArray();
        }

        /** Values are sensitive in several attribute types; never print them. */
        @Override
        public String toString() {
            return "Attribute[type=" + type + ", value=<" + value.length + " octets>]";
        }

        // A record with an array component gets identity equality for that component,
        // which would make a parse/serialize round-trip compare unequal. Override it.
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof Attribute other
                    && type == other.type
                    && java.util.Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return 31 * type + java.util.Arrays.hashCode(value);
        }
    }

    /**
     * Decode a flat EAP-AKA attribute sequence.
     *
     * @throws EapPacket.MalformedPacketException on a truncated header, a zero
     *         length (a 2-octet attribute with no value is legal only for a few
     *         vendor types — see {@link #parse(byte[], int, boolean)}), or a
     *         Length that runs past the end of the buffer
     */
    public static List<Attribute> parse(byte[] payload) {
        return parse(payload, 0, true);
    }

    /**
     * @param allowZeroLength permit Length == 0 (used by a few vendor/private
     *        attributes); when false a zero Length is rejected outright
     */
    public static List<Attribute> parse(byte[] payload, int offset, boolean allowZeroLength) {
        Objects.requireNonNull(payload, "payload");
        if (offset < 0 || offset > payload.length) {
            throw new EapPacket.MalformedPacketException("offset outside the payload");
        }
        List<Attribute> out = new ArrayList<>();
        int at = offset;
        while (at < payload.length) {
            if (at + ATTRIBUTE_HEADER_LEN > payload.length) {
                throw new EapPacket.MalformedPacketException(
                        "truncated attribute header at offset " + at);
            }
            int type = payload[at] & 0xFF;
            int length = payload[at + 1] & 0xFF;
            if (length == 0 && !allowZeroLength) {
                throw new EapPacket.MalformedPacketException(
                        "attribute type " + type + " declares a zero-length value");
            }
            int valueAt = at + ATTRIBUTE_HEADER_LEN;
            if (valueAt + length > payload.length) {
                throw new EapPacket.MalformedPacketException(
                        "attribute type " + type + " declares " + length
                                + " value octets but only " + (payload.length - valueAt)
                                + " remain");
            }
            out.add(new Attribute(type, java.util.Arrays.copyOfRange(payload, valueAt, valueAt + length)));
            at = valueAt + length;
        }
        return List.copyOf(out);
    }

    /** Encode an attribute sequence. Inverse of {@link #parse(byte[])}. */
    public static byte[] serialize(List<Attribute> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Attribute attribute : attributes) {
            byte[] value = attribute.value();
            if (value.length > MAX_VALUE_LEN) {
                throw new IllegalArgumentException("attribute value too long");
            }
            out.write(attribute.type());
            out.write(value.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    /**
     * Index attributes by type, first occurrence wins. EAP-AKA allows a vendor to
     * send {@code AT_KDF} more than once (RFC 9048 §3.2 negotiates a list), so this is
     * only for the "one attribute of this type" lookups.
     */
    public static Map<Integer, Attribute> byType(List<Attribute> attributes) {
        Map<Integer, Attribute> out = new LinkedHashMap<>();
        for (Attribute attribute : attributes) {
            out.putIfAbsent(attribute.type(), attribute);
        }
        return out;
    }

    /** First attribute of the given type, or {@code null}. */
    public static Attribute first(List<Attribute> attributes, int type) {
        for (Attribute attribute : attributes) {
            if (attribute.type() == type) {
                return attribute;
            }
        }
        return null;
    }

    /** Value of the first attribute of the given type, or {@code null}. */
    public static byte[] firstValue(List<Attribute> attributes, int type) {
        Attribute attribute = first(attributes, type);
        return attribute == null ? null : attribute.value();
    }

    public static byte[] of(int type, byte[] value) {
        return new Attribute(type, value).value();
    }
}
