/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * CIBA token endpoint ({@code POST /token}, CAMARA ICM §CIBA flow): exchanges
 * {@code grant_type=urn:openid:params:grant-type:ciba} + {@code auth_req_id}
 * for the user-bound access token. The pending binding is consumed atomically,
 * so one {@code auth_req_id} yields exactly ONE token; unknown ids answer
 * {@code invalid_grant}, expired ones {@code expired_token}.
 */
@Path("/token")
@Produces(MediaType.APPLICATION_JSON)
public class TokenResource {

    private static final Logger LOG = LogManager.getLogger(TokenResource.class);

    /** OpenID CIBA grant type (Backchannel Authentication Core 1.0 §7). */
    public static final String CIBA_GRANT_TYPE = "urn:openid:params:grant-type:ciba";

    @Inject
    AuthorizationRequestService authRequests;

    @Inject
    AccessTokenService accessTokens;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response token(@FormParam("grant_type") String grantType,
                          @FormParam("auth_req_id") String authReqId) {
        if (grantType == null || !CIBA_GRANT_TYPE.equals(grantType.trim())) {
            return error(400, "unsupported_grant_type",
                    "grant_type must be " + CIBA_GRANT_TYPE);
        }
        AuthorizationRequestService.ConsumeResult consumed = authRequests.consume(authReqId);
        if (consumed.binding() == null) {
            return consumed.knownExpired()
                    ? error(400, "expired_token", "auth_req_id has expired")
                    : error(400, "invalid_grant",
                            "auth_req_id is invalid or was already exchanged");
        }
        try {
            String accessToken = accessTokens.issue(consumed.binding());
            LOG.info("[SAS] /token granted for {}", consumed.binding().authReqId());
            return Response.ok(Map.of(
                    "access_token", accessToken,
                    "token_type", "Bearer",
                    "expires_in", AccessTokenService.TOKEN_TTL_SECONDS,
                    "scope", String.join(" ", consumed.binding().scopes()))).build();
        } catch (IllegalStateException e) {
            LOG.error("[SAS] /token issuance failed closed: {}", e.getMessage());
            return error(500, "server_error", "token issuance unavailable");
        }
    }

    private static Response error(int status, String error, String description) {
        return Response.status(status)
                .entity(new OAuthErrorResponse(error, description))
                .build();
    }
}
