/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.security.RequestValidator;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OAuth token endpoint ({@code POST /token}, CAMARA ICM): supports the CIBA
 * grant, the 2-legged client-credentials grant and the CAMARA JWT bearer
 * grant. The CIBA pending binding is consumed atomically, so one
 * {@code auth_req_id} yields exactly ONE token; unknown ids answer
 * {@code invalid_grant}, expired ones {@code expired_token}. JWT bearer
 * assertions are one-time client-authenticated authorization grants that
 * identify the user through {@code tel:} or {@code operatortoken:}.
 */
@Path("/token")
@Produces(MediaType.APPLICATION_JSON)
public class TokenResource {

    private static final Logger LOG = LogManager.getLogger(TokenResource.class);

    public static final String CIBA_GRANT_TYPE = "urn:openid:params:grant-type:ciba";
    public static final String CLIENT_CREDENTIALS_GRANT_TYPE = "client_credentials";
    public static final String JWT_BEARER_GRANT_TYPE = OAuthClientAuthenticator.JWT_BEARER_GRANT_TYPE;

    private static final String TEL_SCHEME_PREFIX = "tel:";

    @Inject
    AuthorizationRequestService authRequests;

    @Inject
    AccessTokenService accessTokens;

    @Inject
    OAuthClientAuthenticator clientAuthenticator;

    @Inject
    OAuthMetadataService metadata;

    @Inject
    IdentityAnchor operatorAnchor;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response token(@FormParam("grant_type") String grantType,
                          @FormParam("auth_req_id") String authReqId,
                          @FormParam("scope") String scope,
                          @FormParam("client_id") String clientId,
                          @FormParam("client_assertion_type") String clientAssertionType,
                          @FormParam("client_assertion") String clientAssertion,
                          @FormParam("assertion") String assertion,
                          @Context UriInfo uriInfo) {
        String normalizedGrantType = trimToNull(grantType);
        if (normalizedGrantType == null) {
            return error(OAuthException.unsupportedGrantType("grant_type is required"));
        }
        try {
            return switch (normalizedGrantType) {
                case CIBA_GRANT_TYPE -> cibaToken(authReqId, scope, clientId,
                        clientAssertionType, clientAssertion, uriInfo);
                case CLIENT_CREDENTIALS_GRANT_TYPE -> clientCredentialsToken(scope, clientId,
                        clientAssertionType, clientAssertion, uriInfo);
                case JWT_BEARER_GRANT_TYPE -> jwtBearerToken(scope, assertion, uriInfo);
                default -> throw OAuthException.unsupportedGrantType(
                        "unsupported grant_type: " + normalizedGrantType);
            };
        } catch (OAuthException e) {
            return error(e);
        } catch (IllegalStateException e) {
            LOG.error("[SAS] /token issuance failed closed: {}", e.getMessage());
            return error(OAuthException.serverError("token issuance unavailable"));
        }
    }

    private Response cibaToken(String authReqId,
                               String scope,
                               String clientId,
                               String clientAssertionType,
                               String clientAssertion,
                               UriInfo uriInfo) {
        if (trimToNull(scope) != null) {
            throw OAuthException.invalidRequest("scope MUST NOT be specified for the CIBA token request");
        }
        OAuthClient client = authenticateClient(
                clientId, clientAssertionType, clientAssertion, uriInfo, false);
        client.authorizeGrant(CIBA_GRANT_TYPE);

        AuthorizationRequestService.ConsumeResult consumed = authRequests.consume(authReqId);
        if (consumed.binding() == null) {
            throw consumed.knownExpired()
                    ? CibaException.expiredToken("auth_req_id has expired")
                    : OAuthException.invalidGrant("auth_req_id is invalid or was already exchanged");
        }
        PendingBinding binding = consumed.binding();
        if (!Objects.equals(binding.clientId(), client.clientId())) {
            throw OAuthException.invalidGrant("auth_req_id was issued to another client");
        }
        client.authorizeGrantedScopes(binding.scopes());
        String accessToken = accessTokens.issue(binding);
        LOG.info("[SAS] /token granted CIBA token for {}", binding.authReqId());
        return tokenResponse(accessToken, binding.scopes());
    }

    private Response clientCredentialsToken(String scope,
                                            String clientId,
                                            String clientAssertionType,
                                            String clientAssertion,
                                            UriInfo uriInfo) {
        OAuthClient client = authenticateClient(
                clientId, clientAssertionType, clientAssertion, uriInfo, false);
        if (client.clientId() == null || client.clientId().isBlank()) {
            throw OAuthException.invalidRequest("client_id is required");
        }
        client.authorizeGrant(CLIENT_CREDENTIALS_GRANT_TYPE);
        OAuthScopePolicy.ScopeGrant grant = OAuthScopePolicy.parse(scope);
        client.authorizeScope(grant, false);
        Set<String> scopes = grant.responseScopes();
        String accessToken = accessTokens.issueClientToken(client.clientId(), scopes);
        LOG.info("[SAS] /token granted 2-legged token for client {}", client.clientId());
        return tokenResponse(accessToken, scopes);
    }

    private Response jwtBearerToken(String scope, String assertion, UriInfo uriInfo) {
        if (trimToNull(scope) != null) {
            throw OAuthException.invalidRequest(
                    "scope MUST NOT be specified for the JWT bearer token request");
        }
        if (clientAuthenticator == null || metadata == null) {
            throw OAuthException.serverError("JWT bearer authentication is unavailable");
        }
        OAuthClientAuthenticator.AuthenticatedAssertion authenticated =
                clientAuthenticator.authenticateJwtBearer(assertion, metadata.tokenEndpointAudiences(uriInfo));
        OAuthClient client = authenticated.client();
        client.authorizeGrant(JWT_BEARER_GRANT_TYPE);
        OAuthScopePolicy.ScopeGrant grant =
                OAuthScopePolicy.parse(authenticated.assertion().stringClaim("scope"));
        client.authorizeScope(grant, true);
        String msisdn = resolveSubject(authenticated.assertion().stringClaim("sub"));
        Set<String> scopes = grant.responseScopes();
        String accessToken = accessTokens.issueUserToken(msisdn, scopes, client.clientId());
        LOG.info("[SAS] /token granted JWT-bearer token for client {}", client.clientId());
        return tokenResponse(accessToken, scopes);
    }

    private OAuthClient authenticateClient(String clientId,
                                           String clientAssertionType,
                                           String clientAssertion,
                                           UriInfo uriInfo,
                                           boolean required) {
        if (clientAuthenticator == null) {
            return OAuthClient.anonymous(clientId);
        }
        Set<String> audiences = metadata == null ? Set.of() : metadata.tokenEndpointAudiences(uriInfo);
        return clientAuthenticator.authenticate(
                clientId, clientAssertionType, clientAssertion, audiences, required);
    }

    private String resolveSubject(String subject) {
        String value = trimToNull(subject);
        if (value == null) {
            throw OAuthException.invalidGrant("JWT assertion is missing sub");
        }
        if (value.regionMatches(true, 0, TEL_SCHEME_PREFIX, 0, TEL_SCHEME_PREFIX.length())) {
            return normalizedE164(value.substring(TEL_SCHEME_PREFIX.length()), "tel");
        }
        String operatorToken = IdentityAnchor.parseLoginHint(value);
        if (operatorToken != null) {
            if (operatorAnchor == null) {
                throw OAuthException.invalidGrant("operator token validation is unavailable");
            }
            IdentityAnchor.OperatorBinding binding =
                    operatorAnchor.resolveOperatorToken(operatorToken);
            if (binding == null) {
                throw OAuthException.invalidGrant(
                        "operator token is invalid, expired or already used");
            }
            return normalizedE164(binding.msisdn(), "operator token");
        }
        throw OAuthException.invalidGrant("sub must be tel:<E.164> or operatortoken:<token>");
    }

    private static String normalizedE164(String raw, String subjectType) {
        return RequestValidator.normalizeE164(raw).orElseThrow(() ->
                OAuthException.invalidGrant(subjectType + " subject is not a valid E.164 number"));
    }

    private static Response tokenResponse(String accessToken, Set<String> scopes) {
        return Response.ok(Map.of(
                "access_token", accessToken,
                "token_type", "Bearer",
                "expires_in", AccessTokenService.TOKEN_TTL_SECONDS,
                "scope", String.join(" ", scopes))).build();
    }

    private static Response error(OAuthException e) {
        LOG.warn("[SAS] /token rejected: {} ({})", e.error(), e.getMessage());
        return Response.status(e.httpStatus())
                .entity(new OAuthErrorResponse(e.error(), e.getMessage()))
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
