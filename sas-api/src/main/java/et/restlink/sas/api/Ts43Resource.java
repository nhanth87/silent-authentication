/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import et.restlink.sas.events.Ts43RequestEvent;
import et.restlink.sas.model.Ts43Result;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TS.43 entitlement surface, {@code /ts43} — the EAP-AKA hop a Wi-Fi device makes
 * against the operator's entitlement server (GSMA TS.43 §6.1, RFC 4187 / RFC 9048
 * on the wire).
 *
 * <p><strong>Wire contract</strong>:</p>
 * <ul>
 *   <li>{@code GET /ts43/challenge?imsi=&msisdn=} →
 *       {@code {"reqId":..,"outcome":"CHALLENGE","challenge":"<base64 EAP-Request>","method":"EAP-AKA"}}.
 *       {@code msisdn} is optional and is only meaningful to a number-driven
 *       network source (MAP {@code SendIMSI}); a source that discovers the number
 *       itself ignores it.</li>
 *   <li>{@code POST /ts43/respond} {@code {"reqId":..,"imsi":..,"msisdn":..,"response":"<base64 EAP-Response>"}} →
 *       {@code {"reqId":..,"outcome":"SUCCESS","token":..,"expiresIn":..}} or
 *       {@code {"outcome":"FAILURE","message":..}}.</li>
 *   <li>Errors are {@code {"code":..,"message":..}} with {@code INVALID_ARGUMENT}
 *       (400), {@code UNAUTHENTICATED} (401) and {@code ENTITLEMENT_UNAVAILABLE}
 *       (503).</li>
 * </ul>
 *
 * <p><strong>This class owns no state and no transports.</strong> It mints the
 * {@code reqId} that identifies the SLEE activity, submits the event, and awaits
 * the terminal answer. The EAP session, the vector fetch and the number binding all
 * live inside the container behind {@link SasEntitlementEngine} (H24 —
 * {@code slee_boundary}).</p>
 *
 * <p><strong>Authentication.</strong> The UE authenticates the <em>server</em>, not
 * the other way round, so {@code /ts43} is open to the device in the same way EAP is
 * open on a Wi-Fi network: it is only safe because a request can produce nothing but a
 * Challenge, and an answer only becomes a token after {@code AT_MAC} +
 * {@code AT_RES} are verified against the network's own vector. Rate limiting and
 * CAP/AAA-level abuse controls are the operator's, not this resource's. Machine
 * consumers that redeem a token (the bank backend) do go through
 * {@code /entitlement/exchange}, which requires an API key.</p>
 *
 * <p><strong>Privacy (H8)</strong>: an IMSI travels in the request — it is the SIM
 * identity being proved — but it is never echoed in a response. The IMSI stays in the
 * SAS and inside the entitlement token.</p>
 */
@Path("/ts43")
public class Ts43Resource {

    private static final Logger LOG = LogManager.getLogger(Ts43Resource.class);

    /** Whole-surface ceiling; the SLEE FSM's own budgets are far shorter. */
    private static final long TOTAL_BUDGET_MS = 5_000L;

    @Inject
    SasEntitlementEngine engine;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChallengeResponse(String reqId, String outcome, String challenge,
                                    String method) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespondRequest(String reqId, String imsi, String msisdn, String response) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespondResponse(String reqId, String outcome, String token, Long expiresIn,
                                  String message) {}

    @GET
    @Path("/challenge")
    @Produces(MediaType.APPLICATION_JSON)
    public Response challenge(@QueryParam("imsi") String imsi,
                              @QueryParam("msisdn") String msisdn) {
        if (isBlank(imsi)) {
            return error(400, "INVALID_ARGUMENT", "imsi is required");
        }
        String reqId = "ts43-" + UUID.randomUUID();
        Ts43Result result = await(Ts43RequestEvent.challenge(reqId, imsi.trim(), msisdn));
        if (result == null) {
            return error(503, "ENTITLEMENT_UNAVAILABLE", "entitlement service did not answer");
        }
        if (result.outcome() != Ts43Result.Outcome.CHALLENGE) {
            // A CHALLENGE request can only fail; never upgrade it into a soft pass.
            return error(503, "ENTITLEMENT_UNAVAILABLE",
                    result.message() == null ? "no challenge available" : result.message());
        }
        LOG.info("[ts43] challenge issued reqId={}", reqId);
        return Response.ok(new ChallengeResponse(reqId, result.outcome().name(),
                result.challengeB64(), "EAP-AKA")).build();
    }

    @POST
    @Path("/respond")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response respond(RespondRequest body) {
        if (body == null || isBlank(body.reqId()) || isBlank(body.imsi())
                || isBlank(body.response())) {
            return error(400, "INVALID_ARGUMENT", "reqId, imsi and response are required");
        }
        Ts43Result result = await(Ts43RequestEvent.respond(body.reqId().trim(),
                body.imsi().trim(), body.msisdn(), body.response().trim()));
        if (result == null) {
            return error(503, "ENTITLEMENT_UNAVAILABLE", "entitlement service did not answer");
        }
        if (result.outcome() == Ts43Result.Outcome.SUCCESS) {
            LOG.info("[ts43] peer authenticated reqId={}", body.reqId());
            return Response.ok(new RespondResponse(result.reqId(), "SUCCESS",
                    result.token(), result.expiresInSeconds(), null)).build();
        }
        if (result.outcome() == Ts43Result.Outcome.CHALLENGE) {
            // Resync: the peer sent AT_AUTS and the SBB fetched a fresh vector.
            return Response.ok(new RespondResponse(result.reqId(), "CHALLENGE",
                    null, null, result.message())).build();
        }
        return error(401, "EAP_FAILED", result.message() == null ? "authentication failed"
                : result.message());
    }

    private Ts43Result await(Ts43RequestEvent evt) {
        try {
            return engine.submitTs43(evt).get(TOTAL_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            LOG.warn("[ts43] engine budget exhausted reqId={}", evt.reqId());
            engine.releaseTs43(evt.reqId());
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            engine.releaseTs43(evt.reqId());
            return null;
        } catch (ExecutionException ee) {
            LOG.warn("[ts43] engine failed reqId={}", evt.reqId(), ee.getCause());
            engine.releaseTs43(evt.reqId());
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status)
                .entity(Map.of("code", code, "message", message))
                .build();
    }
}