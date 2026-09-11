/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /bc-authorize} resource tests over fake services (no HTTP server):
 * form and JSON fallback happy paths, header tuple plumbing, client identity
 * plumbing and OAuth error mapping.
 */
class BcAuthorizeResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BcAuthorizeResource resource;
    private FakeAuthRequests authRequests;

    @BeforeEach
    void setUp() throws Exception {
        resource = new BcAuthorizeResource();
        authRequests = new FakeAuthRequests();
        inject("authRequests", authRequests);
    }

    @Test
    void formHappy_returns200WithAuthReqIdAndExpiresIn() throws Exception {
        authRequests.next = new AuthorizationRequestService.AuthRequest("abc22charb64urlid00", 120L);

        Response response = authorize(null, "number-verification:verify",
                headers("10.20.30.40", "55555"));

        assertEquals(200, response.getStatus());
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"auth_req_id\""));
        assertTrue(json.contains("\"expires_in\":120"));
        assertEquals("10.20.30.40", authRequests.lastSrcIp);
        assertEquals(55555, authRequests.lastSrcPort);
        assertNull(authRequests.lastClientId);
    }

    @Test
    void formClientIdIsBoundIntoAuthorizationRequest() throws Exception {
        authRequests.next = new AuthorizationRequestService.AuthRequest("abc22charb64urlid00", 120L);

        Response response = resource.authorize(null, "number-verification:verify",
                "client-1", null, null, headers(null, null), null);

        assertEquals(200, response.getStatus());
        assertEquals("client-1", authRequests.lastClientId);
    }

    @Test
    void jsonFallback_parsesLoginHintScopeAndClientId() throws Exception {
        authRequests.next = new AuthorizationRequestService.AuthRequest("abc22charb64urlid00", 120L);

        Response response = authorizeJson(
                "{\"login_hint\":\"operatortoken:tk\",\"scope\":\"number-verification:verify\","
                        + "\"client_id\":\"client-json\"}",
                headers(null, null));

        assertEquals(200, response.getStatus());
        assertEquals("operatortoken:tk", authRequests.lastLoginHint);
        assertEquals("number-verification:verify", authRequests.lastScope);
        assertEquals("client-json", authRequests.lastClientId);
    }

    @Test
    void malformedJsonBody_invalidRequest400() throws Exception {
        Response response = authorizeJson("{not-json", headers(null, null));
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_request");
    }

    @Test
    void serviceInvalidScope_mapsTo400invalidScope() throws Exception {
        authRequests.fail = CibaException.invalidScope("unsupported scope: unknown-scope");
        Response response = authorize(null, "unknown-scope", headers(null, null));
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_scope");
    }

    @Test
    void serviceAccessDenied_mapsTo403accessDenied() throws Exception {
        authRequests.fail = CibaException.accessDenied("no unique subscriber bound");
        Response response = authorize(null, "number-verification:verify",
                headers("10.20.30.40", "55555"));
        assertEquals(403, response.getStatus());
        assertErrorBody(response, "access_denied");
    }

    @Test
    void serviceInvalidRequest_mapsTo400invalidRequest() throws Exception {
        authRequests.fail = CibaException.invalidRequest("scope is required");
        Response response = authorize(null, null, headers(null, null));
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_request");
    }

    @Test
    void blankScope_delegatesToService_andMapsInvalidRequest() throws Exception {
        authRequests.fail = CibaException.invalidRequest("scope is required");

        Response response = authorize(null, "", headers(null, null));

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_request");
        assertEquals("", authRequests.lastScope, "scope validation lives in the service");
    }

    @Test
    void clientAuthenticatorFailure_mapsTo401invalidClient() throws Exception {
        resource.clientAuthenticator = new OAuthClientAuthenticator() {
            @Override
            public OAuthClient authenticate(String clientId,
                                            String clientAssertionType,
                                            String clientAssertion,
                                            Set<String> audiences,
                                            boolean required) {
                throw OAuthException.invalidClient("client authentication is required");
            }
        };

        Response response = resource.authorize(null, "number-verification:verify",
                null, OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER, null,
                headers(null, null), null);

        assertEquals(401, response.getStatus());
        assertErrorBody(response, "invalid_client");
        assertFalse(authRequests.called, "client authentication must gate request minting");
    }

    // ---- plumbing (no Quarkus boot, no HTTP server) ----

    private Response authorize(String loginHint, String scope, HttpHeaders headers) {
        return resource.authorize(loginHint, scope, null, null, null, headers, null);
    }

    private Response authorizeJson(String body, HttpHeaders headers) {
        return resource.authorizeJson(body, headers, null);
    }

    private static final class FakeAuthRequests extends AuthorizationRequestService {
        AuthorizationRequestService.AuthRequest next;
        CibaException fail;
        boolean called;
        String lastLoginHint;
        String lastScope;
        String lastSrcIp;
        int lastSrcPort;
        String lastClientId;

        @Override
        public AuthRequest start(String loginHint,
                                 String srcIp,
                                 int srcPort,
                                 String requestedScope,
                                 String clientId) {
            called = true;
            lastLoginHint = loginHint;
            lastScope = requestedScope;
            lastSrcIp = srcIp;
            lastSrcPort = srcPort;
            lastClientId = clientId;
            if (fail != null) {
                throw fail;
            }
            return next;
        }
    }

    private static HttpHeaders headers(String ip, String port) {
        return new HttpHeaders() {
            @Override
            public List<String> getRequestHeader(String name) {
                return List.of();
            }

            @Override
            public MultivaluedMap<String, String> getRequestHeaders() {
                return new MultivaluedHashMap<>();
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return Map.of();
            }

            @Override
            public String getHeaderString(String name) {
                return switch (name) {
                    case BcAuthorizeResource.HEADER_SRC_IP -> ip;
                    case BcAuthorizeResource.HEADER_SRC_PORT -> port;
                    default -> null;
                };
            }

            @Override
            public MediaType getMediaType() {
                return null;
            }

            @Override
            public Locale getLanguage() {
                return null;
            }

            @Override
            public List<MediaType> getAcceptableMediaTypes() {
                return List.of();
            }

            @Override
            public List<Locale> getAcceptableLanguages() {
                return List.of();
            }

            @Override
            public int getLength() {
                return -1;
            }

            @Override
            public java.util.Date getDate() {
                return null;
            }
        };
    }

    private static void assertErrorBody(Response response, String expectedError)
            throws Exception {
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"error\":\"" + expectedError + "\""),
                "error body must carry " + expectedError + ": " + json);
        assertTrue(json.contains("error_description"));
    }

    private void inject(String fieldName, Object dependency) {
        try {
            var field = BcAuthorizeResource.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(resource, dependency);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
