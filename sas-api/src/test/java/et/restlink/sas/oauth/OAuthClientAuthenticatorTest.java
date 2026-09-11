/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.security.TokenValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OAuthClientAuthenticatorTest {

    private static final String CLIENT_ID = "client-1";
    private static final String AUDIENCE = "https://sas.example.com/token";
    private static final String SCOPE = OAuthScopePolicy.PURPOSE_FRAUD_PREVENTION + " "
            + TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;

    private OAuthClientAuthenticator authenticator;
    private OAuthAssertionReplayGuard replayGuard;
    private PrivateKey privateKey;
    private String clientsJson;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        privateKey = keyPair.getPrivate();
        clientsJson = "{\"clients\":[{"
                + "\"client_id\":\"" + CLIENT_ID + "\","
                + "\"token_endpoint_auth_method\":\"private_key_jwt\","
                + "\"grant_types\":[\"" + TokenResource.JWT_BEARER_GRANT_TYPE + "\"],"
                + "\"scopes\":[\"" + TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY + "\"],"
                + "\"purposes\":[\"" + OAuthScopePolicy.PURPOSE_FRAUD_PREVENTION + "\"],"
                + "\"jwks\":{\"keys\":[" + rsaJwk((RSAPublicKey) keyPair.getPublic()) + "]}"
                + "}]}";
        replayGuard = new OAuthAssertionReplayGuard();
        authenticator = authenticator(false);
    }

    @Test
    void validClientAssertionIsAccepted() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 60, 0, "client-jti-1");

        OAuthClient client = authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true);

        assertEquals(CLIENT_ID, client.clientId());
        assertTrue(client.authenticated());
    }

    @Test
    void missingClientAssertionIsRejectedWhenRequired() {
        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                null,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
        assertEquals(401, e.httpStatus());
    }

    @Test
    void missingClientAssertionIsAllowedForLegacyAnonymousRequests() {
        OAuthClient client = authenticator.authenticate(
                CLIENT_ID, null, null, Set.of(AUDIENCE), false);

        assertEquals(CLIENT_ID, client.clientId());
        assertFalse(client.authenticated());
    }

    @Test
    void wrongAssertionTypeIsRejected() {
        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID, "client_secret_basic", "assertion", Set.of(AUDIENCE), true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void clientIdMismatchIsRejected() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 60, 0, "client-jti-2");

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                "other-client",
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void clientAssertionSubjectMustEqualIssuer() {
        String assertion = signedAssertion(CLIENT_ID, "other-subject", AUDIENCE, 60, 0, "client-jti-3");

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void wrongAudienceIsRejected() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, "https://evil.example.com/token",
                60, 0, "client-jti-4");

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void expiredAssertionIsRejected() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, -10, -100, "client-jti-5");

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void futureIssuedAssertionIsRejected() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 120, 60, "client-jti-6");

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void excessiveLifetimeIsRejected() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 400, 0, "client-jti-7");

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void replayedAssertionIsRejected() {
        String assertion = signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 60, 0, "client-jti-8");

        authenticator.authenticate(CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion, Set.of(AUDIENCE), true);
        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void jwtBearerSubjectMayIdentifyTheUser() {
        String assertion = signedAssertion(CLIENT_ID, "tel:+251911111111", AUDIENCE,
                60, 0, "jwt-bearer-jti-1");

        OAuthClientAuthenticator.AuthenticatedAssertion authenticated =
                authenticator.authenticateJwtBearer(assertion, Set.of(AUDIENCE));

        assertEquals(CLIENT_ID, authenticated.client().clientId());
        assertEquals("tel:+251911111111", authenticated.assertion().stringClaim("sub"));
        assertEquals(SCOPE, authenticated.assertion().stringClaim("scope"));
    }

    @Test
    void jwtBearerReplayUsesSeparateNamespaceFromClientAssertion() {
        String assertion = signedAssertion(CLIENT_ID, "tel:+251911111111", AUDIENCE,
                60, 0, "shared-jti");

        authenticator.authenticate(CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 60, 0, "shared-jti"),
                Set.of(AUDIENCE), true);
        assertDoesNotThrow(() ->
                authenticator.authenticateJwtBearer(assertion, Set.of(AUDIENCE)));
    }

    @Test
    void unsupportedSignatureAlgorithmIsRejected() {
        String assertion = unsignedHmacAssertion();

        OAuthException e = assertThrows(OAuthException.class, () -> authenticator.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                assertion,
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void requireClientAuthConfigurationRejectsAnonymousRequests() {
        OAuthClientAuthenticator required = authenticator(true);

        OAuthException e = assertThrows(OAuthException.class, () -> required.authenticate(
                CLIENT_ID, null, null, Set.of(AUDIENCE), false));
        assertEquals("invalid_client", e.error());
    }

    @Test
    void invalidRegistryConfigurationFailsClosed() {
        OAuthClientAuthenticator broken = authenticator(false, "{not-json");

        OAuthException e = assertThrows(OAuthException.class, () -> broken.authenticate(
                CLIENT_ID,
                OAuthClientAuthenticator.CLIENT_ASSERTION_TYPE_JWT_BEARER,
                signedAssertion(CLIENT_ID, CLIENT_ID, AUDIENCE, 60, 0, "client-jti-9"),
                Set.of(AUDIENCE),
                true));
        assertEquals("invalid_client", e.error());
    }

    private OAuthClientAuthenticator authenticator(boolean requireClientAuth) {
        return authenticator(requireClientAuth, clientsJson);
    }

    private OAuthClientAuthenticator authenticator(boolean requireClientAuth, String registryJson) {
        OAuthServerConfig config = new FakeOAuthServerConfig(requireClientAuth, registryJson);
        OAuthClientRegistry registry = new OAuthClientRegistry();
        registry.config = config;
        OAuthClientAuthenticator created = new OAuthClientAuthenticator();
        created.config = config;
        created.registry = registry;
        created.replayGuard = replayGuard;
        return created;
    }

    private String signedAssertion(String issuer,
                                   String subject,
                                   String audience,
                                   long expiresOffsetSeconds,
                                   long issuedOffsetSeconds,
                                   String jti) {
        long nowSec = System.currentTimeMillis() / 1000L;
        String header = "{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}";
        String payload = "{"
                + "\"iss\":\"" + issuer + "\","
                + "\"sub\":\"" + subject + "\","
                + "\"aud\":\"" + audience + "\","
                + "\"exp\":" + (nowSec + expiresOffsetSeconds) + ","
                + "\"iat\":" + (nowSec + issuedOffsetSeconds) + ","
                + "\"jti\":\"" + jti + "\","
                + "\"scope\":\"" + SCOPE + "\""
                + "}";
        String signingInput = base64Url(header) + "." + base64Url(payload);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String unsignedHmacAssertion() {
        long nowSec = System.currentTimeMillis() / 1000L;
        String header = "{\"alg\":\"HS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}";
        String payload = "{"
                + "\"iss\":\"" + CLIENT_ID + "\","
                + "\"sub\":\"" + CLIENT_ID + "\","
                + "\"aud\":\"" + AUDIENCE + "\","
                + "\"exp\":" + (nowSec + 60) + ","
                + "\"iat\":" + nowSec + ","
                + "\"jti\":\"hmac-jti\""
                + "}";
        return base64Url(header) + "." + base64Url(payload) + "."
                + base64Url("not-an-asymmetric-signature");
    }

    private static String rsaJwk(RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"k1\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + base64UrlUnsigned(key.getModulus()) + "\","
                + "\"e\":\"" + base64UrlUnsigned(key.getPublicExponent()) + "\"}";
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeOAuthServerConfig extends OAuthServerConfig {
        private final boolean requireClientAuth;
        private final String clientsJson;

        private FakeOAuthServerConfig(boolean requireClientAuth, String clientsJson) {
            this.requireClientAuth = requireClientAuth;
            this.clientsJson = clientsJson;
        }

        @Override
        public String issuer() {
            return "https://sas.example.com";
        }

        @Override
        public Optional<String> publicBaseUrl() {
            return Optional.of("https://sas.example.com");
        }

        @Override
        public Optional<String> clientsJson() {
            return Optional.ofNullable(clientsJson);
        }

        @Override
        public boolean requireClientAuth() {
            return requireClientAuth;
        }
    }
}
