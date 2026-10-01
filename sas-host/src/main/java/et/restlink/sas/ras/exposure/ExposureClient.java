/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.exposure;

import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureJson;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.ProblemDetails;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The gateway's client of the subscriber exposure API (layered ADR §4) — the RA delegate
 * of {@code exposure-ra}, and the gateway's only way to the subscriber.
 *
 * <p>Every call completes with a value or a problem, never exceptionally. Anything that
 * is not a 200 carrying a body that parses under the strict contract — a problem answer,
 * a refused connection, a timeout, a malformed answer — is a problem, so the caller fails
 * closed. Client authentication (mTLS + client credentials) arrives in step M4; until
 * then prod preflight refuses this client unless it points at an HTTPS ECS.</p>
 */
public final class ExposureClient implements SubscriberService {

    private static final Logger LOG = LogManager.getLogger(ExposureClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1000);
    /** Network grace on top of the budget handed to the ECS. */
    private static final long WIRE_GRACE_MS = 150L;

    private final URI base;
    private final HttpClient http;

    /** @param baseUrl the exposure API root, e.g. {@code https://ecs:8095/sas-subscriber-exposure/v1} */
    public ExposureClient(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.base = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI baseUri() {
        return base;
    }

    @Override
    public CompletableFuture<ExposureOutcome<UeIdInfo>> retrieveUeId(ExposureContext ctx, UeIdReq req) {
        return post(ExposureApi.UE_ID_PATH, ctx, req, UeIdInfo.class);
    }

    @Override
    public CompletableFuture<ExposureOutcome<MonitoringEventReports>> monitor(
            ExposureContext ctx, String scsAsId, MonitoringEventSubscription req) {
        String path = ExposureApi.MONITORING_PATH.replace("{scsAsId}",
                URLEncoder.encode(scsAsId, StandardCharsets.UTF_8));
        return post(path, ctx, req, MonitoringEventReports.class);
    }

    @Override
    public CompletableFuture<ExposureOutcome<SimChangeInfo>> retrieveSimChange(
            ExposureContext ctx, SimChangeReq req) {
        return post(ExposureApi.SIM_CHANGE_PATH, ctx, req, SimChangeInfo.class);
    }

    @Override
    public CompletableFuture<ExposureOutcome<SubscriberVerificationResult>> redeemOperatorToken(
            ExposureContext ctx, OperatorTokenRedeemReq req) {
        return post(ExposureApi.REDEEM_PATH, ctx, req, SubscriberVerificationResult.class);
    }

    private <T> CompletableFuture<ExposureOutcome<T>> post(String path, ExposureContext ctx, Object body,
                                                           Class<T> answer) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofMillis(ctx.budgetMs() + WIRE_GRACE_MS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, application/problem+json")
                    .header(ExposureApi.HEADER_REQUEST_ID, ctx.requestId())
                    .header(ExposureApi.HEADER_BUDGET_MS, Integer.toString(ctx.budgetMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(ExposureJson.write(body)))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(ExposureOutcome.problem(
                    ExposureCause.INTERNAL_ERROR, "invalid exposure request"));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> this.<T>interpret(path, resp.statusCode(), resp.body(), answer))
                .exceptionally(error -> transportProblem(path, error));
    }

    <T> ExposureOutcome<T> interpret(String path, int status, String body, Class<T> answer) {
        if (status == 200) {
            ExposureOutcome<T> parsed = ExposureJson.read(body, answer);
            if (parsed.ok()) {
                return parsed;
            }
            LOG.warn("[exposure-client] {} 200 with a body outside the contract — fail closed", path);
            return ExposureOutcome.problem(ExposureCause.NETWORK_ERROR, "answer outside the contract");
        }
        ExposureOutcome<ProblemDetails> problem = ExposureJson.read(body, ProblemDetails.class);
        if (problem.ok() && problem.value().cause() != null) {
            return ExposureOutcome.problem(problem.value());
        }
        LOG.warn("[exposure-client] {} answered {} without problem details", path, status);
        return ExposureOutcome.problem(status == 404 ? ExposureCause.TRANSPORT_UNAVAILABLE
                : status == 401 || status == 403 ? ExposureCause.CLIENT_UNAUTHENTICATED
                : ExposureCause.NETWORK_ERROR, "HTTP " + status);
    }

    private static <T> ExposureOutcome<T> transportProblem(String path, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
            LOG.warn("[exposure-client] {} timed out", path);
            return ExposureOutcome.problem(ExposureCause.NETWORK_TIMEOUT, "entitlement server did not answer");
        }
        LOG.warn("[exposure-client] {} unreachable: {}", path, cause.getClass().getSimpleName());
        return ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE,
                cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                        ? "entitlement server unreachable" : "exposure transport failed");
    }
}
