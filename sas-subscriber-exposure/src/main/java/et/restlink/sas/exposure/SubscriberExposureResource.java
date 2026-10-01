/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.exposure;

import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureJson;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.ExposureRequestValidator;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.ProblemDetails;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.UeIdReq;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The subscriber exposure API ({@code docs/design/openapi/sas-subscriber-exposure-v1.yaml}).
 * Caller: sas-camara only. Validates headers and body against the contract, hands the
 * call to {@link SubscriberService} (the SLEE bridge) and waits within the caller's budget.
 * It owns no signalling and no activity state (gate H24).
 *
 * <p>Client authentication (mTLS + client credentials) arrives in migration step M4;
 * until then the surface is off unless {@code sas.exposure.enabled=true} (lab only, prod
 * preflight refuses it).</p>
 */
@Path(ExposureApi.BASE_PATH)
public class SubscriberExposureResource {

    private static final Logger LOG = LogManager.getLogger(SubscriberExposureResource.class);
    private static final String PROBLEM_JSON = "application/problem+json";
    /** Grace on top of the caller's budget for the answer to travel back. */
    private static final long ANSWER_GRACE_MS = 250L;

    @Inject
    SubscriberService service;

    @Inject
    ExposureConfig config;

    @POST
    @Path(ExposureApi.UE_ID_PATH)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, PROBLEM_JSON})
    public Response retrieveUeId(@HeaderParam(ExposureApi.HEADER_REQUEST_ID) String requestId,
                                 @HeaderParam(ExposureApi.HEADER_BUDGET_MS) String budgetMs,
                                 String body) {
        return handle("ue-id", requestId, budgetMs, body, UeIdReq.class,
                ExposureRequestValidator::ueId, service::retrieveUeId);
    }

    @POST
    @Path(ExposureApi.MONITORING_PATH)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, PROBLEM_JSON})
    public Response monitor(@PathParam("scsAsId") String scsAsId,
                            @HeaderParam(ExposureApi.HEADER_REQUEST_ID) String requestId,
                            @HeaderParam(ExposureApi.HEADER_BUDGET_MS) String budgetMs,
                            String body) {
        return handle("monitoring", requestId, budgetMs, body, MonitoringEventSubscription.class,
                req -> ExposureRequestValidator.monitoring(scsAsId, req),
                (ctx, req) -> service.monitor(ctx, scsAsId, req));
    }

    @POST
    @Path(ExposureApi.SIM_CHANGE_PATH)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, PROBLEM_JSON})
    public Response retrieveSimChange(@HeaderParam(ExposureApi.HEADER_REQUEST_ID) String requestId,
                                      @HeaderParam(ExposureApi.HEADER_BUDGET_MS) String budgetMs,
                                      String body) {
        return handle("sim-change", requestId, budgetMs, body, SimChangeReq.class,
                ExposureRequestValidator::simChange, service::retrieveSimChange);
    }

    @POST
    @Path(ExposureApi.REDEEM_PATH)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, PROBLEM_JSON})
    public Response redeemOperatorToken(@HeaderParam(ExposureApi.HEADER_REQUEST_ID) String requestId,
                                        @HeaderParam(ExposureApi.HEADER_BUDGET_MS) String budgetMs,
                                        String body) {
        return handle("operator-token", requestId, budgetMs, body, OperatorTokenRedeemReq.class,
                ExposureRequestValidator::redeem, service::redeemOperatorToken);
    }

    private <Q, A> Response handle(String operation, String requestId, String budgetMs, String body,
                                   Class<Q> type, Function<Q, Optional<ProblemDetails>> rule,
                                   BiFunction<ExposureContext, Q, CompletableFuture<ExposureOutcome<A>>> call) {
        if (!config.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Optional<ProblemDetails> bad = ExposureRequestValidator.headers(requestId, budgetMs);
        if (bad.isPresent()) {
            return problem(bad.get());
        }
        ExposureOutcome<Q> parsed = ExposureJson.read(body, type);
        if (!parsed.ok()) {
            return problem(parsed.problem());
        }
        bad = rule.apply(parsed.value());
        if (bad.isPresent()) {
            return problem(bad.get());
        }
        ExposureContext ctx = new ExposureContext(requestId, Integer.parseInt(budgetMs.trim()));
        ExposureOutcome<A> outcome;
        try {
            outcome = call.apply(ctx, parsed.value())
                    .get(ctx.budgetMs() + ANSWER_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("[exposure] {} not answered within budget requestId={}", operation, requestId);
            return problem(ExposureCause.NETWORK_TIMEOUT.problem("no answer within the budget"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem(ExposureCause.INTERNAL_ERROR.problem("interrupted"));
        } catch (ExecutionException e) {
            LOG.warn("[exposure] {} failed requestId={}", operation, requestId, e.getCause());
            return problem(ExposureCause.INTERNAL_ERROR.problem("subscriber service failed"));
        }
        if (!outcome.ok()) {
            LOG.info("[exposure] {} requestId={} -> {} {}", operation, requestId,
                    outcome.problem().status(), outcome.cause());
            return problem(outcome.problem());
        }
        LOG.info("[exposure] {} requestId={} -> 200", operation, requestId);
        return Response.ok(ExposureJson.write(outcome.value()), MediaType.APPLICATION_JSON).build();
    }

    private static Response problem(ProblemDetails p) {
        int status = p.status() == null ? 500 : p.status();
        return Response.status(status).type(PROBLEM_JSON).entity(ExposureJson.write(p)).build();
    }
}
