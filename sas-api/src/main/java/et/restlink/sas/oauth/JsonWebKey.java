/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record JsonWebKey(
        String kid,
        String kty,
        String alg,
        String use,
        PublicKey publicKey) {

    public static final List<String> SUPPORTED_SIGNATURE_ALGORITHMS =
            List.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512");

    private static final Set<String> SIGNATURE_ALGORITHMS =
            Set.copyOf(SUPPORTED_SIGNATURE_ALGORITHMS);

    public static JsonWebKey from(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("JWK must be a JSON object");
        }
        String kty = text(node, "kty");
        String use = text(node, "use");
        if (kty == null) {
            throw new IllegalArgumentException("JWK is missing kty");
        }
        if (use != null && !"sig".equals(use)) {
            throw new IllegalArgumentException("JWK use is not sig");
        }
        PublicKey publicKey = switch (kty) {
            case "RSA" -> rsaKey(node);
            case "EC" -> ecKey(node);
            default -> throw new IllegalArgumentException("unsupported JWK kty: " + kty);
        };
        return new JsonWebKey(text(node, "kid"), kty, text(node, "alg"), use, publicKey);
    }

    public boolean supports(String headerAlg) {
        if (headerAlg == null || !SIGNATURE_ALGORITHMS.contains(headerAlg)) {
            return false;
        }
        if (alg != null && !alg.equalsIgnoreCase(headerAlg)) {
            return false;
        }
        return switch (headerAlg.toUpperCase(Locale.ROOT)) {
            case "RS256", "RS384", "RS512" -> "RSA".equals(kty);
            case "ES256", "ES384", "ES512" -> "EC".equals(kty);
            default -> false;
        };
    }

    static String signatureAlgorithm(String headerAlg) {
        return switch (headerAlg.toUpperCase(Locale.ROOT)) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            case "ES256" -> "SHA256withECDSA";
            case "ES384" -> "SHA384withECDSA";
            case "ES512" -> "SHA512withECDSA";
            default -> throw new IllegalArgumentException("unsupported JWS alg: " + headerAlg);
        };
    }

    private static PublicKey rsaKey(JsonNode node) {
        try {
            BigInteger modulus = unsignedInteger(node, "n");
            BigInteger exponent = unsignedInteger(node, "e");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid RSA JWK", e);
        }
    }

    private static PublicKey ecKey(JsonNode node) {
        String crv = text(node, "crv");
        if (crv == null) {
            throw new IllegalArgumentException("EC JWK is missing crv");
        }
        String curveName = switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> throw new IllegalArgumentException("unsupported EC curve: " + crv);
        };
        try {
            BigInteger x = unsignedInteger(node, "x");
            BigInteger y = unsignedInteger(node, "y");
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec(curveName));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            return KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid EC JWK", e);
        }
    }

    private static BigInteger unsignedInteger(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("JWK is missing " + field);
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(value));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }
}
