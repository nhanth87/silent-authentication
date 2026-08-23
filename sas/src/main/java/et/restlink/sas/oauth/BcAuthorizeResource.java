/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

/**
 * CIBA back-channel authentication endpoint ({@code POST /bc-authorize},
 * CAMARA ICM §CIBA flow): resolves the identity anchor —
 * {@code login_hint=operatortoken:<tk>} or the cellular tuple from the
 * {@code X-Sas-Src-Ip}/{@code X-Sas-Src-Port} headers — and answers
 * {@code {"auth_req_id":...,"expires_in":120}} or an OAuth error body.
 * Accepts form-urlencoded natively plus a JSON-body fallback.
 */
@Path("/bc-authorize")
@Produces(MediaType.APPLICATION_JSON)
public class BcAuthorizeResource {

    private static final Logger LOG = LogManager.getLogger(BcAuthorizeResource.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String HEADER_SRC_IP = "X-Sas-Src-Ip";
    static final String HEADER_SRC_PORT = "X-Sas-Src-Port";

    @Inject
    AuthorizationRequestService authRequests;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response authorize(@FormParam("login_hint") String loginHint,
                              @FormParam("scope") String scope,
                              @Context HttpHeaders headers) {
        return doAuthorize(loginHint, scope,
                header(headers, HEADER_SRC_IP), header(headers, HEADER_SRC_PORT));
    }

    /** JSON-body fallback with the same fields as the form contract. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response authorizeJson(String body, @Context HttpHeaders headers) {
        String loginHint = null;
        String scope = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(body);
                loginHint = textOrNull(node, "login_hint");
                scope = textOrNull(node, "scope");
            } catch (IOException e) {
                return error(CibaException.invalidRequest("malformed JSON body"));
            }
        }
        return doAuthorize(loginHint, scope,
                header(headers, HEADER_SRC_IP), header(headers, HEADER_SRC_PORT));
    }

    // ---- shared handler ----

    private Response doAuthorize(String loginHint, String scope, String srcIp, String srcPortRaw) {
        int srcPort = parsePort(srcPortRaw);
        try {
            AuthorizationRequestService.AuthRequest auth =
                    authRequests.start(loginHint, srcIp, srcPort, scope);
            LOG.info("[SAS] /bc-authorize issued {} (expires_in={}s)",
                    auth.authReqId(), auth.expiresInSeconds());
            return Response.ok(Map.of(
                    "auth_req_id", auth.authReqId(),
                    "expires_in", auth.expiresInSeconds())).build();
        } catch (CibaException e) {
            return error(e);
        }
    }

    private static Response error(CibaException e) {
        LOG.warn("[SAS] /bc-authorize rejected: {} ({})", e.error(), e.getMessage());
        return Response.status(e.httpStatus())
                .entity(new OAuthErrorResponse(e.error(), e.getMessage()))
                .build();
    }

    private static String header(HttpHeaders headers, String name) {
        return headers == null ? null : headers.getHeaderString(name);
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }
}
