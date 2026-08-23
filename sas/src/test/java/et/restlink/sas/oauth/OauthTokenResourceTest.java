/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.security.TokenValidator;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /token} resource tests over fake services (no HTTP server): CIBA
 * grant-type enforcement, one-token-per-auth_req_id semantics, expired vs
 * unknown grant errors and fail-closed issuance.
 */
class OauthTokenResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE_VERIFY = TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;

    private TokenResource resource;
    private FakeAuthRequests authRequests;
    private FakeAccessTokens accessTokens;

    @BeforeEach
    void setUp() throws Exception {
        resource = new TokenResource();
        authRequests = new FakeAuthRequests();
        accessTokens = new FakeAccessTokens();
        inject("authRequests", authRequests);
        inject("accessTokens", accessTokens);
    }

    @Test
    void happyPath_returnsBearerTokenWithScopeAndTtl() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        authRequests.next = new AuthorizationRequestService.ConsumeResult(
                new PendingBinding("req-id", "+251911111111", null,
                        Set.of(SCOPE_VERIFY), nowSec, nowSec + 120L), false);
        accessTokens.token = "signed-token-value";

        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "req-id");

        assertEquals(200, response.getStatus());
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"access_token\":\"signed-token-value\""));
        assertTrue(json.contains("\"token_type\":\"Bearer\""));
        assertTrue(json.contains("\"expires_in\":300"));
        assertTrue(json.contains("\"scope\":\"" + SCOPE_VERIFY + "\""));
    }

    @Test
    void wrongGrantType_unsupportedGrantType400() throws Exception {
        Response response = resource.token("client_credentials", "req-id");
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "unsupported_grant_type");
        assertFalse(authRequests.called, "grant check must gate the consumption");
    }

    @Test
    void missingGrantType_unsupportedGrantType400() {
        Response response = resource.token(null, "req-id");
        assertEquals(400, response.getStatus());
    }

    @Test
    void unknownAuthReqId_invalidGrant400() throws Exception {
        authRequests.next = new AuthorizationRequestService.ConsumeResult(null, false);
        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "bogus");
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_grant");
    }

    @Test
    void expiredAuthReqId_expiredToken400() throws Exception {
        authRequests.next = new AuthorizationRequestService.ConsumeResult(null, true);
        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "old-req-id");
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "expired_token");
    }

    @Test
    void blankSecretOnIssue_serverError500FailClosed() throws Exception {
        authRequests.next = new AuthorizationRequestService.ConsumeResult(
                new PendingBinding("req-id", "+251911111111", null,
                        Set.of(SCOPE_VERIFY), 0L, Long.MAX_VALUE), false);
        accessTokens.failure = new IllegalStateException(
                "sas.oauth.secret is required to issue access tokens");

        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "req-id");
        assertEquals(500, response.getStatus());
        assertErrorBody(response, "server_error");
    }

    // ---- plumbing (no Quarkus boot, no HTTP server) ----

    private static final class FakeAuthRequests extends AuthorizationRequestService {
        AuthorizationRequestService.ConsumeResult next;
        boolean called;

        @Override
        public ConsumeResult consume(String authReqId) {
            called = true;
            return next;
        }
    }

    private static final class FakeAccessTokens extends AccessTokenService {
        String token;
        RuntimeException failure;

        @Override
        public String issue(PendingBinding binding) {
            if (failure != null) {
                throw failure;
            }
            return token;
        }
    }

    private static void assertErrorBody(Response response, String expectedError)
            throws Exception {
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"error\":\"" + expectedError + "\""),
                "error body must carry " + expectedError + ": " + json);
    }

    private void inject(String fieldName, Object dependency) {
        try {
            var field = TokenResource.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(resource, dependency);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
