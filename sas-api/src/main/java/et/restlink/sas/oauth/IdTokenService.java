/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;

import et.restlink.sas.token.Es256Jws;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * OIDC ID tokens for the Authorization Code flow (OpenID Connect Core §2, §3.1.3.7).
 *
 * <ul>
 *   <li>ES256 only — an ID token is consumed by the client, which must be able to
 *       verify it from {@code /jwks.json} without a shared secret.</li>
 *   <li>{@code sub} is <strong>pairwise</strong> (Core §8.1):
 *       {@code base64url(HMAC-SHA256(secret, client_id "|" msisdn))}. Two clients
 *       cannot correlate a subscriber and the number never appears in the token.</li>
 *   <li>{@code nonce}, {@code auth_time} and {@code amr} are echoed from the
 *       authorization request; lifetime is the CAMARA 300 s ceiling.</li>
 * </ul>
 */
@ApplicationScoped
public class IdTokenService {

    private static final Logger LOG = LogManager.getLogger(IdTokenService.class);

    public static final long ID_TOKEN_TTL_SECONDS = 300L;

    @Inject
    OAuthServerConfig config;

    @Inject
    OAuthSigningKeys signingKeys;

    private volatile byte[] ephemeralPairwiseSecret;

    /** True when an ID token can be issued (ES256 key available). */
    public boolean available() {
        return signingKeys != null && signingKeys.es256() && signingKeys.signingKey().isPresent();
    }

    public String issue(String issuer, String msisdn, String clientId, String nonce,
                        long authTimeEpochSec, List<String> amr) {
        ECKey key = signingKeys == null ? null : signingKeys.signingKey().orElse(null);
        if (key == null || !signingKeys.es256()) {
            throw new IllegalStateException("ID tokens require an ES256 signing key");
        }
        long now = System.currentTimeMillis() / 1000L;
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(pairwiseSubject(clientId, msisdn))
                .audience(clientId)
                .issueTime(new Date(now * 1000L))
                .expirationTime(new Date((now + ID_TOKEN_TTL_SECONDS) * 1000L))
                .claim("auth_time", authTimeEpochSec);
        if (nonce != null && !nonce.isBlank()) {
            claims.claim("nonce", nonce);
        }
        if (amr != null && !amr.isEmpty()) {
            claims.claim("amr", List.copyOf(amr));
        }
        return Es256Jws.sign(claims.build(), key, JOSEObjectType.JWT);
    }

    /** Pairwise subject for {@code clientId}. Stable per client, unlinkable across clients. */
    public String pairwiseSubject(String clientId, String msisdn) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            byte[] out = mac.doFinal((clientId + "|" + msisdn).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private byte[] secret() {
        String configured = config == null ? null : config.pairwiseSecret().orElse(null);
        if (configured != null) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        if (config != null && config.allowEphemeralSigningKey()) {
            byte[] s = ephemeralPairwiseSecret;
            if (s == null) {
                synchronized (this) {
                    if (ephemeralPairwiseSecret == null) {
                        byte[] fresh = new byte[32];
                        new SecureRandom().nextBytes(fresh);
                        ephemeralPairwiseSecret = fresh;
                        LOG.warn("[SAS] LAB: ephemeral pairwise-subject secret — subjects change on restart");
                    }
                    s = ephemeralPairwiseSecret;
                }
            }
            return s;
        }
        throw new IllegalStateException("sas.oauth.pairwise-secret is required to issue ID tokens");
    }
}
