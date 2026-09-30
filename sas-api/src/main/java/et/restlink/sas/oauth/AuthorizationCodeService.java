/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Authorization codes for the OIDC Authorization Code flow (RFC 6749 §4.1) with
 * mandatory PKCE S256 (RFC 7636) — the flow CAMARA Number Verification requires,
 * because the network-based authentication only works when the <em>device</em>
 * reaches {@code /authorize} over its cellular bearer.
 *
 * <p>A code is 256 random bits, lives {@value #CODE_TTL_SECONDS} s, is bound to the
 * client, the exact {@code redirect_uri}, the PKCE challenge and the subscriber the
 * network resolved, and is consumed atomically: the second presentation of a code is
 * {@code invalid_grant} (RFC 6749 §4.1.2).</p>
 *
 * <p>Scope of this store: one process. Codes are short-lived and exchanged by the
 * same client moments after the redirect, so a sticky load balancer is enough for a
 * multi-instance deployment; a shared store is a documented follow-up.</p>
 */
@ApplicationScoped
public class AuthorizationCodeService {

    public static final long CODE_TTL_SECONDS = 60L;

    /** RFC 7636 §4.1: 43..128 chars of the unreserved set. */
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9\\-_]{43}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();

    /** Everything a code stands for. Never log it whole: it carries the MSISDN. */
    public record AuthorizationCode(String clientId, String redirectUri, Set<String> scopes,
                                    boolean openid, String msisdn, String codeChallenge,
                                    String nonce, long authTimeEpochSec, long expiresEpochSec,
                                    List<String> amr) {

        @Override
        public String toString() {
            return "AuthorizationCode[client=" + clientId + ", exp=" + expiresEpochSec + "]";
        }
    }

    /** True when {@code challenge} is a syntactically valid S256 code challenge. */
    public static boolean validChallenge(String challenge) {
        return challenge != null && CHALLENGE.matcher(challenge).matches();
    }

    /** Mint a code for an authenticated subscriber. */
    public String mint(String clientId, String redirectUri, Set<String> scopes, boolean openid,
                       String msisdn, String codeChallenge, String nonce, List<String> amr) {
        long now = System.currentTimeMillis() / 1000L;
        evictExpired(now);
        String code = random();
        codes.put(code, new AuthorizationCode(clientId, redirectUri, Set.copyOf(scopes), openid,
                msisdn, codeChallenge, nonce, now, now + CODE_TTL_SECONDS,
                amr == null ? List.of() : List.copyOf(amr)));
        return code;
    }

    /**
     * Remove and return the code. Empty when unknown, already used or expired —
     * callers answer {@code invalid_grant} in every case.
     */
    public Optional<AuthorizationCode> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        AuthorizationCode found = codes.remove(code.trim());
        long now = System.currentTimeMillis() / 1000L;
        if (found == null || now >= found.expiresEpochSec()) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /** RFC 7636 §4.6: BASE64URL(SHA256(ASCII(code_verifier))) == code_challenge. */
    public static boolean pkceMatches(String codeVerifier, String codeChallenge) {
        if (codeVerifier == null || codeChallenge == null || !VERIFIER.matcher(codeVerifier).matches()) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void evictExpired(long now) {
        codes.values().removeIf(c -> now >= c.expiresEpochSec());
    }

    private static String random() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
