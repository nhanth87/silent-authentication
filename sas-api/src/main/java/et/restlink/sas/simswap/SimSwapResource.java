/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import et.restlink.sas.api.ApiAudit;
import et.restlink.sas.api.ApiCdrRecorder;
import et.restlink.sas.api.TenantRegistry;
import et.restlink.sas.oauth.AccessTokenService;
import et.restlink.sas.security.ReplayGuard;
import et.restlink.sas.security.RequestValidator;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * CAMARA SimSwap v2.1.0 northbound surface (spec server prefix
 * {@code {apiRoot}/sim-swap/v2}), served from the same read-only SIM-change
 * evidence the SAS Verifier already scores as {@code notSimSwapped}: MAP
 * SAI/PSI {@code lastUpdateLocation} on 2G/3G, a read-only Sh UDR/SNR read on
 * 4G/5G (TS 29.328/29.329). No AIR/AIA (never advances the AuC SQN), no
 * IDR/IDA (wrong direction for a read), no interconnect ATI (FS.11 Cat 1).
 *
 * <p><strong>Wire contract (r3.3 / v2.1.0)</strong>:</p>
 * <ul>
 *   <li>POST /sim-swap/v2/check {@code {"phoneNumber"?,"maxAge"?}} →
 *       {@code {"swapped":boolean}}. {@code maxAge} is hours, default
 *       {@value #DEFAULT_MAX_AGE_HOURS}, spec range 1..{@value #MAX_AGE_HOURS_LIMIT};
 *       outside it the request answers {@code 400 OUT_OF_RANGE} rather than
 *       being silently clamped.</li>
 *   <li>POST /sim-swap/v2/retrieve-date {@code {"phoneNumber"?}} →
 *       {@code {"latestSimChange":"<RFC 3339>"}}.</li>
 *   <li>Every error body is {@code {"status":int,"code":string,"message":string}}
 *       with the CAMARA codes {@code INVALID_ARGUMENT}/{@code OUT_OF_RANGE}
 *       (400), {@code UNAUTHENTICATED} (401), {@code PERMISSION_DENIED} (403),
 *       {@code IDENTIFIER_NOT_FOUND} (404), {@code MISSING_IDENTIFIER}/
 *       {@code UNNECESSARY_IDENTIFIER} (422) and {@code QUOTA_EXCEEDED} (429).
 *       {@code x-correlator} is echoed on every response.</li>
 * </ul>
 *
 * <p><strong>Identifier resolution</strong>: with token validation enabled the
 * subscriber comes from the user-number binding of the access token
 * ({@code phone_number}, else the custom {@code msisdn} claim) — a token
 * without a binding answers {@code 403 PERMISSION_DENIED}, and supplying an
 * explicit {@code phoneNumber} on top of it answers
 * {@code 422 UNNECESSARY_IDENTIFIER} (the server cannot compare the two). In
 * lab mode (validation disabled) the body {@code phoneNumber} is the 2-legged
 * identifier: absent → {@code 422 MISSING_IDENTIFIER}, malformed →
 * {@code 400 INVALID_ARGUMENT}.</p>
 *
 * <p><strong>Bearer-path security</strong> mirrors {@code /verify}: single-use
 * short-lived token (one completed call per {@code jti}, further use →
 * {@code 401}), one {@code x-correlator} per token key (a different correlator
 * is a replayed transaction → {@code 401}), per-endpoint scope
 * ({@value TokenValidator#SCOPE_SIM_SWAP_CHECK} /
 * {@value TokenValidator#SCOPE_SIM_SWAP_RETRIEVE_DATE}, family
 * {@code sim-swap} grants both), then the tenant + quota gate before any
 * evidence read. Unlike {@code /verify} there is no {@code amr} rule: the
 * SimSwap spec does not require mobile-network authentication evidence.</p>
 *
 * <p><strong>Fail-closed</strong>: no evidence for the identifier — unknown
 * subscriber, or no binding-age source wired (e.g. a live S6a/SWx transport
 * without a Sh UDR read) — answers {@code 404 IDENTIFIER_NOT_FOUND}. It never
 * answers {@code swapped:false}, which would be a silent "no fraud" claim.</p>
 *
 * <p><strong>Privacy</strong>: the MSISDN/IMSI never leaves the bank backend —
 * responses carry only the boolean or the timestamp, and logs mask the
 * number.</p>
 */
@Path("/sim-swap/v2")
public class SimSwapResource {

    private static final Logger LOG = LogManager.getLogger(SimSwapResource.class);

    /** CAMARA SimSwap default look-back window, in hours. */
    public static final int DEFAULT_MAX_AGE_HOURS = 240;

    /** CAMARA SimSwap maximum look-back window, in hours (spec {@code maximum}). */
    public static final int MAX_AGE_HOURS_LIMIT = 2400;

    private static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String CODE_OUT_OF_RANGE = "OUT_OF_RANGE";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_PERMISSION_DENIED = "PERMISSION_DENIED";
    private static final String CODE_IDENTIFIER_NOT_FOUND = "IDENTIFIER_NOT_FOUND";
    private static final String CODE_MISSING_IDENTIFIER = "MISSING_IDENTIFIER";
    private static final String CODE_UNNECESSARY_IDENTIFIER = "UNNECESSARY_IDENTIFIER";
    private static final String CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

    @Inject
    SimSwapQueryPort simSwapEvidence;

    @Inject
    TokenValidator tokenValidator;

    @Inject
    ReplayGuard replayGuard;

    @Inject
    AccessTokenService accessTokens;

    @Inject
    SasSecurityConfig securityConfig;

    /**
     * Tenant + quota gate. Defaults to a bare lab registry so plain unit
     * constructions behave as enforcement-off/unmetered; the container injects
     * the configured bean over it.
     */
    @Inject
    TenantRegistry tenants = new TenantRegistry();

    /** Container-provided request headers (X-Api-Key); null in unit tests. */
    @Inject
    HttpHeaders httpHeaders;

    @Inject
    ApiCdrRecorder cdr = ApiCdrRecorder.NOOP;

    /** CAMARA SimSwap v2.1.0 checkSimSwap. */
    @POST
    @Path("/check")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response check(SimSwapCheckRequest body,
                          @HeaderParam("x-correlator") String xCorrelator,
                          @HeaderParam("Authorization") String authorization) {
        String correlator = xCorrelator == null ? "" : xCorrelator;
        ApiAudit audit = ApiAudit.start("SIMSWAP", "SIMSWAP", correlator)
                .detail("action", "check");
        return audit(audit, doCheck(body, correlator, authorization, audit));
    }

    private Response doCheck(SimSwapCheckRequest body,
                             String correlator,
                             String authorization,
                             ApiAudit audit) {
        int maxAgeHours = DEFAULT_MAX_AGE_HOURS;
        Integer requestedMaxAge = body == null ? null : body.maxAge();
        if (requestedMaxAge != null) {
            if (requestedMaxAge < 1 || requestedMaxAge > MAX_AGE_HOURS_LIMIT) {
                return error(400, CODE_OUT_OF_RANGE,
                        "maxAge must be between 1 and " + MAX_AGE_HOURS_LIMIT + " hours",
                        correlator);
            }
            maxAgeHours = requestedMaxAge;
        }
        audit.detail("maxAgeHours", maxAgeHours);

        Identity identity = authorize(body == null ? null : body.phoneNumber(),
                TokenValidator.SCOPE_SIM_SWAP_CHECK, authorization, correlator);
        if (identity.rejection() != null) {
            return identity.rejection();
        }
        audit.msisdn(mask(identity.msisdn()));

        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            return error(401, CODE_UNAUTHENTICATED, "unknown X-Api-Key (no tenant)", correlator);
        }
        audit.tenant(tenant.tenantId());
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(), correlator);
        }

        Optional<Instant> lastSimChange = evidence(identity.msisdn());
        consume(identity);

        if (lastSimChange.isEmpty()) {
            audit.detail("evidence", "ABSENT");
            LOG.info("[SAS] /sim-swap/v2/check msisdn={} no evidence (fail-closed 404)",
                    mask(identity.msisdn()));
            return error(404, CODE_IDENTIFIER_NOT_FOUND,
                    "no SIM-change evidence for the requested identifier", correlator);
        }

        boolean swapped = Duration.between(lastSimChange.get(), Instant.now())
                .compareTo(Duration.ofHours(maxAgeHours)) <= 0;
        audit.detail("swapped", swapped);
        LOG.info("[SAS] /sim-swap/v2/check msisdn={} swapped={} maxAge={}h tenant={}",
                mask(identity.msisdn()), swapped, maxAgeHours, tenant.tenantId());
        return Response.ok(new CheckSimSwapInfo(swapped))
                .header("x-correlator", correlator)
                .build();
    }

    /** CAMARA SimSwap v2.1.0 retrieveSimSwapDate. */
    @POST
    @Path("/retrieve-date")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response retrieveDate(SimSwapDateRequest body,
                                 @HeaderParam("x-correlator") String xCorrelator,
                                 @HeaderParam("Authorization") String authorization) {
        String correlator = xCorrelator == null ? "" : xCorrelator;
        ApiAudit audit = ApiAudit.start("SIMSWAP", "SIMSWAP", correlator)
                .detail("action", "retrieve-date");
        return audit(audit, doRetrieveDate(body, correlator, authorization, audit));
    }

    private Response doRetrieveDate(SimSwapDateRequest body,
                                    String correlator,
                                    String authorization,
                                    ApiAudit audit) {
        Identity identity = authorize(body == null ? null : body.phoneNumber(),
                TokenValidator.SCOPE_SIM_SWAP_RETRIEVE_DATE, authorization, correlator);
        if (identity.rejection() != null) {
            return identity.rejection();
        }
        audit.msisdn(mask(identity.msisdn()));

        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            return error(401, CODE_UNAUTHENTICATED, "unknown X-Api-Key (no tenant)", correlator);
        }
        audit.tenant(tenant.tenantId());
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(), correlator);
        }

        Optional<Instant> lastSimChange = evidence(identity.msisdn());
        consume(identity);

        if (lastSimChange.isEmpty()) {
            audit.detail("evidence", "ABSENT");
            LOG.info("[SAS] /sim-swap/v2/retrieve-date msisdn={} no evidence (fail-closed 404)",
                    mask(identity.msisdn()));
            return error(404, CODE_IDENTIFIER_NOT_FOUND,
                    "no SIM-change evidence for the requested identifier", correlator);
        }

        audit.detail("result", "RETRIEVED");
        LOG.info("[SAS] /sim-swap/v2/retrieve-date msisdn={} answered tenant={}",
                mask(identity.msisdn()), tenant.tenantId());
        return Response.ok(SimSwapInfo.of(lastSimChange.get()))
                .header("x-correlator", correlator)
                .build();
    }

    // ---- helpers ----

    /** Resolved subscriber, the single-use token key, or the rejection to return. */
    private record Identity(String msisdn, String tokenKey, Response rejection) {

        static Identity reject(Response rejection) {
            return new Identity(null, null, rejection);
        }
    }

    /**
     * Token + identifier resolution shared by both operations: signature/claims,
     * per-endpoint scope, single-use and replay gates, then the subscriber —
     * from the token binding (3-legged) or the body (2-legged lab mode).
     */
    private Identity authorize(String bodyPhoneNumber,
                               String requiredScope,
                               String authorization,
                               String correlator) {
        TokenValidator.DetailedAuth auth = tokenValidator.validateDetailed(authorization);
        if (!auth.ok()) {
            return Identity.reject(error(401, CODE_UNAUTHENTICATED, auth.error(), correlator));
        }
        String tokenKey = auth.tokenKey();
        String requested = trimToNull(bodyPhoneNumber);

        if (!securityConfig.tokenValidationEnabled()) {
            // Lab / 2-legged: the body carries the identifier.
            if (requested == null) {
                return Identity.reject(error(422, CODE_MISSING_IDENTIFIER,
                        "phoneNumber is required when the access token carries no user binding",
                        correlator));
            }
            String msisdn = RequestValidator.normalizeE164(requested).orElse(null);
            if (msisdn == null) {
                return Identity.reject(error(400, CODE_INVALID_ARGUMENT,
                        "phoneNumber must be E.164 (+<digits>)", correlator));
            }
            return new Identity(msisdn, null, null);
        }

        if (!TokenValidator.hasScope(auth.scopes(), requiredScope)) {
            return Identity.reject(error(403, CODE_PERMISSION_DENIED,
                    "missing required scope: " + requiredScope, correlator));
        }
        String boundNumber = auth.boundNumber();
        if (boundNumber == null) {
            return Identity.reject(error(403, CODE_PERMISSION_DENIED,
                    "access token carries no user phone-number binding "
                            + "(phone_number/msisdn claim)", correlator));
        }
        if (requested != null) {
            // Spec: the server cannot compare the two identifications.
            return Identity.reject(error(422, CODE_UNNECESSARY_IDENTIFIER,
                    "the device is already identified by the access token", correlator));
        }
        if (replayGuard.isConsumed(tokenKey) || accessTokens.isConsumed(tokenKey)) {
            return Identity.reject(error(401, CODE_UNAUTHENTICATED,
                    "token already used (single-use)", correlator));
        }
        String replayError = replayGuard.checkReplay(tokenKey, correlator);
        if (replayError != null) {
            return Identity.reject(error(401, CODE_UNAUTHENTICATED, replayError, correlator));
        }
        return new Identity(boundNumber, tokenKey, null);
    }

    /** Evidence read — fail-closed (empty) when no port is wired. */
    private Optional<Instant> evidence(String msisdn) {
        if (simSwapEvidence == null) {
            LOG.error("[SAS] no SIM-change evidence source wired — failing closed");
            return Optional.empty();
        }
        try {
            Optional<Instant> last = simSwapEvidence.lastSimChange(msisdn);
            return last == null ? Optional.empty() : last;
        } catch (RuntimeException e) {
            LOG.error("[SAS] SIM-change evidence lookup failed — failing closed", e);
            return Optional.empty();
        }
    }

    /** The token has now driven one completed call — consume it. */
    private void consume(Identity identity) {
        if (identity.tokenKey() != null) {
            replayGuard.consume(identity.tokenKey());
            accessTokens.markConsumed(identity.tokenKey());
        }
    }

    /** X-Api-Key from the container headers; null-safe outside a container. */
    private String apiKeyHeader() {
        return httpHeaders == null ? null : httpHeaders.getHeaderString("X-Api-Key");
    }

    private Response audit(ApiAudit audit, Response response) {
        String errorCode = response.getEntity() instanceof CamaraError err ? err.code() : null;
        cdr.record(audit.complete(response.getStatus(), errorCode));
        return response;
    }

    /** Spec ErrorInfo: status+code+message are all required. */
    record CamaraError(int status, String code, String message) {
    }

    private static Response error(int status, String code, String message, String correlator) {
        return Response.status(status)
                .entity(new CamaraError(status, code, message))
                .header("x-correlator", correlator)
                .build();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Privacy: never log a full MSISDN. */
    static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) {
            return "***";
        }
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
