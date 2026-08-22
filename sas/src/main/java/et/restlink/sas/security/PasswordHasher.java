/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import io.quarkus.elytron.security.common.BcryptUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PasswordHasher {
    public static final int DEFAULT_COST = 10;
    private static final int LEGACY_SHA256_HEX_LENGTH = 64;

    private PasswordHasher() {}

    public static String hash(String password) {
        return hash(password, DEFAULT_COST);
    }

    public static String hash(String password, int cost) {
        if (password == null) throw new IllegalArgumentException("password required");
        return BcryptUtil.bcryptHash(password, Math.clamp(cost, 4, 16));
    }

    public static boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) return false;
        if (isLegacySha256(storedHash)) {
            return MessageDigest.isEqual(
                    legacySha256Hex(password).getBytes(StandardCharsets.UTF_8),
                    storedHash.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        try {
            return BcryptUtil.matches(password, storedHash);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static boolean needsRehash(String storedHash) {
        return storedHash == null || storedHash.isBlank() || isLegacySha256(storedHash);
    }

    static boolean isLegacySha256(String storedHash) {
        String s = storedHash == null ? "" : storedHash.trim();
        if (s.length() != LEGACY_SHA256_HEX_LENGTH) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static String legacySha256Hex(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}