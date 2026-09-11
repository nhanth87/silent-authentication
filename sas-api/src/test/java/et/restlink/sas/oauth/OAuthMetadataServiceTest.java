/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OAuthMetadataServiceTest {

    @Test
    void metadataUsesIssuerAndSupportedCamaraGrants() {
        OAuthMetadataService service = service("https://sas.example.com", null, null);

        Map<String, Object> metadata = service.metadata(null);

        assertEquals("https://sas.example.com", metadata.get("issuer"));
        assertEquals("https://sas.example.com/token", metadata.get("token_endpoint"));
        assertEquals("https://sas.example.com/bc-authorize",
                metadata.get("backchannel_authentication_endpoint"));
        assertEquals("https://sas.example.com/jwks.json", metadata.get("jwks_uri"));
        assertEquals(List.of(
                TokenResource.CIBA_GRANT_TYPE,
                TokenResource.CLIENT_CREDENTIALS_GRANT_TYPE,
                TokenResource.JWT_BEARER_GRANT_TYPE), metadata.get("grant_types_supported"));
        assertEquals(List.of(OAuthClient.AUTH_METHOD_PRIVATE_KEY_JWT, "none"),
                metadata.get("token_endpoint_auth_methods_supported"));
        assertEquals(JsonWebKey.SUPPORTED_SIGNATURE_ALGORITHMS,
                metadata.get("token_endpoint_auth_signing_alg_values_supported"));
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) metadata.get("scopes_supported");
        assertTrue(scopes.contains(OAuthScopePolicy.SCOPE_OPENID));
        assertTrue(scopes.contains(OAuthScopePolicy.SCOPE_OFFLINE_ACCESS));
        assertTrue(scopes.contains(OAuthScopePolicy.PURPOSE_FRAUD_PREVENTION));
    }

    @Test
    void metadataHidesNoneClientAuthenticationWhenRequired() {
        OAuthMetadataService service = service("https://sas.example.com", null, null);
        ((FakeOAuthServerConfig) service.config).requireClientAuth = true;

        Map<String, Object> metadata = service.metadata(null);

        assertEquals(List.of(OAuthClient.AUTH_METHOD_PRIVATE_KEY_JWT),
                metadata.get("token_endpoint_auth_methods_supported"));
    }

    @Test
    void publicBaseUrlOverridesIssuerForEndpointConstruction() {
        OAuthMetadataService service = service("sas-restlink", "https://public.example.com/sas", null);

        Map<String, Object> metadata = service.metadata(null);

        assertEquals("sas-restlink", metadata.get("issuer"));
        assertEquals("https://public.example.com/sas/token", metadata.get("token_endpoint"));
    }

    @Test
    void jwksDefaultsToEmptyKeySet() {
        assertEquals(Map.of("keys", List.of()), service("sas-restlink", null, null).jwks());
    }

    @Test
    void jwksReturnsConfiguredKeys() {
        OAuthMetadataService service = service("sas-restlink", null,
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\"}]}");
        assertEquals(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k1"))), service.jwks());
    }

    @Test
    void invalidJwksConfigurationFailsClosed() {
        OAuthMetadataService service = service("sas-restlink", null, "{not-json");
        assertThrows(IllegalStateException.class, service::jwks);
    }

    private static OAuthMetadataService service(String issuer, String baseUrl, String jwksJson) {
        OAuthMetadataService service = new OAuthMetadataService();
        service.config = new FakeOAuthServerConfig(issuer, baseUrl, jwksJson);
        return service;
    }

    private static final class FakeOAuthServerConfig extends OAuthServerConfig {
        private final String issuer;
        private final String baseUrl;
        private final String jwksJson;
        private boolean requireClientAuth;

        private FakeOAuthServerConfig(String issuer, String baseUrl, String jwksJson) {
            this.issuer = issuer;
            this.baseUrl = baseUrl;
            this.jwksJson = jwksJson;
        }

        @Override
        public String issuer() {
            return issuer;
        }

        @Override
        public Optional<String> publicBaseUrl() {
            return Optional.ofNullable(baseUrl);
        }

        @Override
        public Optional<String> jwksJson() {
            return Optional.ofNullable(jwksJson);
        }

        @Override
        public boolean requireClientAuth() {
            return requireClientAuth;
        }
    }
}
