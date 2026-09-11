/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public record JsonWebSignature(
        JsonNode header,
        JsonNode payload,
        String signingInput,
        byte[] signature) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonWebSignature parse(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw OAuthException.invalidRequest("missing JWT assertion");
        }
        String[] parts = jwt.trim().split("\\.");
        if (parts.length != 3) {
            throw OAuthException.invalidRequest("malformed JWT assertion");
        }
        try {
            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            return new JsonWebSignature(header, payload, parts[0] + "." + parts[1], signature);
        } catch (IOException | IllegalArgumentException e) {
            throw OAuthException.invalidRequest("malformed JWT assertion");
        }
    }

    public String algorithm() {
        return text(header, "alg");
    }

    public String keyId() {
        return text(header, "kid");
    }

    public String stringClaim(String name) {
        return text(payload, name);
    }

    public Long longClaim(String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.canConvertToLong()) {
            return null;
        }
        return value.asLong();
    }

    public List<String> stringListClaim(String name) {
        JsonNode value = payload.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isTextual()) {
            return List.of(value.asText());
        }
        if (!value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    public boolean verify(JsonWebKey key) {
        String alg = algorithm();
        if (alg == null || key == null || key.publicKey() == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(JsonWebKey.signatureAlgorithm(alg));
            verifier.initVerify(key.publicKey());
            verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }
}
