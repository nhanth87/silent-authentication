/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Northbound request-input validation and replay-key derivation helpers
 * (CAMARA NV v2.1.0 body schema). Pure functions — unit-testable without a
 * container.
 */
public final class RequestValidator {

    /** E.164 phone number: {@code +} then 1–15 digits, first digit 1–9. */
    public static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{1,14}$");

    /** SHA-256 digest in hexadecimal representation (64 hex chars). */
    public static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private RequestValidator() {
    }

    /** CAMARA {@code phoneNumber} shape check (E.164, leading {@code +}). */
    public static boolean isE164(String phoneNumber) {
        return phoneNumber != null && E164.matcher(phoneNumber).matches();
    }

    /** CAMARA {@code hashedPhoneNumber} shape check (64 hex chars). */
    public static boolean isSha256Hex(String hashedPhoneNumber) {
        return hashedPhoneNumber != null && SHA256_HEX.matcher(hashedPhoneNumber).matches();
    }

    /**
     * Single normalization point for every MSISDN crossing the northbound
     * surface (F6): strips spaces, dashes, dots and parentheses, collapses
     * to exactly one leading {@code +}, then re-validates against the CAMARA
     * E.164 pattern. Empty when the input is null, blank or unnormalizable.
     */
    public static Optional<String> normalizeE164(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.replaceAll("[\\s.()\\-]", "");
        while (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }
        if (!cleaned.isEmpty()) {
            cleaned = "+" + cleaned;
        }
        return isE164(cleaned) ? Optional.of(cleaned) : Optional.empty();
    }

    /** Lowercase SHA-256 hex of a UTF-8 string (never fails on any JVM). */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Lab-mode (validation disabled) request key: SHA-256 of the raw token
     * key plus correlator, so retries of an identical request stay
     * idempotent instead of minting a fresh UUID per call.
     */
    public static String deriveLabReqId(String tokenKey, String correlator) {
        return sha256Hex(tokenKey + "|" + (correlator == null ? "" : correlator));
    }

    /**
     * Number-discovery request key — namespaced so a /verify call and a
     * /retrieve-phone-number call with the same token never share a reqId.
     */
    public static String deriveRetrieveReqId(String tokenKey, String correlator) {
        return sha256Hex("retrieve|" + tokenKey + "|" + (correlator == null ? "" : correlator));
    }
}
