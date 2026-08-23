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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P1 OIDC / JWT token validator for the bank→SAS northbound surface.
 * Validates: signature (HMAC-SHA256), expiry, issuer, audience, scope.
 * Fail-closed: any validation failure rejects the request.
 *
 * <p>On success {@link #validateDetailed} also exposes the claims the SAS
 * needs for replay protection and per-endpoint authorization: the token key
 * ({@code jti}, or SHA-256 of the raw token when absent), the granted scopes
 * and the {@code amr} values.</p>
 */
@ApplicationScoped
public class TokenValidator {

    private static final Logger LOG = LogManager.getLogger(TokenValidator.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    /** CAMARA NV v2.1.0 scope for {@code POST /verify}. */
    public static final String SCOPE_NUMBER_VERIFICATION_VERIFY = "number-verification:verify";

    /** CAMARA NV v2.1.0 scope for {@code GET /retrieve-phone-number}. */
    public static final String SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ =
            "number-verification:device-phone-number:read";

    @Inject
    SasSecurityConfig config;

    /**
     * Validated bearer-token claims. {@code error == null} means accepted.
     * {@code tokenKey} is the stable replay key: the {@code jti} claim when
     * present, else the SHA-256 hex of the raw token (lab mode: SHA-256 of
     * the raw Authorization header).
     */
    public record DetailedAuth(String error, String tokenKey, Set<String> scopes,
                               List<String> amrValues) {

        public boolean ok() {
            return error == null;
        }

        public static DetailedAuth fail(String reason) {
            return new DetailedAuth(reason, null, Set.of(), List.of());
        }
    }

    /** Validate a Bearer token. Returns null on success, or a rejection reason. */
    public String validate(String authorizationHeader) {
        return validateDetailed(authorizationHeader).error();
    }

    /**
     * Validate a Bearer token and expose its replay/authorization claims.
     * Never returns null; check {@link DetailedAuth#ok()}.
     */
    public DetailedAuth validateDetailed(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return DetailedAuth.fail("missing Authorization header");
        }
        String token = extractBearer(authorizationHeader);
        if (token == null) {
            return DetailedAuth.fail("Authorization header is not a Bearer token");
        }
        if (!config.tokenValidationEnabled()) {
            // Pilot mode: presence-only (P0 behaviour); stable key from the
            // raw header so identical retries stay idempotent.
            return new DetailedAuth(null, sha256Hex(authorizationHeader),
                    Set.of(), List.of());
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

    /**
     * Resolve the effective amr and enforce mobile-network authentication.
     * The signed JWT {@code amr} claim is preferred over the client-supplied
     * {@code X-Sas-Amr} header; when neither source carries an amr the
     * request fails closed. Returns null when mobile-network auth is proven.
     */
    public String resolveAmrError(List<String> jwtAmrValues, String headerAmr) {
        if (jwtAmrValues != null && !jwtAmrValues.isEmpty()) {
            for (String value : jwtAmrValues) {
                if (validateAmr(value) == null) {
                    return null;
                }
            }
            return "token amr claim is not mobile-network auth (amr=" + jwtAmrValues + ")";
        }
        if (headerAmr != null && !headerAmr.isBlank()) {
            return validateAmr(headerAmr);
        }
        return "no amr evidence (neither token amr claim nor X-Sas-Amr header)";
    }

    /**
     * Family/prefix scope match. A granted scope satisfies {@code required}
     * when it equals it exactly (legacy {@code _} normalised to {@code :}),
     * is a wildcard ({@code *}, or {@code <family>:*}), or is the family root
     * (e.g. {@code number-verification}) of the required scope.
     */
    public static boolean hasScope(Set<String> grantedScopes, String required) {
        if (grantedScopes == null || grantedScopes.isEmpty() || required == null) {
            return false;
        }
        String req = required.trim();
        for (String raw : grantedScopes) {
            if (raw == null) {
                continue;
            }
            String s = raw.trim().replace('_', ':');
            if (s.equals(req) || s.equals("*")) {
                return true;
            }
            if (s.endsWith(":*") && req.startsWith(s.substring(0, s.length() - 1))) {
                return true;
            }
            // family root grants its sub-scopes ("number-verification",
            // "number-verification:device-phone-number", ...).
            int colon = s.indexOf(':');
            if (colon > 0) {
                if (req.startsWith(s.substring(0, colon) + ":")) {
                    return true;
                }
            } else if (req.startsWith(s + ":")) {
                return true;
            }
        }
        return false;
    }

    private DetailedAuth validateJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return DetailedAuth.fail("malformed JWT (expected 3 parts)");
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!verifySignature(signingInput, parts[2])) {
            return DetailedAuth.fail("invalid token signature");
        }
        String payloadJson;
        try {
            payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return DetailedAuth.fail("malformed JWT payload encoding");
        }
        Long exp = extractLongClaim(payloadJson, "exp");
        if (exp == null) {
            return DetailedAuth.fail("missing exp claim");
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (exp < nowSec) {
            return DetailedAuth.fail("token expired");
        }
        Long iat = extractLongClaim(payloadJson, "iat");
        if (iat != null && iat > nowSec + config.clockSkewSeconds()) {
            return DetailedAuth.fail("token issued in the future (clock skew)");
        }
        String iss = extractStringClaim(payloadJson, "iss");
        if (config.expectedIssuer() != null && !config.expectedIssuer().isBlank()) {
            if (iss == null || !config.expectedIssuer().equals(iss)) {
                return DetailedAuth.fail("unexpected issuer (iss=" + iss + ")");
            }
        }
        String aud = extractStringClaim(payloadJson, "aud");
        if (config.expectedAudience() != null && !config.expectedAudience().isBlank()) {
            if (aud == null || !config.expectedAudience().equals(aud)) {
                return DetailedAuth.fail("unexpected audience (aud=" + aud + ")");
            }
        }
        String scope = extractStringClaim(payloadJson, "scope");
        if (!config.requiredScopes().isEmpty()) {
            if (scope == null || scope.isBlank()) {
                return DetailedAuth.fail("missing scope claim");
            }
            Set<String> tokenScopes = Set.of(scope.split("\\s+"));
            for (String required : config.requiredScopes()) {
                if (!tokenScopes.contains(required)) {
                    return DetailedAuth.fail("missing required scope: " + required);
                }
            }
        }
        String jti = extractStringClaim(payloadJson, "jti");
        String tokenKey = (jti != null && !jti.isBlank())
                ? jti : sha256Hex(token);
        List<String> amrValues = extractArrayOrStringClaim(payloadJson, "amr");
        Set<String> scopes = (scope == null || scope.isBlank())
                ? Set.of() : Set.of(scope.split("\\s+"));
        return new DetailedAuth(null, tokenKey, scopes, amrValues);
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

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Extract a claim that is either a JSON string or an array of strings
     * (e.g. OIDC {@code amr}). Returns an empty list when absent/malformed.
     */
    private static List<String> extractArrayOrStringClaim(String json, String claim) {
        String key = "\"" + claim + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return List.of();
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return List.of();
        }
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (json.charAt(start) == '[') {
            int pos = start + 1;
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == ']') {
                    break;
                }
                if (c == '"') {
                    int end = json.indexOf('"', pos + 1);
                    if (end < 0) {
                        return List.of();
                    }
                    values.add(json.substring(pos + 1, end));
                    pos = end + 1;
                    continue;
                }
                pos++;
            }
            return List.copyOf(values);
        }
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) {
                return List.of();
            }
            return List.of(json.substring(start + 1, end));
        }
        return List.of();
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
