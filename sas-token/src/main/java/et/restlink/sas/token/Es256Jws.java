/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.util.List;
import java.util.Objects;

/**
 * ES256 compact JWS (RFC 7515, RFC 7518 §3.4). The signature is the raw
 * {@code R || S} pair JOSE mandates — never DER — which Nimbus handles.
 *
 * <p>Verification is algorithm-pinned: only {@code ES256} is accepted, whatever the
 * header claims, so an {@code alg:none} or {@code HS256}-with-the-public-key token
 * can never verify (RFC 8725 §3.1). An explicit {@code typ} is required and must
 * match (RFC 8725 §3.11), so an access token cannot be replayed as an ID token or an
 * operator token.</p>
 */
public final class Es256Jws {

    private Es256Jws() {
    }

    public static String sign(JWTClaimsSet claims, ECKey signingKey, JOSEObjectType typ) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(signingKey, "signingKey");
        if (!signingKey.isPrivate()) {
            throw new IllegalArgumentException("signing key has no private part");
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(signingKey.getKeyID())
                .type(typ)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new ECDSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("ES256 signing failed", e);
        }
        return jwt.serialize();
    }

    /**
     * Verify signature and type; return the claims or {@code null}. Claim checks
     * (exp, aud, iss) are the caller's, because each token kind has its own policy.
     */
    public static JWTClaimsSet verify(String compact, List<ECKey> trustedKeys,
                                      JOSEObjectType expectedTyp) {
        if (compact == null || compact.isBlank() || trustedKeys == null || trustedKeys.isEmpty()) {
            return null;
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compact.trim());
        } catch (ParseException e) {
            return null;
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            return null;
        }
        if (expectedTyp != null && !expectedTyp.equals(header.getType())) {
            return null;
        }
        if (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty()) {
            return null;
        }
        String kid = header.getKeyID();
        for (ECKey key : trustedKeys) {
            if (kid != null && key.getKeyID() != null && !kid.equals(key.getKeyID())) {
                continue;
            }
            try {
                if (jwt.verify(new ECDSAVerifier(key.toPublicJWK()))) {
                    return jwt.getJWTClaimsSet();
                }
            } catch (JOSEException | ParseException e) {
                // try the next key; a malformed token fails on every key
            }
        }
        return null;
    }

    /** Header {@code alg} of a compact JWS without verifying it, or {@code null}. */
    public static String peekAlgorithm(String compact) {
        try {
            return SignedJWT.parse(compact.trim()).getHeader().getAlgorithm().getName();
        } catch (ParseException | RuntimeException e) {
            return null;
        }
    }
}
