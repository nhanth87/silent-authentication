/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints the CAMARA user-bound access token for a consumed CIBA pending
 * binding: {@code b64url(payload-json).b64url(HMAC-SHA256(payload, secret))}
 * with {@code iss=sas-restlink}, {@code sub}/{@code phone_number} bound to the
 * resolved MSISDN, TTL 300 s (CAMARA single-use ceiling). Fail-closed: a blank
 * {@code sas.oauth.secret} makes issuance throw; introspection rejects
 * malformed/tampered/expired tokens with null.
 */
@ApplicationScoped
public class AccessTokenService {

    private static final Logger LOG = LogManager.getLogger(AccessTokenService.class);

    /** Token issuer claim (SAS Authorization Server). */
    public static final String ISSUER = "sas-restlink";

    /** CAMARA single-use ceiling for user-bound access tokens (seconds). */
    public static final long TOKEN_TTL_SECONDS = 300L;

    /** How long a consumed jti marker is kept for replay rejection. */
    static final long CONSUMED_TTL_SECONDS = 600L;

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final char PART_SEPARATOR = '.';
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Inject
    OAuthServerConfig config;

    /** jti → consumption deadline epoch sec (replay guard hook for /verify). */
    private final Map<String, Long> consumedJtis = new ConcurrentHashMap<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TokenPayload(
            String iss,
            String sub,
            @JsonProperty("phone_number") String phoneNumber,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("token_use") String tokenUse,
            String scope,
            String jti,
            Long iat,
            Long exp) {}

    public record Decoded(
            String msisdn,
            Set<String> scopes,
            String jti,
            long expiresEpochSec,
            String clientId,
            String tokenUse) {

        public Decoded(String msisdn, Set<String> scopes, String jti, long expiresEpochSec) {
            this(msisdn, scopes, jti, expiresEpochSec, null, null);
        }

        public boolean userBound() {
            return msisdn != null && !msisdn.isBlank();
        }
    }

    /**
     * Sign and return the access token bound to the pending binding.
     *
     * @throws IllegalStateException when {@code sas.oauth.secret} is blank
     *         (misconfiguration never downgrades to unsigned tokens)
     */
    public String issue(PendingBinding binding) {
        return issueUserToken(binding.msisdn(), binding.scopes(), binding.clientId());
    }

    public String issueUserToken(String msisdn, Set<String> scopes, String clientId) {
        if (msisdn == null || msisdn.isBlank()) {
            throw new IllegalStateException("user-bound access token requires an msisdn");
        }
        long nowSec = currentEpochSeconds();
        TokenPayload payload = new TokenPayload(
                issuer(),
                msisdn,
                msisdn,
                clientId,
                "user",
                String.join(" ", scopes == null ? Set.of() : scopes),
                randomJti(),
                nowSec,
                nowSec + TOKEN_TTL_SECONDS);
        String token = sign(payload);
        LOG.info("[SAS] user-bound access token issued for {} (ttl={}s)",
                AuthorizationRequestService.maskMsisdn(msisdn), TOKEN_TTL_SECONDS);
        return token;
    }

    public String issueClientToken(String clientId, Set<String> scopes) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("client-credentials access token requires client_id");
        }
        long nowSec = currentEpochSeconds();
        TokenPayload payload = new TokenPayload(
                issuer(),
                null,
                null,
                clientId,
                "client",
                String.join(" ", scopes == null ? Set.of() : scopes),
                randomJti(),
                nowSec,
                nowSec + TOKEN_TTL_SECONDS);
        String token = sign(payload);
        LOG.info("[SAS] 2-legged access token issued for client {} (ttl={}s)",
                clientId, TOKEN_TTL_SECONDS);
        return token;
    }

    private String sign(TokenPayload payload) {
        String signingSecret = config == null ? "" : config.secret();
        if (signingSecret.isBlank()) {
            LOG.error("sas.oauth.secret is blank — refusing to issue access tokens");
            throw new IllegalStateException(
                    "sas.oauth.secret is required to issue access tokens");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = MAPPER.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("access-token payload serialization failed", e);
        }
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        byte[] signature = hmacSha256(
                (header + PART_SEPARATOR + payloadB64).getBytes(StandardCharsets.UTF_8),
                signingSecret);
        if (signature == null) {
            throw new IllegalStateException("HMAC unavailable — cannot issue access token");
        }
        return header
                + PART_SEPARATOR
                + payloadB64
                + PART_SEPARATOR
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /**
     * Verify signature (constant-time) and expiry; null on ANY failure.
     * Single-use enforcement is separate: {@link #markConsumed}/{@link #isConsumed}.
     */
    public Decoded introspect(String token) {
        TokenPayload p = verifySignedPayload(token);
        if (p == null) {
            return null;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (nowSec >= p.exp()) {
            LOG.warn("Access-token introspect: token expired");
            return null;
        }
        boolean userBound = p.sub() != null && !p.sub().isBlank();
        boolean clientBound = p.clientId() != null && !p.clientId().isBlank();
        if (!userBound && !clientBound) {
            LOG.warn("Access-token introspect: signed payload has no subject or client binding");
            return null;
        }
        Set<String> scopes = new LinkedHashSet<>();
        if (p.scope() != null && !p.scope().isBlank()) {
            for (String s : p.scope().trim().split("\\s+")) {
                scopes.add(s);
            }
        }
        return new Decoded(userBound ? p.sub() : null, scopes, p.jti(), p.exp(),
                p.clientId(), p.tokenUse());
    }

    public String issuer() {
        return config == null ? ISSUER : config.issuer();
    }

    /** Registers a jti as consumed (single-use enforcement at /verify). */
    public void markConsumed(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        evictExpired(nowSec);
        consumedJtis.put(jti, nowSec + CONSUMED_TTL_SECONDS);
    }

    /** True when the jti was marked consumed and its marker has not aged out. */
    public boolean isConsumed(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Long deadline = consumedJtis.get(jti);
        if (deadline == null) {
            return false;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (nowSec >= deadline) {
            consumedJtis.remove(jti);
            return false;
        }
        return true;
    }

    // ---- helpers ----

    private TokenPayload verifySignedPayload(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String signingSecret = config == null ? "" : config.secret();
        if (signingSecret.isBlank()) {
            LOG.warn("sas.oauth.secret not configured — rejecting access token");
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            LOG.warn("Access token: malformed JWT (expected 3 parts)");
            return null;
        }
        byte[] payloadBytes;
        byte[] actualSig;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            actualSig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            LOG.warn("Access token: malformed base64url encoding");
            return null;
        }
        byte[] expectedSig = hmacSha256(
                (parts[0] + PART_SEPARATOR + parts[1]).getBytes(StandardCharsets.UTF_8),
                signingSecret);
        if (expectedSig == null || !MessageDigest.isEqual(expectedSig, actualSig)) {
            LOG.warn("Access token: invalid signature");
            return null;
        }
        try {
            TokenPayload p = MAPPER.readValue(payloadBytes, TokenPayload.class);
            if (p == null || p.exp() == null || p.jti() == null || p.jti().isBlank()) {
                LOG.warn("Access token: missing exp/jti claims");
                return null;
            }
            return p;
        } catch (IOException e) {
            LOG.warn("Access token: malformed payload JSON");
            return null;
        }
    }

    private void evictExpired(long nowSec) {
        consumedJtis.values().removeIf(deadline -> nowSec > deadline);
    }

    private static byte[] hmacSha256(byte[] payload, String signingSecret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            LOG.error("HMAC sign failed", e);
            return null;
        }
    }

    private static long currentEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String randomJti() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
