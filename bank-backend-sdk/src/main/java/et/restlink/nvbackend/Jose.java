/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * An ES256 (P-256) key and the three JWTs the backend signs with keys like it.
 *
 * <ul>
 *   <li>{@code private_key_jwt} client assertion (RFC 7523 §2.2): {@code iss = sub =
 *       client_id}, {@code aud} = the endpoint, unique {@code jti}, 60 s life.</li>
 *   <li>JWT bearer grant assertion (RFC 7523 §2.1, CAMARA ICM): the same, with
 *       {@code sub} = {@code operatortoken:…} or {@code tel:…} and a {@code scope}.</li>
 *   <li>DPoP proof (RFC 9449 §4.2): {@code typ=dpop+jwt}, public {@code jwk} in the
 *       header, {@code htm}, {@code htu}, {@code iat}, {@code jti}, and {@code ath}
 *       when an access token goes with it.</li>
 * </ul>
 * The private key never leaves this object; {@link #publicJwk()} is what the bank
 * registers with the operator (client) or sends in each proof (DPoP).
 */
public final class Jose {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair pair;
    private final String kid;

    private Jose(KeyPair pair, String kid) {
        this.pair = pair;
        this.kid = kid;
    }

    /** A fresh P-256 key (a DPoP key, or a lab client key). */
    public static Jose generate(String kid) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return new Jose(g.generateKeyPair(), kid);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 unavailable", e);
        }
    }

    /**
     * The client key from a private EC JWK ({@code kty=EC, crv=P-256, x, y, d}), as the
     * bank keeps it in its vault.
     */
    public static Jose fromPrivateJwk(String json) {
        Map<String, String> jwk = FlatJson.parse(json);
        if (!"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv"))) {
            throw new IllegalArgumentException("client key must be an EC P-256 JWK");
        }
        try {
            ECParameterSpec spec = ((ECPublicKey) generate(null).pair.getPublic()).getParams();
            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPublicKeySpec pub = new ECPublicKeySpec(new ECPoint(unsigned(jwk.get("x")), unsigned(jwk.get("y"))), spec);
            ECPrivateKeySpec priv = new ECPrivateKeySpec(unsigned(jwk.get("d")), spec);
            return new Jose(new KeyPair(kf.generatePublic(pub), kf.generatePrivate(priv)), jwk.get("kid"));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("client JWK is not a usable P-256 key");
        }
    }

    public String kid() {
        return kid;
    }

    /** Public JWK, members in RFC 7638 order (so it doubles as the thumbprint input). */
    public String publicJwk() {
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + B64.encodeToString(fixed(pub.getW().getAffineX()))
                + "\",\"y\":\"" + B64.encodeToString(fixed(pub.getW().getAffineY())) + "\"}";
    }

    /** The JWKS document to register for {@code private_key_jwt}. */
    public String publicJwks() {
        String jwk = publicJwk();
        String withMeta = jwk.substring(0, jwk.length() - 1)
                + (kid == null ? "" : ",\"kid\":\"" + FlatJson.escape(kid) + "\"")
                + ",\"use\":\"sig\",\"alg\":\"ES256\"}";
        return "{\"keys\":[" + withMeta + "]}";
    }

    /** Private JWK for the bank's vault. Never send it anywhere. */
    public String privateJwk() {
        ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();
        String jwk = publicJwk();
        return jwk.substring(0, jwk.length() - 1) + ",\"d\":\"" + B64.encodeToString(fixed(priv.getS())) + "\""
                + (kid == null ? "" : ",\"kid\":\"" + FlatJson.escape(kid) + "\"") + "}";
    }

    /** RFC 7638 thumbprint of the public key. */
    public String thumbprint() {
        return B64.encodeToString(sha256(publicJwk().getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code private_key_jwt} client assertion for {@code audience}. */
    public String clientAssertion(String clientId, String audience) {
        long now = now();
        return sign("{\"alg\":\"ES256\",\"typ\":\"JWT\"" + kidMember() + "}",
                "{\"iss\":\"" + FlatJson.escape(clientId) + "\",\"sub\":\"" + FlatJson.escape(clientId)
                        + "\",\"aud\":\"" + FlatJson.escape(audience) + "\",\"jti\":\"" + UUID.randomUUID()
                        + "\",\"iat\":" + now + ",\"exp\":" + (now + 60) + "}");
    }

    /** JWT bearer grant assertion naming the user ({@code operatortoken:…} or {@code tel:…}). */
    public String grantAssertion(String clientId, String audience, String subject, String scope) {
        long now = now();
        return sign("{\"alg\":\"ES256\",\"typ\":\"JWT\"" + kidMember() + "}",
                "{\"iss\":\"" + FlatJson.escape(clientId) + "\",\"sub\":\"" + FlatJson.escape(subject)
                        + "\",\"aud\":\"" + FlatJson.escape(audience) + "\",\"scope\":\"" + FlatJson.escape(scope)
                        + "\",\"jti\":\"" + UUID.randomUUID() + "\",\"iat\":" + now + ",\"exp\":" + (now + 60) + "}");
    }

    /** DPoP proof for one request; {@code accessToken} null at the token endpoint. */
    public String dpopProof(String method, String url, String accessToken) {
        String htu = url.replaceAll("[?#].*$", "");
        return sign("{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\",\"jwk\":" + publicJwk() + "}",
                "{\"jti\":\"" + UUID.randomUUID() + "\",\"htm\":\"" + method + "\",\"htu\":\""
                        + FlatJson.escape(htu) + "\",\"iat\":" + now()
                        + (accessToken == null ? "" : ",\"ath\":\""
                        + B64.encodeToString(sha256(accessToken.getBytes(StandardCharsets.US_ASCII))) + "\"")
                        + "}");
    }

    private String kidMember() {
        return kid == null ? "" : ",\"kid\":\"" + FlatJson.escape(kid) + "\"";
    }

    private String sign(String header, String claims) {
        String input = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(pair.getPrivate(), RANDOM);
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64.encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ES256 signing failed", e);
        }
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    static String s256(String verifier) {
        return B64.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int skip = raw.length > 32 ? raw.length - 32 : 0;
        System.arraycopy(raw, skip, out, 32 - (raw.length - skip), raw.length - skip);
        return out;
    }

    private static BigInteger unsigned(String b64) {
        if (b64 == null) {
            throw new IllegalArgumentException("JWK member missing");
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(b64));
    }
}
