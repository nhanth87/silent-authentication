/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;
import et.restlink.sas.token.Es256Jws;
import et.restlink.sas.token.Es256Keys;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (in-process) Authorization Code + PKCE flow for CAMARA Number
 * Verification: /authorize with network-based authentication → code → /token →
 * ES256 access token that the NV resource server accepts, plus a pairwise ES256
 * ID token.
 */
class AuthorizationCodeFlowTest {

    private static final String ISSUER = "https://camara.lab.restlink.et";
    private static final String CLIENT_ID = "bank-1";
    private static final String REDIRECT = "https://bank.example/cb";
    private static final String MSISDN = "+251911111111";
    private static final String DEVICE_IP = "10.20.30.40";
    private static final String SCOPE = "openid dpv:FraudPreventionAndDetection "
            + TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private final ECKey signingKey = Es256Keys.generate(KeyUse.SIGNATURE);

    private AuthorizeResource authorize;
    private TokenResource token;
    private OAuthSigningKeys keys;
    private AuthorizationRequestService networkAuth;

    @BeforeEach
    void setUp() throws Exception {
        LabConfig config = new LabConfig();
        keys = new OAuthSigningKeys();
        keys.config = config;
        keys.use(signingKey);

        OAuthClientRegistry registry = new OAuthClientRegistry();
        registry.config = config;

        networkAuth = new AuthorizationRequestService();
        networkAuth.resolverOverride = (ip, port, ts) -> CompletableFuture.completedFuture(
                DEVICE_IP.equals(ip) ? ResolverResult.bound(MSISDN, "636010000000001", 1000L)
                        : ResolverResult.miss(FallbackReason.RESOLVER_ERROR));

        AuthorizationCodeService codes = new AuthorizationCodeService();
        authorize = new AuthorizeResource();
        authorize.registry = registry;
        authorize.networkAuth = networkAuth;
        authorize.codes = codes;
        authorize.trustSrcHeader = true;

        AccessTokenService access = new AccessTokenService();
        access.config = config;
        access.signingKeys = keys;
        IdTokenService id = new IdTokenService();
        id.config = config;
        id.signingKeys = keys;
        OAuthMetadataService metadata = new OAuthMetadataService();
        metadata.config = config;
        metadata.signingKeys = keys;

        token = new TokenResource();
        token.accessTokens = access;
        token.authorizationCodes = codes;
        token.idTokens = id;
        token.metadata = metadata;
    }

    @Test
    void fullFlowIssuesVerifiableTokensBoundToTheNetworkSubscriber() throws Exception {
        String code = authorizeOk("st-1", "n-1");

        Response r = exchange(code, REDIRECT, VERIFIER);
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("no-store", r.getHeaderString("Cache-Control"));

        // Access token: ES256 at+jwt, verifiable by the NV resource server, amr=mno.
        String at = (String) body.get("access_token");
        assertEquals("ES256", Es256Jws.peekAlgorithm(at));
        TokenValidator validator = validator(false);
        TokenValidator.DetailedAuth auth = validator.validateDetailed("Bearer " + at);
        assertTrue(auth.ok(), auth.error());
        assertEquals(MSISDN, auth.boundNumber());
        assertNull(validator.resolveAmrError(auth.amrValues(), null),
                "network-authenticated token proves mobile-network auth on its own");

        // ID token: ES256, pairwise sub, nonce echoed, aud = client.
        String idToken = (String) body.get("id_token");
        assertNotNull(idToken);
        JWTClaimsSet id = Es256Jws.verify(idToken, keys.verificationKeys(), JOSEObjectType.JWT);
        assertNotNull(id);
        assertEquals(List.of(CLIENT_ID), id.getAudience());
        assertEquals("n-1", id.getStringClaim("nonce"));
        assertNotEquals(MSISDN, id.getSubject());
        assertFalse(id.toString().contains("251911111111"), "ID token never carries the number");
        assertEquals(List.of("mno"), id.getStringListClaim("amr"));
    }

    @Test
    void codeIsSingleUse() {
        String code = authorizeOk("s", null);
        assertEquals(200, exchange(code, REDIRECT, VERIFIER).getStatus());
        Response replay = exchange(code, REDIRECT, VERIFIER);
        assertEquals(400, replay.getStatus());
        assertEquals("invalid_grant", ((OAuthErrorResponse) replay.getEntity()).error());
    }

    @Test
    void wrongPkceVerifierRefusedAndCodeIsSpent() {
        String code = authorizeOk("s", null);
        Response bad = exchange(code, REDIRECT, "x".repeat(43));
        assertEquals(400, bad.getStatus());
        assertEquals(400, exchange(code, REDIRECT, VERIFIER).getStatus(),
                "a failed exchange still consumes the code");
    }

    @Test
    void redirectUriMustMatchAtTokenTime() {
        String code = authorizeOk("s", null);
        assertEquals(400, exchange(code, "https://bank.example/other", VERIFIER).getStatus());
    }

    @Test
    void unregisteredRedirectIsNeverFollowed() {
        Response r = authorizeCall("https://evil.example/cb", challenge(VERIFIER), "S256", DEVICE_IP);
        assertEquals(400, r.getStatus());
        assertNull(r.getLocation(), "no open redirect");
    }

    @Test
    void unknownClientIsNeverRedirected() {
        Response r = authorize.authorize("code", "nobody", REDIRECT, SCOPE, "s", null,
                challenge(VERIFIER), "S256", null, null, DEVICE_IP, "1234", null);
        assertEquals(400, r.getStatus());
        assertNull(r.getLocation());
    }

    @Test
    void pkceIsMandatory() {
        Response r = authorizeCall(REDIRECT, null, null, DEVICE_IP);
        assertEquals(302, r.getStatus());
        Map<String, String> q = query(r.getLocation());
        assertEquals("invalid_request", q.get("error"));
        assertEquals("st", q.get("state"));
        Response plain = authorizeCall(REDIRECT, challenge(VERIFIER), "plain", DEVICE_IP);
        assertEquals("invalid_request", query(plain.getLocation()).get("error"));
    }

    @Test
    void unresolvedNetworkSourceIsAccessDenied() {
        Response r = authorizeCall(REDIRECT, challenge(VERIFIER), "S256", "192.0.2.1");
        assertEquals(302, r.getStatus());
        assertEquals("access_denied", query(r.getLocation()).get("error"));
        assertNull(query(r.getLocation()).get("code"));
    }

    @Test
    void sourceHeaderIgnoredUnlessTrusted() {
        authorize.trustSrcHeader = false;
        // No live connection in a unit test: the header must not be used as a fallback.
        Response r = authorizeCall(REDIRECT, challenge(VERIFIER), "S256", DEVICE_IP);
        assertEquals(302, r.getStatus());
        assertEquals("access_denied", query(r.getLocation()).get("error"));
    }

    @Test
    void hs256TokensRefusedWhenResourceServerIsEs256Only() throws Exception {
        String hs = hs256Token("lab-secret");
        TokenValidator strict = validator(false);
        assertFalse(strict.validateDetailed("Bearer " + hs).ok());
        TokenValidator legacy = validator(true);
        assertTrue(legacy.validateDetailed("Bearer " + hs).ok(), "lab switch keeps HS256 working");
    }

    @Test
    void jwksPublishesTheSigningKeyWithoutPrivatePart() {
        OAuthMetadataService metadata = token.metadata;
        Map<String, Object> jwks = metadata.jwks();
        String json = jwks.toString();
        assertTrue(json.contains(signingKey.getKeyID()));
        for (Object k : (List<?>) jwks.get("keys")) {
            assertFalse(((Map<?, ?>) k).containsKey("d"), "no private scalar in a published JWK");
        }
        Map<String, Object> meta = metadata.metadata(null);
        assertEquals(ISSUER + "/authorize", meta.get("authorization_endpoint"));
        assertEquals(List.of("S256"), meta.get("code_challenge_methods_supported"));
        assertEquals(List.of("ES256"), meta.get("id_token_signing_alg_values_supported"));
    }

    // ------------------------------------------------------------------ helpers

    private String authorizeOk(String state, String nonce) {
        Response r = authorize.authorize("code", CLIENT_ID, REDIRECT, SCOPE, state, nonce,
                challenge(VERIFIER), "S256", null, null, DEVICE_IP, "40000", null);
        assertEquals(302, r.getStatus(), () -> String.valueOf(r.getEntity()));
        Map<String, String> q = query(r.getLocation());
        assertEquals(state, q.get("state"));
        assertNotNull(q.get("code"));
        return q.get("code");
    }

    private Response authorizeCall(String redirect, String challenge, String method, String ip) {
        return authorize.authorize("code", CLIENT_ID, redirect, SCOPE, "st", null,
                challenge, method, null, null, ip, "40000", null);
    }

    private Response exchange(String code, String redirect, String verifier) {
        return token.token(TokenResource.AUTHORIZATION_CODE_GRANT_TYPE, null, null, CLIENT_ID,
                null, null, null, code, redirect, verifier, null);
    }

    private TokenValidator validator(boolean acceptHs256) throws Exception {
        SasSecurityConfig sec = new SasSecurityConfig();
        set(sec, "tokenValidationEnabled", true);
        set(sec, "hmacSecret", Optional.of("lab-secret"));
        set(sec, "expectedIssuer", Optional.empty());
        set(sec, "expectedAudience", Optional.empty());
        set(sec, "requiredScopesRaw", Optional.empty());
        set(sec, "clockSkewSeconds", 30L);
        set(sec, "acceptHs256", Optional.of(acceptHs256));
        TokenValidator v = new TokenValidator();
        set(v, "config", sec);
        set(v, "signingKeys", keys);
        return v;
    }

    private static String hs256Token(String secret) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"x\",\"phone_number\":\"" + MSISDN + "\",\"iat\":" + now
                + ",\"exp\":" + (now + 60) + ",\"jti\":\"j\"}");
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + sig;
    }

    private static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) {
            return out;
        }
        for (String kv : uri.getRawQuery().split("&")) {
            int eq = kv.indexOf('=');
            out.put(java.net.URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    private static final class LabConfig extends OAuthServerConfig {
        @Override
        public String issuer() {
            return ISSUER;
        }

        @Override
        public Optional<String> publicBaseUrl() {
            return Optional.of(ISSUER);
        }

        @Override
        public String signingAlg() {
            return "ES256";
        }

        @Override
        public Optional<String> pairwiseSecret() {
            return Optional.of("pairwise-test-secret");
        }

        @Override
        public Optional<String> clientsJson() {
            return Optional.of("{\"clients\":[{\"client_id\":\"" + CLIENT_ID + "\","
                    + "\"token_endpoint_auth_method\":\"none\","
                    + "\"grant_types\":[\"authorization_code\"],"
                    + "\"redirect_uris\":[\"" + REDIRECT + "\"],"
                    + "\"scopes\":[\"" + TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY + "\"],"
                    + "\"purposes\":[\"dpv:FraudPreventionAndDetection\"]}]}");
        }
    }
}
