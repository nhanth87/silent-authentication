/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.token;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDHDecrypter;
import com.nimbusds.jose.crypto.ECDHEncrypter;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;

import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The operator token: what the entitlement server (sas-ecs, or an operator's own ECS)
 * hands a device after a successful EAP-AKA, and what the CAMARA server (sas-camara)
 * accepts as {@code operatortoken:<token>} in a CIBA {@code login_hint} or a JWT-bearer
 * {@code sub} (CAMARA ICM).
 *
 * <h2>Wire format</h2>
 * <pre>
 *   JWE( alg=ECDH-ES, enc=A256GCM, cty=JWT, kid=&lt;camara enc key&gt; )      RFC 7516/7518
 *     └─ JWS( alg=ES256, typ=operator-token+jwt, kid=&lt;ecs sig key&gt; )      RFC 7515
 *          { iss, aud, sub(opaque), phone_number, eap_method, iat, exp, jti }
 * </pre>
 *
 * <p>Nested encryption is deliberate: the token passes through the handset and the
 * bank app, and the invariant "MSISDN never reaches the app" would be broken by a
 * base64 payload anyone can read. The signature proves who issued it; the
 * encryption limits who can read it to the CAMARA server that owns the decryption
 * key. {@code sub} is random and carries no identity.</p>
 *
 * <h2>Verification</h2>
 * Issuer allow-list with per-issuer keys (JWKS), audience match, ES256 only,
 * explicit {@code typ}, {@code exp}/{@code iat} with skew, lifetime ≤ 300 s, a
 * {@code jti}, and an {@code eap_method} from the EAP-AKA family. Single-use is
 * NOT decided here: the consumer (sas-camara) keeps the durable jti ledger.
 */
public final class OperatorToken {

    public static final JOSEObjectType TYPE = new JOSEObjectType("operator-token+jwt");

    /** CAMARA single-use ceiling (seconds). */
    public static final long MAX_LIFETIME_SECONDS = 300L;

    public static final String CLAIM_PHONE_NUMBER = "phone_number";
    public static final String CLAIM_EAP_METHOD = "eap_method";

    public static final String EAP_AKA = "EAP-AKA";
    public static final String EAP_AKA_PRIME = "EAP-AKA'";
    private static final Set<String> EAP_METHODS = Set.of(EAP_AKA, EAP_AKA_PRIME);

    private static final SecureRandom RANDOM = new SecureRandom();

    private OperatorToken() {
    }

    /** Verified content of an operator token. */
    public record Claims(String issuer, String audience, String subject, String phoneNumber,
                         String eapMethod, String jwtId, long issuedAt, long expiresAt) {

        @Override
        public String toString() {
            // Never print the number: this record reaches log statements.
            return "OperatorToken.Claims[iss=" + issuer + ", jti=" + jwtId
                    + ", method=" + eapMethod + ", exp=" + expiresAt + "]";
        }
    }

    /** Why a token was refused. Messages are safe to log (no token material). */
    public static final class RejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RejectedException(String reason) {
            super(reason);
        }
    }

    // ---------------------------------------------------------------- issuing

    /**
     * Issue a token.
     *
     * @param signingKey   issuer's private P-256 signing key
     * @param recipientKey CAMARA's public P-256 encryption key; {@code null} emits a
     *                     bare JWS (lab / interop only — prod verifiers refuse it)
     */
    public static String issue(String issuer, String audience, String phoneNumber,
                               String eapMethod, long ttlSeconds, ECKey signingKey,
                               ECKey recipientKey, Clock clock) {
        requireText(issuer, "issuer");
        requireText(audience, "audience");
        requireText(phoneNumber, "phoneNumber");
        if (!EAP_METHODS.contains(eapMethod)) {
            throw new IllegalArgumentException("eap_method must be EAP-AKA or EAP-AKA'");
        }
        if (ttlSeconds <= 0 || ttlSeconds > MAX_LIFETIME_SECONDS) {
            throw new IllegalArgumentException("ttl must be 1.." + MAX_LIFETIME_SECONDS + " s");
        }
        long now = clock.millis() / 1000L;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(random())
                .claim(CLAIM_PHONE_NUMBER, phoneNumber)
                .claim(CLAIM_EAP_METHOD, eapMethod)
                .issueTime(new Date(now * 1000L))
                .expirationTime(new Date((now + ttlSeconds) * 1000L))
                .jwtID(random())
                .build();
        String jws = Es256Jws.sign(claims, signingKey, TYPE);
        if (recipientKey == null) {
            return jws;
        }
        JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.ECDH_ES, EncryptionMethod.A256GCM)
                .contentType("JWT")
                .keyID(recipientKey.getKeyID())
                .build();
        JWEObject jwe = new JWEObject(header, new Payload(jws));
        try {
            jwe.encrypt(new ECDHEncrypter(recipientKey.toPublicJWK()));
        } catch (JOSEException e) {
            throw new IllegalStateException("operator-token encryption failed", e);
        }
        return jwe.serialize();
    }

    // ------------------------------------------------------------ verification

    /**
     * Verifier policy.
     *
     * @param trustedIssuers     issuer → its public signing keys
     * @param expectedAudience   this CAMARA server's identifier
     * @param decryptionKeys     this CAMARA server's private encryption keys
     * @param requireEncryption  refuse a bare JWS (prod: true)
     * @param clockSkewSeconds   tolerated clock difference
     */
    public record Policy(Map<String, List<ECKey>> trustedIssuers, String expectedAudience,
                         List<ECKey> decryptionKeys, boolean requireEncryption,
                         long clockSkewSeconds) {

        public Policy {
            trustedIssuers = Map.copyOf(Objects.requireNonNull(trustedIssuers, "trustedIssuers"));
            decryptionKeys = List.copyOf(decryptionKeys == null ? List.of() : decryptionKeys);
            requireText(expectedAudience, "expectedAudience");
        }
    }

    /** Verify a token; returns its claims or throws {@link RejectedException}. */
    public static Claims verify(String token, Policy policy, Clock clock) {
        if (token == null || token.isBlank()) {
            throw new RejectedException("empty operator token");
        }
        String compact = token.trim();
        int parts = compact.split("\\.", -1).length;
        String jws;
        if (parts == 5) {
            jws = decrypt(compact, policy.decryptionKeys());
        } else if (parts == 3) {
            if (policy.requireEncryption()) {
                throw new RejectedException("operator token must be encrypted (JWE)");
            }
            jws = compact;
        } else {
            throw new RejectedException("operator token is neither a JWS nor a JWE");
        }

        String issuer = peekIssuer(jws);
        List<ECKey> keys = issuer == null ? null : policy.trustedIssuers().get(issuer);
        if (keys == null || keys.isEmpty()) {
            throw new RejectedException("operator token issuer is not trusted");
        }
        JWTClaimsSet claims = Es256Jws.verify(jws, keys, TYPE);
        if (claims == null) {
            throw new RejectedException("operator token signature/type does not verify");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new RejectedException("operator token issuer changed after verification");
        }
        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(policy.expectedAudience())) {
            throw new RejectedException("operator token audience mismatch");
        }
        Date iat = claims.getIssueTime();
        Date exp = claims.getExpirationTime();
        if (iat == null || exp == null) {
            throw new RejectedException("operator token lacks iat/exp");
        }
        long now = clock.millis() / 1000L;
        long iatSec = iat.getTime() / 1000L;
        long expSec = exp.getTime() / 1000L;
        if (iatSec > now + policy.clockSkewSeconds()) {
            throw new RejectedException("operator token issued in the future");
        }
        if (expSec + policy.clockSkewSeconds() <= now) {
            throw new RejectedException("operator token expired");
        }
        if (expSec - iatSec > MAX_LIFETIME_SECONDS) {
            throw new RejectedException("operator token lifetime exceeds " + MAX_LIFETIME_SECONDS + " s");
        }
        String jti = claims.getJWTID();
        if (jti == null || jti.isBlank()) {
            throw new RejectedException("operator token lacks jti");
        }
        String phone;
        String method;
        try {
            phone = claims.getStringClaim(CLAIM_PHONE_NUMBER);
            method = claims.getStringClaim(CLAIM_EAP_METHOD);
        } catch (ParseException e) {
            throw new RejectedException("operator token claims are malformed");
        }
        if (phone == null || phone.isBlank()) {
            throw new RejectedException("operator token lacks phone_number");
        }
        if (!EAP_METHODS.contains(method)) {
            throw new RejectedException("operator token eap_method is not EAP-AKA family");
        }
        return new Claims(issuer, policy.expectedAudience(), claims.getSubject(), phone,
                method, jti, iatSec, expSec);
    }

    private static String decrypt(String compact, List<ECKey> keys) {
        JWEObject jwe;
        try {
            jwe = JWEObject.parse(compact);
        } catch (ParseException e) {
            throw new RejectedException("operator token JWE is malformed");
        }
        JWEHeader header = jwe.getHeader();
        if (!JWEAlgorithm.ECDH_ES.equals(header.getAlgorithm())
                || !EncryptionMethod.A256GCM.equals(header.getEncryptionMethod())) {
            throw new RejectedException("operator token JWE must be ECDH-ES / A256GCM");
        }
        if (keys.isEmpty()) {
            throw new RejectedException("no operator-token decryption key configured");
        }
        String kid = header.getKeyID();
        for (ECKey key : keys) {
            if (kid != null && key.getKeyID() != null && !kid.equals(key.getKeyID())) {
                continue;
            }
            try {
                JWEObject attempt = JWEObject.parse(compact);
                attempt.decrypt(new ECDHDecrypter(key));
                return attempt.getPayload().toString();
            } catch (JOSEException | ParseException | RuntimeException e) {
                // next key
            }
        }
        throw new RejectedException("operator token cannot be decrypted with our keys");
    }

    private static String peekIssuer(String jws) {
        try {
            return com.nimbusds.jwt.SignedJWT.parse(jws).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new RejectedException("operator token JWS is malformed");
        }
    }

    private static String random() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
