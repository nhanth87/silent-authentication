/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * P1 OIDC / JWT token validator for the bank→SAS northbound surface.
 * Validates: signature (HMAC-SHA256), expiry, issuer, audience, scope.
 * Fail-closed: any validation failure rejects the request.
 */
@ApplicationScoped
public class TokenValidator {

    private static final Logger LOG = LogManager.getLogger(TokenValidator.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    @Inject
    SasSecurityConfig config;

    /** Validate a Bearer token. Returns null on success, or a rejection reason. */
    public String validate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return "missing Authorization header";
        }
        String token = extractBearer(authorizationHeader);
        if (token == null) {
            return "Authorization header is not a Bearer token";
        }
        if (!config.tokenValidationEnabled()) {
            return null; // Pilot mode: presence-only (P0 behaviour).
        }
        return validateJwt(token);
    }

    /** Validate the amr claim — must indicate mobile-network auth. */
    public String validateAmr(String amr) {
        if (amr == null || amr.isBlank()) {
            return null;
        }
        String a = amr.toLowerCase(Locale.ROOT);
        if (a.contains("mobile") || a.equals("mno") || a.equals("cellular")) {
            return null;
        }
        return "amr is not mobile-network auth (amr=" + amr + ")";
    }

    private String validateJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return "malformed JWT (expected 3 parts)";
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!verifySignature(signingInput, parts[2])) {
            return "invalid token signature";
        }
        String payloadJson;
        try {
            payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "malformed JWT payload encoding";
        }
        Long exp = extractLongClaim(payloadJson, "exp");
        if (exp == null) {
            return "missing exp claim";
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (exp < nowSec) {
            return "token expired";
        }
        Long iat = extractLongClaim(payloadJson, "iat");
        if (iat != null && iat > nowSec + config.clockSkewSeconds()) {
            return "token issued in the future (clock skew)";
        }
        String iss = extractStringClaim(payloadJson, "iss");
        if (config.expectedIssuer() != null && !config.expectedIssuer().isBlank()) {
            if (iss == null || !config.expectedIssuer().equals(iss)) {
                return "unexpected issuer (iss=" + iss + ")";
            }
        }
        String aud = extractStringClaim(payloadJson, "aud");
        if (config.expectedAudience() != null && !config.expectedAudience().isBlank()) {
            if (aud == null || !config.expectedAudience().equals(aud)) {
                return "unexpected audience (aud=" + aud + ")";
            }
        }
        String scope = extractStringClaim(payloadJson, "scope");
        if (!config.requiredScopes().isEmpty()) {
            if (scope == null || scope.isBlank()) {
                return "missing scope claim";
            }
            Set<String> tokenScopes = Set.of(scope.split("\\s+"));
            for (String required : config.requiredScopes()) {
                if (!tokenScopes.contains(required)) {
                    return "missing required scope: " + required;
                }
            }
        }
        return null;
    }

    private boolean verifySignature(String signingInput, String signatureB64) {
        String secret = config.hmacSecret();
        if (secret == null || secret.isBlank()) {
            LOG.warn("sas.security.hmac-secret not configured — rejecting all tokens");
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actual;
            try {
                actual = Base64.getUrlDecoder().decode(signatureB64);
            } catch (IllegalArgumentException e) {
                return false;
            }
            return MessageDigest.isEqual(expected, actual);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            LOG.error("HMAC verification failed", e);
            return false;
        }
    }

    private static String extractBearer(String header) {
        String h = header.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return h.substring(7).trim();
        }
        return null;
    }

    private static String extractStringClaim(String json, String claim) {
        String key = "\"" + claim + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static Long extractLongClaim(String json, String claim) {
        String key = "\"" + claim + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return null;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
