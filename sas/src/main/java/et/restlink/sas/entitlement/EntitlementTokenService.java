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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
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
 *       {@code POST /entitlement/exchange} to obtain a CAMARA-compatible
 *       access token for {@code /verify}.</li>
 * </ol>
 *
 * <p>CIBA integration: the token can be used as
 * {@code login_hint=operatortoken:<tk>} in a CIBA back-channel auth request.
 * JWT-Bearer: the token is exchanged for a standard OAuth2 access token.</p>
 *
 * <p>Fail-closed: expired, malformed, or unknown tokens are rejected.</p>
 */
@ApplicationScoped
public class EntitlementTokenService {

    private static final Logger LOG = LogManager.getLogger(EntitlementTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Token TTL — TS.43 recommends ≤ 300 s for temporary entitlement tokens. */
    public static final long TOKEN_TTL_MS = 300_000L;

    @Inject
    EntitlementConfig config;

    /** token → EntitlementRecord. Bounded by TTL eviction. */
    private final Map<String, EntitlementRecord> tokens = new ConcurrentHashMap<>();

    public record EntitlementRecord(
            String msisdn,
            String imsi,
            long issuedEpochMs,
            long expiresEpochMs,
            String eapMethod) {}

    /**
     * Issue a temporary entitlement token after successful EAP-AKA.
     * Called by the 3GPP AAA integration (or the SWx verifier on success).
     */
    public String issueToken(String msisdn, String imsi, String eapMethod) {
        long now = System.currentTimeMillis();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, new EntitlementRecord(msisdn, imsi, now, now + TOKEN_TTL_MS, eapMethod));
        evictExpired(now);
        LOG.info("Entitlement token issued for {} (eap={})", maskMsisdn(msisdn), eapMethod);
        return token;
    }

    /**
     * Exchange an entitlement token for the bound identity.
     * Returns null if the token is invalid/expired (fail-closed).
     */
    public EntitlementRecord exchange(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        EntitlementRecord r = tokens.get(token);
        if (r == null) {
            LOG.warn("Entitlement exchange: unknown token");
            return null;
        }
        long now = System.currentTimeMillis();
        if (now > r.expiresEpochMs()) {
            tokens.remove(token);
            LOG.warn("Entitlement exchange: token expired");
            return null;
        }
        // Single-use: consume the token on exchange.
        tokens.remove(token);
        LOG.info("Entitlement token exchanged for {} (eap={})", maskMsisdn(r.msisdn()), r.eapMethod());
        return r;
    }

    /** Validate a token without consuming it (for pre-checks). */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) return false;
        EntitlementRecord r = tokens.get(token);
        return r != null && System.currentTimeMillis() <= r.expiresEpochMs();
    }

    /** Sign a token payload for JWT-Bearer grant (HMAC-SHA256). */
    public String sign(String payload) {
        String secret = config.hmacSecret();
        if (secret == null || secret.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            LOG.error("HMAC sign failed", e);
            return "";
        }
    }

    public int activeTokenCount() {
        return tokens.size();
    }

    private void evictExpired(long nowMs) {
        tokens.entrySet().removeIf(e -> nowMs > e.getValue().expiresEpochMs());
    }

    private static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) return "***";
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
