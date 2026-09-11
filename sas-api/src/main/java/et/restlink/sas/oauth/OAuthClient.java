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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record OAuthClient(
        String clientId,
        String tokenEndpointAuthMethod,
        JsonWebKeySet jwks,
        Set<String> grantTypes,
        Set<String> redirectUris,
        Set<String> scopes,
        Set<String> purposes) {

    public static final String AUTH_METHOD_PRIVATE_KEY_JWT = "private_key_jwt";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static OAuthClient anonymous(String clientId) {
        return new OAuthClient(
                clientId,
                "none",
                new JsonWebKeySet(List.of()),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    public static OAuthClient from(JsonNode node) {
        String clientId = text(node, "client_id");
        if (clientId == null) {
            throw new IllegalArgumentException("client_id is required");
        }
        String authMethod = text(node, "token_endpoint_auth_method");
        JsonNode jwksNode = node.get("jwks");
        JsonWebKeySet jwks = jwksNode == null
                ? new JsonWebKeySet(List.of())
                : JsonWebKeySet.parse(jwksNode.toString());
        return new OAuthClient(
                clientId,
                authMethod == null ? AUTH_METHOD_PRIVATE_KEY_JWT : authMethod,
                jwks,
                stringSet(node.get("grant_types")),
                stringSet(node.get("redirect_uris")),
                stringSet(node.get("scopes")),
                stringSet(node.get("purposes")));
    }

    public static List<OAuthClient> parseRegistry(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode clients = root.isArray() ? root : root.get("clients");
            if (clients == null || !clients.isArray()) {
                throw new IllegalArgumentException("clients array is required");
            }
            List<OAuthClient> parsed = new ArrayList<>();
            for (JsonNode client : clients) {
                parsed.add(from(client));
            }
            return List.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid OAuth clients JSON", e);
        }
    }

    public boolean authenticated() {
        return AUTH_METHOD_PRIVATE_KEY_JWT.equals(tokenEndpointAuthMethod) && !jwks.isEmpty();
    }

    public void authorizeGrant(String grantType) {
        if (grantTypes.isEmpty()) {
            return;
        }
        if (!grantTypes.contains(grantType)) {
            throw OAuthException.unauthorizedClient(
                    "client is not allowed to use grant_type " + grantType);
        }
    }

    public void authorizeScope(String requestedScope, boolean purposeRequired) {
        if (scopes.isEmpty() && purposes.isEmpty() && !purposeRequired) {
            return;
        }
        authorizeScope(OAuthScopePolicy.parse(requestedScope), purposeRequired);
    }

    public void authorizeGrantedScopes(Set<String> grantedScopes) {
        if (scopes.isEmpty() && purposes.isEmpty()) {
            return;
        }
        for (String scope : grantedScopes == null ? Set.<String>of() : grantedScopes) {
            if (scope.startsWith(OAuthScopePolicy.PURPOSE_PREFIX)) {
                if (!purposes.isEmpty() && !purposes.contains(scope)) {
                    throw OAuthException.invalidScope("client is not allowed purpose: " + scope);
                }
            } else if (!scopes.isEmpty() && !scopes.contains(scope)) {
                throw OAuthException.invalidScope("client is not allowed scope: " + scope);
            }
        }
    }

    public void authorizeScope(OAuthScopePolicy.ScopeGrant grant, boolean purposeRequired) {
        if (purposeRequired && grant.purpose() == null) {
            throw OAuthException.invalidScope("a dpv purpose scope is required");
        }
        if (!scopes.isEmpty()) {
            for (String scope : grant.apiScopes()) {
                if (!scopes.contains(scope)) {
                    throw OAuthException.invalidScope("client is not allowed scope: " + scope);
                }
            }
        }
        if (!purposes.isEmpty() && grant.purpose() != null && !purposes.contains(grant.purpose())) {
            throw OAuthException.invalidScope("client is not allowed purpose: " + grant.purpose());
        }
    }

    private static Set<String> stringSet(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return Set.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }
}
