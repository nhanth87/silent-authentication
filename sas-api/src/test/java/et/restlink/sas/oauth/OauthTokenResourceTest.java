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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /token} resource tests over fake services (no HTTP server): CIBA
 * grant-type enforcement, one-token-per-auth_req_id semantics, expired vs
 * unknown grant errors, fail-closed issuance, client-credentials grants and
 * CAMARA JWT bearer grants.
 */
class OauthTokenResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE_VERIFY = TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;
    private static final String PURPOSE = OAuthScopePolicy.PURPOSE_FRAUD_PREVENTION;
    private static final String MSISDN = "+251911111111";
    private static final String CLIENT_ID = "client-1";
    private static final String VALID_OPERATOR_TOKEN = "valid-operator-token";

    private TokenResource resource;
    private FakeAuthRequests authRequests;
    private FakeAccessTokens accessTokens;
    private FakeClientAuthenticator clientAuthenticator;
    private FakeIdentityAnchor operatorAnchor;

    @BeforeEach
    void setUp() throws Exception {
        resource = new TokenResource();
        authRequests = new FakeAuthRequests();
        accessTokens = new FakeAccessTokens();
        clientAuthenticator = new FakeClientAuthenticator();
        operatorAnchor = new FakeIdentityAnchor();
        inject("authRequests", authRequests);
        inject("accessTokens", accessTokens);
        inject("clientAuthenticator", clientAuthenticator);
        inject("operatorAnchor", operatorAnchor);
        inject("metadata", metadata("https://sas.example.com", false, Optional.empty()));
    }

    @Test
    void happyPath_returnsBearerTokenWithScopeAndTtl() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        authRequests.next = new AuthorizationRequestService.ConsumeResult(
                new PendingBinding("req-id", MSISDN, null,
                        Set.of(SCOPE_VERIFY), nowSec, nowSec + 120L), false);
        accessTokens.token = "signed-token-value";

        Response response = cibaToken(TokenResource.CIBA_GRANT_TYPE, "req-id");

        assertEquals(200, response.getStatus());
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"access_token\":\"signed-token-value\""));
        assertTrue(json.contains("\"token_type\":\"Bearer\""));
        assertTrue(json.contains("\"expires_in\":300"));
        assertTrue(json.contains("\"scope\":\"" + SCOPE_VERIFY + "\""));
    }

    @Test
    void cibaTokenBindsClientIdFromPendingBinding() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        authRequests.next = new AuthorizationRequestService.ConsumeResult(
                new PendingBinding("req-id", MSISDN, null, Set.of(SCOPE_VERIFY),
                        nowSec, nowSec + 120L, CLIENT_ID), false);
        accessTokens.token = "signed-token-value";
        clientAuthenticator.client = OAuthClient.anonymous(CLIENT_ID);

        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "req-id",
                null, CLIENT_ID, null, null, null, null);

        assertEquals(200, response.getStatus());
        assertEquals(CLIENT_ID, accessTokens.lastBinding.clientId());
    }

    @Test
    void cibaTokenForAnotherClient_invalidGrant400() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        authRequests.next = new AuthorizationRequestService.ConsumeResult(
                new PendingBinding("req-id", MSISDN, null, Set.of(SCOPE_VERIFY),
                        nowSec, nowSec + 120L, CLIENT_ID), false);
        clientAuthenticator.client = OAuthClient.anonymous("other-client");

        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "req-id",
                null, "other-client", null, null, null, null);

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_grant");
        assertNull(accessTokens.lastBinding);
    }

    @Test
    void cibaTokenWithScopeParameter_invalidRequest400() throws Exception {
        Response response = resource.token(TokenResource.CIBA_GRANT_TYPE, "req-id",
                SCOPE_VERIFY, null, null, null, null, null);

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_request");
        assertFalse(authRequests.called, "parameter validation must gate consumption");
    }

    @Test
    void wrongGrantType_unsupportedGrantType400() throws Exception {
        Response response = cibaToken("authorization_code", "req-id");
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "unsupported_grant_type");
        assertFalse(authRequests.called, "grant check must gate the consumption");
    }

    @Test
    void missingGrantType_unsupportedGrantType400() {
        Response response = cibaToken(null, "req-id");
        assertEquals(400, response.getStatus());
    }

    @Test
    void unknownAuthReqId_invalidGrant400() throws Exception {
        authRequests.next = new AuthorizationRequestService.ConsumeResult(null, false);
        Response response = cibaToken(TokenResource.CIBA_GRANT_TYPE, "bogus");
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_grant");
    }

    @Test
    void expiredAuthReqId_expiredToken400() throws Exception {
        authRequests.next = new AuthorizationRequestService.ConsumeResult(null, true);
        Response response = cibaToken(TokenResource.CIBA_GRANT_TYPE, "old-req-id");
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "expired_token");
    }

    @Test
    void blankSecretOnIssue_serverError500FailClosed() throws Exception {
        authRequests.next = new AuthorizationRequestService.ConsumeResult(
                new PendingBinding("req-id", MSISDN, null,
                        Set.of(SCOPE_VERIFY), 0L, Long.MAX_VALUE), false);
        accessTokens.failure = new IllegalStateException(
                "sas.oauth.secret is required to issue access tokens");

        Response response = cibaToken(TokenResource.CIBA_GRANT_TYPE, "req-id");
        assertEquals(500, response.getStatus());
        assertErrorBody(response, "server_error");
    }

    @Test
    void clientCredentialsHappy_returns2LeggedToken() throws Exception {
        accessTokens.token = "client-token";
        clientAuthenticator.client = OAuthClient.anonymous(CLIENT_ID);

        Response response = clientCredentials(PURPOSE + " " + SCOPE_VERIFY, CLIENT_ID);

        assertEquals(200, response.getStatus());
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"access_token\":\"client-token\""));
        assertTrue(json.contains(PURPOSE));
        assertTrue(json.contains(SCOPE_VERIFY));
        assertEquals(CLIENT_ID, accessTokens.lastClientId);
        assertEquals(Set.of(PURPOSE, SCOPE_VERIFY), accessTokens.lastScopes);
        assertNull(accessTokens.lastMsisdn);
    }

    @Test
    void clientCredentialsWithoutClientId_invalidRequest400() throws Exception {
        Response response = clientCredentials(SCOPE_VERIFY, null);
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_request");
        assertNull(accessTokens.lastClientId);
    }

    @Test
    void clientCredentialsInvalidScope_invalidScope400() throws Exception {
        clientAuthenticator.client = OAuthClient.anonymous(CLIENT_ID);
        Response response = clientCredentials("unknown-scope", CLIENT_ID);
        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_scope");
    }

    @Test
    void clientCredentialsRequiresClientAuthenticationWhenConfigured() throws Exception {
        inject("clientAuthenticator", requireClientAuthAuthenticator());

        Response response = clientCredentials(SCOPE_VERIFY, CLIENT_ID);

        assertEquals(401, response.getStatus());
        assertErrorBody(response, "invalid_client");
        assertNull(accessTokens.lastClientId);
    }

    @Test
    void jwtBearerHappyWithTelSubject_returns3LeggedToken() throws Exception {
        accessTokens.token = "user-token";
        clientAuthenticator.client = registeredClient(Set.of(TokenResource.JWT_BEARER_GRANT_TYPE));
        clientAuthenticator.assertion = assertion("tel:" + MSISDN, PURPOSE + " " + SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(200, response.getStatus());
        String json = MAPPER.writeValueAsString(response.getEntity());
        assertTrue(json.contains("\"access_token\":\"user-token\""));
        assertEquals(MSISDN, accessTokens.lastMsisdn);
        assertEquals(CLIENT_ID, accessTokens.lastClientId);
        assertEquals(Set.of(PURPOSE, SCOPE_VERIFY), accessTokens.lastScopes);
        assertEquals(Set.of("https://sas.example.com/token", "https://sas.example.com"),
                clientAuthenticator.lastAudiences);
    }

    @Test
    void jwtBearerHappyWithOperatorToken_resolvesSubscriber() throws Exception {
        accessTokens.token = "user-token";
        clientAuthenticator.client = registeredClient(Set.of(TokenResource.JWT_BEARER_GRANT_TYPE));
        clientAuthenticator.assertion = assertion(
                "operatortoken:" + VALID_OPERATOR_TOKEN, PURPOSE + " " + SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(200, response.getStatus());
        assertEquals(MSISDN, accessTokens.lastMsisdn);
        assertEquals(VALID_OPERATOR_TOKEN, operatorAnchor.lastCandidate);
    }

    @Test
    void jwtBearerWithScopeParameter_invalidRequest400() throws Exception {
        Response response = resource.token(TokenResource.JWT_BEARER_GRANT_TYPE, null,
                SCOPE_VERIFY, null, null, null, "assertion-value", null);

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_request");
        assertFalse(clientAuthenticator.jwtCalled);
    }

    @Test
    void jwtBearerWithoutPurpose_invalidScope400() throws Exception {
        clientAuthenticator.client = registeredClient(Set.of(TokenResource.JWT_BEARER_GRANT_TYPE));
        clientAuthenticator.assertion = assertion("tel:" + MSISDN, SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_scope");
        assertNull(accessTokens.lastMsisdn);
    }

    @Test
    void jwtBearerWithUnsupportedSubject_invalidGrant400() throws Exception {
        clientAuthenticator.client = registeredClient(Set.of(TokenResource.JWT_BEARER_GRANT_TYPE));
        clientAuthenticator.assertion = assertion("user@example.com", PURPOSE + " " + SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_grant");
    }

    @Test
    void jwtBearerWithInvalidOperatorToken_invalidGrant400() throws Exception {
        clientAuthenticator.client = registeredClient(Set.of(TokenResource.JWT_BEARER_GRANT_TYPE));
        clientAuthenticator.assertion = assertion("operatortoken:bogus", PURPOSE + " " + SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_grant");
    }

    @Test
    void jwtBearerClientNotAllowedForGrant_unauthorizedClient400() throws Exception {
        clientAuthenticator.client = registeredClient(Set.of(TokenResource.CLIENT_CREDENTIALS_GRANT_TYPE));
        clientAuthenticator.assertion = assertion("tel:" + MSISDN, PURPOSE + " " + SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "unauthorized_client");
    }

    @Test
    void jwtBearerClientNotAllowedForScope_invalidScope400() throws Exception {
        clientAuthenticator.client = new OAuthClient(CLIENT_ID,
                OAuthClient.AUTH_METHOD_PRIVATE_KEY_JWT,
                new JsonWebKeySet(List.of()),
                Set.of(TokenResource.JWT_BEARER_GRANT_TYPE),
                Set.of(),
                Set.of(TokenValidator.SCOPE_SIM_SWAP_CHECK),
                Set.of(PURPOSE));
        clientAuthenticator.assertion = assertion("tel:" + MSISDN, PURPOSE + " " + SCOPE_VERIFY);

        Response response = jwtBearer("assertion-value");

        assertEquals(400, response.getStatus());
        assertErrorBody(response, "invalid_scope");
    }

    // ---- plumbing (no Quarkus boot, no HTTP server) ----

    private Response cibaToken(String grantType, String authReqId) {
        return resource.token(grantType, authReqId, null, null, null, null, null, null);
    }

    private Response clientCredentials(String scope, String clientId) {
        return resource.token(TokenResource.CLIENT_CREDENTIALS_GRANT_TYPE, null,
                scope, clientId, null, null, null, null);
    }

    private Response jwtBearer(String assertion) {
        return resource.token(TokenResource.JWT_BEARER_GRANT_TYPE, null,
                null, null, null, null, assertion, null);
    }

    private static OAuthClient registeredClient(Set<String> grantTypes) {
        return new OAuthClient(CLIENT_ID,
                OAuthClient.AUTH_METHOD_PRIVATE_KEY_JWT,
                new JsonWebKeySet(List.of()),
                grantTypes,
                Set.of(),
                Set.of(SCOPE_VERIFY),
                Set.of(PURPOSE));
    }

    private static JsonWebSignature assertion(String subject, String scope) {
        String headerJson = "{\"alg\":\"ES256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"iss\":\"" + CLIENT_ID + "\",\"sub\":\"" + subject
                + "\",\"scope\":\"" + scope + "\"}";
        String header = base64Url(headerJson);
        String payload = base64Url(payloadJson);
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("test-signature".getBytes(StandardCharsets.UTF_8));
        return JsonWebSignature.parse(header + "." + payload + "." + signature);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static OAuthClientAuthenticator requireClientAuthAuthenticator() {
        OAuthClientAuthenticator authenticator = new OAuthClientAuthenticator();
        FakeOAuthServerConfig config = new FakeOAuthServerConfig(
                "https://sas.example.com", true, Optional.empty());
        OAuthClientRegistry registry = new OAuthClientRegistry();
        registry.config = config;
        authenticator.config = config;
        authenticator.registry = registry;
        authenticator.replayGuard = new OAuthAssertionReplayGuard();
        return authenticator;
    }

    private static OAuthMetadataService metadata(String issuer,
                                                 boolean requireClientAuth,
                                                 Optional<String> clientsJson) {
        OAuthMetadataService service = new OAuthMetadataService();
        service.config = new FakeOAuthServerConfig(issuer, requireClientAuth, clientsJson);
        return service;
    }

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
        PendingBinding lastBinding;
        String lastClientId;
        String lastMsisdn;
        Set<String> lastScopes;

        @Override
        public String issue(PendingBinding binding) {
            if (failure != null) {
                throw failure;
            }
            lastBinding = binding;
            return token;
        }

        @Override
        public String issueUserToken(String msisdn, Set<String> scopes, String clientId) {
            if (failure != null) {
                throw failure;
            }
            lastMsisdn = msisdn;
            lastScopes = scopes;
            lastClientId = clientId;
            return token;
        }

        @Override
        public String issueClientToken(String clientId, Set<String> scopes) {
            if (failure != null) {
                throw failure;
            }
            lastClientId = clientId;
            lastScopes = scopes;
            return token;
        }
    }

    private static final class FakeClientAuthenticator extends OAuthClientAuthenticator {
        OAuthClient client;
        JsonWebSignature assertion;
        Set<String> lastAudiences;
        boolean jwtCalled;

        @Override
        public OAuthClient authenticate(String clientId,
                                        String clientAssertionType,
                                        String clientAssertion,
                                        Set<String> audiences,
                                        boolean required) {
            lastAudiences = audiences;
            if (client != null) {
                return client;
            }
            return OAuthClient.anonymous(clientId);
        }

        @Override
        public AuthenticatedAssertion authenticateJwtBearer(String rawAssertion,
                                                            Set<String> audiences) {
            jwtCalled = true;
            lastAudiences = audiences;
            return new AuthenticatedAssertion(client, assertion);
        }
    }

    private static final class FakeIdentityAnchor implements IdentityAnchor {
        String lastCandidate;

        @Override
        public OperatorBinding resolveOperatorToken(String candidate) {
            lastCandidate = candidate;
            return VALID_OPERATOR_TOKEN.equals(candidate)
                    ? new OperatorBinding(MSISDN, null)
                    : null;
        }
    }

    private static final class FakeOAuthServerConfig extends OAuthServerConfig {
        private final String issuer;
        private final boolean requireClientAuth;
        private final Optional<String> clientsJson;

        private FakeOAuthServerConfig(String issuer,
                                      boolean requireClientAuth,
                                      Optional<String> clientsJson) {
            this.issuer = issuer;
            this.requireClientAuth = requireClientAuth;
            this.clientsJson = clientsJson;
        }

        @Override
        public String issuer() {
            return issuer;
        }

        @Override
        public Optional<String> publicBaseUrl() {
            return Optional.of(issuer);
        }

        @Override
        public Optional<String> jwksJson() {
            return Optional.empty();
        }

        @Override
        public Optional<String> clientsJson() {
            return clientsJson;
        }

        @Override
        public boolean requireClientAuth() {
            return requireClientAuth;
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
