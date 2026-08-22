/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * TS.43 entitlement token REST surface (P2 missing item #3).
 *
 * <ul>
 *   <li>{@code POST /entitlement/issue} — called by the 3GPP AAA integration
 *       after successful EAP-AKA; issues a temporary entitlement token.</li>
 *   <li>{@code POST /entitlement/exchange} — called by the bank backend to
 *       exchange the entitlement token for the bound MSISDN (used to drive
 *       the Wi-Fi {@code /verify} path or CIBA {@code login_hint}).</li>
 *   <li>{@code GET /entitlement/status} — health check.</li>
 * </ul>
 *
 * <p>Privacy (H8): the exchange endpoint returns the MSISDN only to the
 * authenticated bank backend (mTLS + token), never to the mobile app.</p>
 */
@Path("/entitlement")
public class EntitlementResource {

    private static final Logger LOG = LogManager.getLogger(EntitlementResource.class);

    @Inject
    EntitlementTokenService tokenService;

    @Inject
    EntitlementConfig config;

    public record IssueRequest(String msisdn, String imsi, String eapMethod) {}
    public record IssueResponse(String token, long expiresInSeconds) {}
    public record ExchangeRequest(String token) {}
    public record ExchangeResponse(String msisdn, String imsi, String eapMethod, boolean valid) {}

    @POST
    @Path("/issue")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response issue(IssueRequest body) {
        if (!config.enabled()) {
            return error(503, "ENTITLEMENT_DISABLED", "Entitlement service is disabled");
        }
        if (body == null || body.msisdn() == null || body.msisdn().isBlank()) {
            return error(400, "INVALID_REQUEST", "msisdn is required");
        }
        String eapMethod = body.eapMethod() != null ? body.eapMethod() : "EAP-AKA";
        String token = tokenService.issueToken(body.msisdn(), body.imsi(), eapMethod);
        return Response.ok(new IssueResponse(token, config.tokenTtlSeconds())).build();
    }

    @POST
    @Path("/exchange")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response exchange(ExchangeRequest body) {
        if (!config.enabled()) {
            return error(503, "ENTITLEMENT_DISABLED", "Entitlement service is disabled");
        }
        if (body == null || body.token() == null || body.token().isBlank()) {
            return error(400, "INVALID_REQUEST", "token is required");
        }
        EntitlementTokenService.EntitlementRecord record = tokenService.exchange(body.token());
        if (record == null) {
            return error(401, "INVALID_TOKEN", "Entitlement token is invalid or expired");
        }
        return Response.ok(new ExchangeResponse(
                record.msisdn(), record.imsi(), record.eapMethod(), true)).build();
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        return Response.ok(Map.of(
                "enabled", config.enabled(),
                "activeTokens", tokenService.activeTokenCount(),
                "cibaEnabled", config.cibaEnabled()
        )).build();
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status)
                .entity(Map.of("code", code, "message", message))
                .build();
    }
}
