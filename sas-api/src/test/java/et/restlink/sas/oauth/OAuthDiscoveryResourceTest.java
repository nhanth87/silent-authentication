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

class OAuthDiscoveryResourceTest {

    @Test
    void discoveryEndpointsReturnSameMetadata() {
        OAuthDiscoveryResource resource = new OAuthDiscoveryResource();
        resource.metadata = metadata();

        Map<String, Object> openid = resource.openidConfiguration(null);
        Map<String, Object> oauth = resource.authorizationServerMetadata(null);

        assertEquals("https://sas.example.com", openid.get("issuer"));
        assertEquals(openid, oauth);
    }

    @Test
    void jwksEndpointReturnsKeySet() {
        OAuthJwksResource resource = new OAuthJwksResource();
        resource.metadata = metadata();

        assertEquals(Map.of("keys", List.of()), resource.jwks());
    }

    private static OAuthMetadataService metadata() {
        OAuthMetadataService service = new OAuthMetadataService();
        service.config = new FakeOAuthServerConfig();
        return service;
    }

    private static final class FakeOAuthServerConfig extends OAuthServerConfig {
        @Override
        public String issuer() {
            return "https://sas.example.com";
        }

        @Override
        public Optional<String> publicBaseUrl() {
            return Optional.of("https://sas.example.com");
        }

        @Override
        public Optional<String> jwksJson() {
            return Optional.empty();
        }
    }
}
