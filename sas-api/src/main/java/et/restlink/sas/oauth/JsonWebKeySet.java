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
import java.util.ArrayList;
import java.util.List;

public record JsonWebKeySet(List<JsonWebKey> keys) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonWebKeySet parse(String json) {
        if (json == null || json.isBlank()) {
            return new JsonWebKeySet(List.of());
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode keys = root.get("keys");
            if (keys == null || !keys.isArray()) {
                throw new IllegalArgumentException("JWKS is missing keys array");
            }
            List<JsonWebKey> parsed = new ArrayList<>();
            for (JsonNode key : keys) {
                try {
                    parsed.add(JsonWebKey.from(key));
                } catch (RuntimeException ignored) {
                }
            }
            return new JsonWebKeySet(List.copyOf(parsed));
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid JWKS JSON", e);
        }
    }

    public List<JsonWebKey> candidates(String kid, String alg) {
        List<JsonWebKey> candidates = new ArrayList<>();
        for (JsonWebKey key : keys) {
            if (kid != null && key.kid() != null && !kid.equals(key.kid())) {
                continue;
            }
            if (key.supports(alg)) {
                candidates.add(key);
            }
        }
        return candidates;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }
}
