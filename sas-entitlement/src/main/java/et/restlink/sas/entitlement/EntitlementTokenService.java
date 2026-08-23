/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TS.43 Service Entitlement token exchange (P2 missing item #3).
 *
 * <p>Implements the entitlement-server side of GSMA TS.43 for the Wi-Fi
 * silent-auth path. The flow:</p>
 * <ol>
 *   <li>UE authenticates via EAP-AKA (SWm → 3GPP AAA → SWx → HSS).</li>
 *   <li>On success, the AAA issues a short-lived <em>entitlement token</em>
 *       bound to the authenticated IMSI/MSISDN.</li>
 *   <li>The bank backend presents this token to SAS via
 *       {@code POST /entitlement/exchange} to obtain the bound identity for
 *       the CAMARA {@code /verify} Wi-Fi path.</li>
 * </ol>
 *
 * <p><strong>Signed token format</strong> (when {@code sas.entitlement.hmac-secret}
 * is configured):
 * {@code base64url(payload-json) "." base64url(HMAC-SHA256(payload-bytes, secret))}.
 * Payload JSON fields: {@code msisdn}, {@code imsi}, {@code eapMethod}
 * (canonical: {@value EAP_AKA} or {@value EAP_AKA_PRIME} — anything else is
 * rejected at issue and at resolve), {@code iat} (epoch seconds),
 * {@code exp} (iat + ttl), {@code jti} (random id).</p>
 *
 * <p><strong>Single-use semantics</strong>: a server-side consumed-{@code jti}
 * set rejects any second exchange of the same token (replay).</p>
 *
 * <p>CIBA integration: the token can be used as
 * {@code login_hint=operatortoken:<tk>} in a CIBA back-channel auth request
 * (see {@code et.restlink.sas.security.OperatorTokenSupport}). JWT-Bearer: the
 * token is exchanged for a standard OAuth2 access token.</p>
 *
 * <p>Fail-closed: expired, malformed, tampered or replayed tokens are rejected;
 * with {@code sas.entitlement.require-signed=true} (default) a blank HMAC secret
 * makes {@link #issueToken} throw — misconfiguration never silently downgrades
 * to unsigned tokens.</p>
 */
@ApplicationScoped
public class EntitlementTokenService {

    private static final Logger LOG = LogManager.getLogger(EntitlementTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Separator between payload and signature parts of a signed token. */
    public static final char PART_SEPARATOR = '.';

    /** Canonical EAP method (TS 33.402 / RFC 4187). */
    public static final String EAP_AKA = "EAP-AKA";
    /** Canonical EAP-AKA-prime encoding — RFC 5448 spelling with ASCII apostrophe. */
    public static final String EAP_AKA_PRIME = "EAP-AKA'";

    /**
     * Max seconds an {@code iat} claim may lie in the future before the token
     * is rejected (clock-skew mirror of {@code TokenValidator}).
     */
    public static final long MAX_FUTURE_IAT_SKEW_SECONDS = 60L;

    /**
     * B3 whitelist normaliser: map an incoming EAP-method label to its
     * canonical payload encoding ({@link #EAP_AKA} or {@link #EAP_AKA_PRIME});
     * any other scheme returns {@code null} (reject).
     */
    public static String canonicalEapMethod(String raw) {
        if (raw == null) {
            return null;
        }
        String m = raw.trim().replace('_', '-').toUpperCase(Locale.ROOT);
        return switch (m) {
            case EAP_AKA -> EAP_AKA;
            case EAP_AKA_PRIME, "EAP-AKA-PRIME", "EAP-AKAPRIME" -> EAP_AKA_PRIME;
            default -> null;
        };
    }

    @Inject
    EntitlementConfig config;

    public record EntitlementRecord(
            String msisdn,
            String imsi,
            long issuedEpochMs,
            long expiresEpochMs,
            String eapMethod) {}

    /** Signed-token payload. Boxed Longs detect missing iat/exp at parse time. */
    private record TokenPayload(
            String msisdn,
            String imsi,
            String eapMethod,
            Long iat,
            Long exp,
            String jti) {}

    /** jti → exp epoch sec; issued and not yet consumed (for status/eviction). */
    private final Map<String, Long> issuedJtis = new ConcurrentHashMap<>();

    /** jti → exp epoch sec; consumed single-use tokens (replay guard). */
    private final Map<String, Long> consumedJtis = new ConcurrentHashMap<>();

    /** Legacy unsigned opaque tokens (lab only: require-signed=false, no secret). */
    private final Map<String, EntitlementRecord> unsignedTokens = new ConcurrentHashMap<>();

    /**
     * Issue a temporary entitlement token after successful EAP-AKA.
     * Called by the 3GPP AAA integration (or the SWx verifier on success).
     *
     * @throws IllegalArgumentException when {@code eapMethod} is outside the
     *         B3 whitelist (EAP-AKA / EAP-AKA-prime) — never mint a token
     *         anchored to an unverified authentication scheme
     * @throws IllegalStateException when the secret is blank while
     *         {@code sas.entitlement.require-signed=true} (fail-closed)
     */
    public String issueToken(String msisdn, String imsi, String eapMethod) {
        String canonicalEap = canonicalEapMethod(eapMethod);
        if (canonicalEap == null) {
            LOG.warn("Entitlement issue rejected: unsupported eapMethod={} "
                            + "(whitelist: {}, {})",
                    eapMethod, EAP_AKA, EAP_AKA_PRIME);
            throw new IllegalArgumentException(
                    "unsupported eapMethod (allowed: " + EAP_AKA + ", " + EAP_AKA_PRIME + ")");
        }
        String secret = config.hmacSecret();
        boolean requireSigned = config.requireSigned();
        if (secret == null || secret.isBlank()) {
            if (requireSigned) {
                LOG.error("sas.entitlement.hmac-secret is blank but require-signed=true — refusing to issue");
                throw new IllegalStateException(
                        "sas.entitlement.hmac-secret is required when sas.entitlement.require-signed=true");
            }
            return issueUnsignedToken(msisdn, imsi, canonicalEap);
        }
        return issueSignedToken(msisdn, imsi, canonicalEap, secret);
    }

    /**
     * Exchange an entitlement token for the bound identity (single-use).
     * Returns null if the token is invalid/expired/replayed (fail-closed).
     */
    public EntitlementRecord exchange(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        evictExpired(System.currentTimeMillis());
        int dot = token.indexOf(PART_SEPARATOR);
        if (dot > 0 && token.indexOf(PART_SEPARATOR, dot + 1) < 0) {
            return exchangeSigned(token);
        }
        return exchangeUnsigned(token);
    }

    /**
     * Validate a token without consuming it (for pre-checks). Signature,
     * expiry and not-yet-consumed are all checked.
     */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        evictExpired(System.currentTimeMillis());
        int dot = token.indexOf(PART_SEPARATOR);
        if (dot > 0 && token.indexOf(PART_SEPARATOR, dot + 1) < 0) {
            DecodedPayload p = verifySignedPayload(token);
            if (p == null) {
                return false;
            }
            long nowSec = System.currentTimeMillis() / 1000L;
            if (futureIat(p.payload(), nowSec)) {
                LOG.warn("Entitlement token: iat more than {}s in the future (clock skew)",
                        MAX_FUTURE_IAT_SKEW_SECONDS);
                return false;
            }
            return nowSec < p.payload().exp() && !consumedJtis.containsKey(p.payload().jti());
        }
        EntitlementRecord r = unsignedTokens.get(token);
        return r != null && System.currentTimeMillis() <= r.expiresEpochMs();
    }

    /** Number of live tokens: unconsumed signed jtis plus legacy unsigned. */
    public int activeTokenCount() {
        return issuedJtis.size() + unsignedTokens.size();
    }

    // ---- signed path ----

    private String issueSignedToken(String msisdn, String imsi, String eapMethod, String secret) {
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000L;
        long expSec = nowSec + config.tokenTtlSeconds();
        String jti = randomId();
        TokenPayload payload = new TokenPayload(msisdn, imsi, eapMethod, nowSec, expSec, jti);
        byte[] payloadBytes;
        try {
            payloadBytes = MAPPER.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("entitlement payload serialization failed", e);
        }
        byte[] signature = hmacSha256(payloadBytes, secret);
        if (signature == null) {
            throw new IllegalStateException("HMAC unavailable — cannot issue entitlement token");
        }
        issuedJtis.put(jti, expSec);
        evictExpired(nowMs);
        LOG.info("Signed entitlement token issued for {} (eap={}, ttl={}s)",
                maskMsisdn(msisdn), eapMethod, config.tokenTtlSeconds());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + PART_SEPARATOR
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private EntitlementRecord exchangeSigned(String token) {
        DecodedPayload decoded = verifySignedPayload(token);
        if (decoded == null) {
            return null;
        }
        TokenPayload p = decoded.payload();
        long nowSec = System.currentTimeMillis() / 1000L;
        // RFC 7519: current time MUST be strictly before exp.
        if (nowSec >= p.exp()) {
            LOG.warn("Entitlement exchange: token expired (jti evicted)");
            return null;
        }
        // B7 — future-dated iat beyond the skew window is a forged token;
        // never consumable even when the signature verifies.
        if (futureIat(p, nowSec)) {
            LOG.warn("Entitlement exchange: iat more than {}s in the future (clock skew)",
                    MAX_FUTURE_IAT_SKEW_SECONDS);
            return null;
        }
        if (p.msisdn() == null || p.msisdn().isBlank()) {
            LOG.warn("Entitlement exchange: signed payload has no bound msisdn");
            return null;
        }
        // Single-use: atomic consume-once on the jti.
        if (consumedJtis.putIfAbsent(p.jti(), p.exp()) != null) {
            LOG.warn("Entitlement exchange: replay detected (jti already used)");
            return null;
        }
        issuedJtis.remove(p.jti());
        long issuedSec = p.iat() != null ? p.iat() : nowSec;
        EntitlementRecord record = new EntitlementRecord(
                p.msisdn(), p.imsi(), issuedSec * 1000L, p.exp() * 1000L, p.eapMethod());
        LOG.info("Entitlement token exchanged for {} (eap={})",
                maskMsisdn(record.msisdn()), record.eapMethod());
        return record;
    }

    /** Constant-time signature verification + payload parse. Null = reject. */
    private DecodedPayload verifySignedPayload(String token) {
        String secret = config.hmacSecret();
        if (secret == null || secret.isBlank()) {
            LOG.warn("sas.entitlement.hmac-secret not configured — rejecting signed token");
            return null;
        }
        int dot = token.indexOf(PART_SEPARATOR);
        byte[] payloadBytes;
        byte[] actualSig;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(token.substring(0, dot));
            actualSig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            LOG.warn("Entitlement token: malformed base64url encoding");
            return null;
        }
        byte[] expectedSig = hmacSha256(payloadBytes, secret);
        if (expectedSig == null || !MessageDigest.isEqual(expectedSig, actualSig)) {
            LOG.warn("Entitlement token: invalid signature");
            return null;
        }
        try {
            TokenPayload p = MAPPER.readValue(payloadBytes, TokenPayload.class);
            if (p == null || p.exp() == null || p.jti() == null || p.jti().isBlank()) {
                LOG.warn("Entitlement token: missing exp/jti claims");
                return null;
            }
            return new DecodedPayload(p);
        } catch (IOException e) {
            LOG.warn("Entitlement token: malformed payload JSON");
            return null;
        }
    }

    private record DecodedPayload(TokenPayload payload) {}

    /** B7: true when iat is present and more than {@link #MAX_FUTURE_IAT_SKEW_SECONDS} ahead. */
    private static boolean futureIat(TokenPayload p, long nowSec) {
        return p.iat() != null && p.iat() > nowSec + MAX_FUTURE_IAT_SKEW_SECONDS;
    }

    /** HMAC-SHA256 over the raw bytes. Null on JCE failure (fail-closed). */
    private static byte[] hmacSha256(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            LOG.error("HMAC sign failed", e);
            return null;
        }
    }

    private static String randomId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // ---- legacy unsigned path (lab only) ----

    private String issueUnsignedToken(String msisdn, String imsi, String eapMethod) {
        long now = System.currentTimeMillis();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        unsignedTokens.put(token, new EntitlementRecord(
                msisdn, imsi, now, now + config.tokenTtlSeconds() * 1000L, eapMethod));
        evictExpired(now);
        LOG.warn("UNSIGNED entitlement token issued (require-signed=false) for {} (eap={})",
                maskMsisdn(msisdn), eapMethod);
        return token;
    }

    private EntitlementRecord exchangeUnsigned(String token) {
        EntitlementRecord r = unsignedTokens.get(token);
        if (r == null) {
            LOG.warn("Entitlement exchange: unknown token");
            return null;
        }
        long now = System.currentTimeMillis();
        if (now > r.expiresEpochMs()) {
            unsignedTokens.remove(token);
            LOG.warn("Entitlement exchange: token expired");
            return null;
        }
        // Single-use: consume the token on exchange.
        unsignedTokens.remove(token);
        LOG.info("Entitlement token exchanged for {} (eap={})", maskMsisdn(r.msisdn()), r.eapMethod());
        return r;
    }

    // ---- shared ----

    private void evictExpired(long nowMs) {
        long nowSec = nowMs / 1000L;
        issuedJtis.values().removeIf(expSec -> nowSec > expSec);
        consumedJtis.values().removeIf(expSec -> nowSec > expSec);
        unsignedTokens.values().removeIf(r -> nowMs > r.expiresEpochMs());
    }

    private static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) return "***";
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
