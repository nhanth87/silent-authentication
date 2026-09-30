/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement.eap;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * RFC 3748 EAP packet codec. Pure, no I/O, no crypto.
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     Code      |  Identifier   |            Length             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |   Type    |   Type-Data ...
 * +-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>RFC 3748 §4.1: {@code Length} counts "the length, in octets, of the EAP packet
 * including the Code, Identifier, Length, and Data fields" and must be at least 5 (6
 * when a one-byte method Type is present). Octets outside the Length range are
 * ignored on reception; a Length larger than the buffer is a protocol error.</p>
 *
 * <p>Fail-closed decoding: every malformed input throws
 * {@link MalformedPacketException} rather than returning a half-parsed packet, because
 * this class sits directly on the untrusted `/ts43` request path.</p>
 */
public final class EapPacket {

    /** Header without the method Type: Code + Identifier + Length. */
    public static final int HEADER_LEN = 4;

    /** Smallest legal EAP packet: header + one-byte method Type. */
    public static final int MIN_PACKET_LEN = 6;

    /** RFC 3748 §4.2 — EAP Code values we accept. */
    public enum Code {
        REQUEST(1), RESPONSE(2), SUCCESS(3), FAILURE(4);

        private final int value;

        Code(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        static Code fromValue(int value) {
            for (Code c : values()) {
                if (c.value == value) {
                    return c;
                }
            }
            throw new MalformedPacketException("unknown EAP Code " + value);
        }
    }

    /** Raised for any input this codec refuses to guess about. */
    public static final class MalformedPacketException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        MalformedPacketException(String message) {
            super(message);
        }
    }

    private final Code code;
    private final int identifier;
    private final int type;
    private final byte[] data;

    private EapPacket(Code code, int identifier, int type, byte[] data) {
        this.code = Objects.requireNonNull(code, "code");
        this.identifier = identifier;
        this.type = type;
        this.data = Objects.requireNonNull(data, "data").clone();
    }

    /**
     * Build a Request or Response carrying a method Type and its payload.
     *
     * @param type       EAP method type (23 = EAP-AKA, 50 = EAP-AKA')
     * @param typeData   method payload; for EAP-AKA this is the attribute TLV sequence
     */
    public static EapPacket of(Code code, int identifier, int type, byte[] typeData) {
        if (identifier < 0 || identifier > 0xFF) {
            throw new IllegalArgumentException("Identifier must fit in one octet");
        }
        if (type < 0 || type > 0xFF) {
            throw new IllegalArgumentException("EAP Type must fit in one octet");
        }
        Objects.requireNonNull(typeData, "typeData");
        int length = lengthOf(typeData.length);
        if (length > 0xFFFF) {
            throw new IllegalArgumentException("EAP packet exceeds the 16-bit Length field");
        }
        return new EapPacket(code, identifier, type, typeData);
    }

    /**
     * Build a Success or Failure packet. RFC 3748 §4.2: these carry no method Type,
     * so the packet is exactly 4 octets (Length = 4).
     */
    public static EapPacket ofResult(Code code, int identifier) {
        if (code != Code.SUCCESS && code != Code.FAILURE) {
            throw new IllegalArgumentException("only Success/Failure take this form");
        }
        if (identifier < 0 || identifier > 0xFF) {
            throw new IllegalArgumentException("Identifier must fit in one octet");
        }
        return new EapPacket(code, identifier, -1, new byte[0]);
    }

    /**
     * Decode one EAP packet. Trailing octets beyond the declared Length are ignored
     * per RFC 3748 §4.1 — but a Length that runs past the buffer is rejected.
     *
     * @param buffer the receive buffer, starting at the EAP header
     * @param offset index of the first octet of the header
     * @return the parsed packet
     * @throws MalformedPacketException on any inconsistency
     */
    public static EapPacket parse(byte[] buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer");
        if (offset < 0 || offset + HEADER_LEN > buffer.length) {
            throw new MalformedPacketException("truncated EAP header");
        }
        Code code = Code.fromValue(buffer[offset] & 0xFF);
        int identifier = buffer[offset + 1] & 0xFF;
        int length = ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);

        if (length < HEADER_LEN) {
            throw new MalformedPacketException("EAP Length " + length + " is below the header size");
        }
        if (offset + length > buffer.length) {
            throw new MalformedPacketException(
                    "EAP Length " + length + " runs past the buffer (" + buffer.length + " octets)");
        }

        if (code == Code.SUCCESS || code == Code.FAILURE) {
            // No method Type on a result packet; anything beyond the header is ignored.
            return new EapPacket(code, identifier, -1, new byte[0]);
        }

        if (length < MIN_PACKET_LEN) {
            throw new MalformedPacketException(
                    "EAP Length " + length + " is too small for a method Type");
        }
        int type = buffer[offset + 4] & 0xFF;
        byte[] data = Arrays.copyOfRange(buffer, offset + 5, offset + length);
        return new EapPacket(code, identifier, type, data);
    }

    public Code code() {
        return code;
    }

    public int identifier() {
        return identifier;
    }

    /** Method type, or {@code -1} on a Success/Failure packet. */
    public int type() {
        return type;
    }

    /** Copy of the method payload. Callers get their own array. */
    public byte[] data() {
        return data.clone();
    }

    /**
     * Encoded length in octets, as it appears in the header: the 4-octet header,
     * plus the one-octet method Type, plus the payload. Note this is
     * {@code MIN_PACKET_LEN - 1 + data.length} — {@link #MIN_PACKET_LEN} is the
     * smallest <em>legal</em> packet (empty payload), not the per-packet base.
     */
    public int length() {
        return type < 0 ? HEADER_LEN : lengthOf(data.length);
    }

    private static int lengthOf(int dataLength) {
        return HEADER_LEN + 1 + dataLength;
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length());
        out.write(code.value());
        out.write(identifier);
        int length = length();
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        if (type >= 0) {
            out.write(type);
            out.write(data, 0, data.length);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EapPacket other)) {
            return false;
        }
        return code == other.code
                && identifier == other.identifier
                && type == other.type
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, identifier, type, Arrays.hashCode(data));
    }

    /** Payload is redacted: a hex dump would be one keystroke from a key disclosure. */
    @Override
    public String toString() {
        return "EapPacket[code=" + code + ", id=" + identifier
                + ", type=" + (type < 0 ? "n/a" : type)
                + ", data=<" + data.length + " octets>]";
    }
}
