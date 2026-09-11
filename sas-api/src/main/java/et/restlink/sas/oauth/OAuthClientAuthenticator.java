/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class OAuthClientAuthenticator {

    public static final String CLIENT_ASSERTION_TYPE_JWT_BEARER =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    public static final String JWT_BEARER_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:jwt-bearer";

    static final long MAX_ASSERTION_LIFETIME_SECONDS = 300L;

    @Inject
    OAuthServerConfig config;

    @Inject
    OAuthClientRegistry registry;

    @Inject
    OAuthAssertionReplayGuard replayGuard;

    public record AuthenticatedAssertion(OAuthClient client, JsonWebSignature assertion) {}

    public OAuthClient authenticate(String clientId,
                                    String clientAssertionType,
                                    String clientAssertion,
                                    Set<String> audiences,
                                    boolean required) {
        if (clientAssertion == null || clientAssertion.isBlank()) {
            if (required || (config != null && config.requireClientAuth())) {
                throw OAuthException.invalidClient("client authentication is required");
            }
            if (clientAssertionType != null && !clientAssertionType.isBlank()) {
                throw OAuthException.invalidClient("client_assertion is missing");
            }
            return OAuthClient.anonymous(clientId);
        }
        if (clientAssertionType == null || !CLIENT_ASSERTION_TYPE_JWT_BEARER.equals(clientAssertionType.trim())) {
            throw OAuthException.invalidClient(
                    "client_assertion_type must be " + CLIENT_ASSERTION_TYPE_JWT_BEARER);
        }
        AuthenticatedAssertion authenticated = validateAssertion(
                clientAssertion, audiences, "client-assertion", true);
        String assertionClientId = authenticated.assertion().stringClaim("iss");
        if (clientId != null && !clientId.isBlank() && !clientId.trim().equals(assertionClientId)) {
            throw OAuthException.invalidClient("client_id does not match the client assertion iss");
        }
        return authenticated.client();
    }

    public AuthenticatedAssertion authenticateJwtBearer(String assertion, Set<String> audiences) {
        return validateAssertion(assertion, audiences, "jwt-bearer", false);
    }

    private AuthenticatedAssertion validateAssertion(String rawAssertion,
                                                      Set<String> audiences,
                                                      String replayNamespace,
                                                      boolean subjectMustEqualIssuer) {
        if (rawAssertion == null || rawAssertion.isBlank()) {
            throw OAuthException.invalidClient("missing JWT assertion");
        }
        JsonWebSignature assertion;
        try {
            assertion = JsonWebSignature.parse(rawAssertion);
        } catch (OAuthException e) {
            throw OAuthException.invalidClient(e.getMessage());
        }
        String alg = assertion.algorithm();
        if (alg == null) {
            throw OAuthException.invalidClient("JWT assertion is missing alg");
        }
        String issuer = assertion.stringClaim("iss");
        if (issuer == null) {
            throw OAuthException.invalidClient("JWT assertion is missing iss");
        }
        OAuthClient client = client(issuer);
        if (!client.authenticated()) {
            throw OAuthException.invalidClient("client has no usable private_key_jwt key");
        }
        if (!verifySignature(assertion, client.jwks())) {
            throw OAuthException.invalidClient("JWT assertion signature is invalid");
        }
        if (subjectMustEqualIssuer) {
            String subject = assertion.stringClaim("sub");
            if (subject == null || !subject.equals(issuer)) {
                throw OAuthException.invalidClient("client assertion sub must equal iss");
            }
        }
        validateAudience(assertion, audiences);
        validateLifetime(assertion);
        String jti = assertion.stringClaim("jti");
        if (jti == null) {
            throw OAuthException.invalidClient("JWT assertion is missing jti");
        }
        OAuthAssertionReplayGuard guard = replayGuard == null ? new OAuthAssertionReplayGuard() : replayGuard;
        if (!guard.useOnce(replayNamespace + ":" + issuer, jti)) {
            throw OAuthException.invalidClient("JWT assertion jti has already been used");
        }
        return new AuthenticatedAssertion(client, assertion);
    }

    private OAuthClient client(String clientId) {
        OAuthClientRegistry clientRegistry = registry == null ? new OAuthClientRegistry() : registry;
        return clientRegistry.find(clientId)
                .orElseThrow(() -> OAuthException.invalidClient("unknown client: " + clientId));
    }

    private static boolean verifySignature(JsonWebSignature assertion, JsonWebKeySet jwks) {
        List<JsonWebKey> candidates = jwks.candidates(assertion.keyId(), assertion.algorithm());
        for (JsonWebKey key : candidates) {
            if (assertion.verify(key)) {
                return true;
            }
        }
        return false;
    }

    private static void validateAudience(JsonWebSignature assertion, Set<String> audiences) {
        List<String> values = assertion.stringListClaim("aud");
        if (values.isEmpty() || audiences == null || audiences.isEmpty()) {
            throw OAuthException.invalidClient("JWT assertion audience is invalid");
        }
        for (String value : values) {
            if (audiences.contains(value)) {
                return;
            }
        }
        throw OAuthException.invalidClient("JWT assertion audience is invalid");
    }

    private static void validateLifetime(JsonWebSignature assertion) {
        Long exp = assertion.longClaim("exp");
        Long iat = assertion.longClaim("iat");
        if (exp == null || iat == null) {
            throw OAuthException.invalidClient("JWT assertion requires exp and iat");
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (exp < nowSec) {
            throw OAuthException.invalidClient("JWT assertion has expired");
        }
        if (exp > nowSec + MAX_ASSERTION_LIFETIME_SECONDS) {
            throw OAuthException.invalidClient("JWT assertion lifetime exceeds 300 seconds");
        }
        if (iat > nowSec) {
            throw OAuthException.invalidClient("JWT assertion was issued in the future");
        }
        if (exp - iat > MAX_ASSERTION_LIFETIME_SECONDS) {
            throw OAuthException.invalidClient("JWT assertion lifetime exceeds 300 seconds");
        }
    }
}
