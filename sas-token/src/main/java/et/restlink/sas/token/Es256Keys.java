/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * P-256 key handling for the SAS JOSE profile. Keys are configured as JWK JSON
 * (RFC 7517) because a private EC JWK carries its public half too — a PKCS#8 blob
 * would force re-deriving the public point by hand.
 *
 * <p>Every key gets a {@code kid}: the configured one, or the RFC 7638 thumbprint.</p>
 */
public final class Es256Keys {

    private Es256Keys() {
    }

    /**
     * Parse a private P-256 JWK for signing ({@code use=sig}) or decryption
     * ({@code use=enc}).
     *
     * @throws IllegalArgumentException when the JSON is not a private P-256 EC key
     */
    public static ECKey parsePrivate(String jwkJson, KeyUse expectedUse) {
        ECKey key = parseEc(jwkJson);
        if (!key.isPrivate()) {
            throw new IllegalArgumentException("JWK has no private part (d)");
        }
        return withKidAndUse(key, expectedUse);
    }

    /** Parse a public (or private — only the public half is kept) P-256 JWK. */
    public static ECKey parsePublic(String jwkJson, KeyUse expectedUse) {
        return withKidAndUse(parseEc(jwkJson), expectedUse).toPublicJWK();
    }

    /** Parse a JWKS document, keeping only P-256 EC keys usable for {@code use}. */
    public static List<ECKey> parseSet(String jwksJson, KeyUse use) {
        JWKSet set;
        try {
            set = JWKSet.parse(jwksJson);
        } catch (ParseException e) {
            throw new IllegalArgumentException("invalid JWKS JSON", e);
        }
        List<ECKey> out = new ArrayList<>();
        for (JWK jwk : set.getKeys()) {
            if (jwk instanceof ECKey ec && Curve.P_256.equals(ec.getCurve())
                    && (ec.getKeyUse() == null || use.equals(ec.getKeyUse()))) {
                out.add(withKidAndUse(ec, use));
            }
        }
        return List.copyOf(out);
    }

    /** Lab only: a fresh random key. Never persist or reuse across restarts in prod. */
    public static ECKey generate(KeyUse use) {
        try {
            ECKeyGenerator generator = new ECKeyGenerator(Curve.P_256);
            generator.keyUse(use);
            generator.keyIDFromThumbprint(true);
            generator.algorithm(KeyUse.SIGNATURE.equals(use) ? JWSAlgorithm.ES256 : JWEAlgorithm.ECDH_ES);
            return generator.generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("P-256 key generation unavailable", e);
        }
    }

    /** Public JWKS JSON for publication at a {@code jwks_uri}. */
    public static String publicJwks(List<ECKey> keys) {
        List<JWK> pub = new ArrayList<>();
        for (ECKey k : keys) {
            pub.add(k.toPublicJWK());
        }
        return new JWKSet(pub).toString(true);
    }

    private static ECKey parseEc(String jwkJson) {
        if (jwkJson == null || jwkJson.isBlank()) {
            throw new IllegalArgumentException("JWK JSON is empty");
        }
        JWK jwk;
        try {
            jwk = JWK.parse(jwkJson.trim());
        } catch (ParseException e) {
            throw new IllegalArgumentException("invalid JWK JSON", e);
        }
        if (!(jwk instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve())) {
            throw new IllegalArgumentException("only P-256 EC keys are accepted");
        }
        return ec;
    }

    private static ECKey withKidAndUse(ECKey key, KeyUse use) {
        if (key.getKeyUse() != null && use != null && !use.equals(key.getKeyUse())) {
            throw new IllegalArgumentException("JWK use is " + key.getKeyUse() + ", expected " + use);
        }
        try {
            String kid = key.getKeyID() != null ? key.getKeyID()
                    : key.computeThumbprint().toString();
            return new ECKey.Builder(key).keyID(kid).keyUse(use).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot compute JWK thumbprint", e);
        }
    }
}
