/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * Test helper: a client DPoP key and proofs signed with it.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

/** One client key (EC P-256 or RSA) and the DPoP proofs it signs. */
final class DpopKeys {

    final KeyPair pair;
    final String alg;
    final String jwk;

    private DpopKeys(KeyPair pair, String alg, String jwk) {
        this.pair = pair;
        this.alg = alg;
        this.jwk = jwk;
    }

    static DpopKeys ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        String jwk = "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + b64(fixed(pub.getW().getAffineX(), 32))
                + "\",\"y\":\"" + b64(fixed(pub.getW().getAffineY(), 32)) + "\"}";
        return new DpopKeys(pair, "ES256", jwk);
    }

    static DpopKeys rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair pair = g.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        String jwk = "{\"kty\":\"RSA\",\"n\":\"" + b64(unsigned(pub.getModulus()))
                + "\",\"e\":\"" + b64(unsigned(pub.getPublicExponent())) + "\"}";
        return new DpopKeys(pair, "RS256", jwk);
    }

    String proof(String htm, String htu, long iat, String accessToken) throws Exception {
        return proof("dpop+jwt", alg, jwk, htm, htu, iat, accessToken, UUID.randomUUID().toString());
    }

    String proof(String typ, String alg, String jwk, String htm, String htu, long iat,
                 String accessToken, String jti) throws Exception {
        String header = "{\"typ\":\"" + typ + "\",\"alg\":\"" + alg + "\",\"jwk\":" + jwk + "}";
        String claims = "{\"jti\":\"" + jti + "\",\"htm\":\"" + htm + "\",\"htu\":\"" + htu + "\",\"iat\":" + iat
                + (accessToken == null ? "" : ",\"ath\":\"" + DpopProofVerifier.accessTokenHash(accessToken) + "\"")
                + "}";
        String input = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("ES256".equals(this.alg)
                ? "SHA256withECDSAinP1363Format" : "SHA256withRSA");
        s.initSign(pair.getPrivate());
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(s.sign());
    }

    String thumbprint() throws Exception {
        return DpopProofVerifier.thumbprint(new com.fasterxml.jackson.databind.ObjectMapper().readTree(jwk),
                "ES256".equals(alg) ? "EC" : "RSA");
    }

    static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static String sha256b64(String s) throws Exception {
        return b64(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] fixed(BigInteger v, int len) {
        byte[] raw = unsigned(v);
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }
}
