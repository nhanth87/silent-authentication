/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.api.dto.VerifyRequestDto;
import et.restlink.sas.api.dto.VerifyResponseDto;
import et.restlink.sas.bootstrap.SasBootstrap;
import et.restlink.sas.entitlement.EntitlementTokenService;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.security.OperatorTokenSupport;
import et.restlink.sas.security.ReplayGuard;
import et.restlink.sas.security.RequestValidator;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CAMARA NumberVerification (NV v2.1.0) northbound surface over the SAS
 * {@code /verify} flow. Fail-closed: any FALLBACK returns
 * {@code devicePhoneNumberVerified:false} — never a soft pass.
 *
 * <p><strong>Identity anchors</strong>: besides the normal OIDC bearer + amr
 * validation, this endpoint accepts an operator token (a signed SAS
 * entitlement token minted after EAP-AKA over Wi-Fi) via
 * {@code Authorization: Bearer operatortoken:<tk>} or — when
 * {@code sas.entitlement.ciba-enabled=true} — the {@code X-Sas-Operator-Token}
 * header. On that path the token binding becomes the claimed MSISDN with
 * {@code accessTech=WIFI}; normal bearer/amr/body checks are skipped and an
 * invalid/expired/replayed token answers {@code 401 INVALID_TOKEN}. The CAMARA
 * response contract is unchanged ({@code devicePhoneNumberVerified:boolean}).
 * The token is single-use.</p>
 *
 * <p><strong>Bearer-path security (token validation enabled)</strong>:</p>
 * <ol>
 *   <li>JWT signature/claims validated; the token key is the {@code jti}
 *       claim, else SHA-256 of the raw token.</li>
 *   <li>Per-endpoint scope: {@value TokenValidator#SCOPE_NUMBER_VERIFICATION_VERIFY}
 *       here, {@value TokenValidator#SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ}
 *       on GET /retrieve-phone-number (family/prefix match) — missing scope
 *       answers {@code 403 PERMISSION_DENIED}.</li>
 *   <li>amr: the JWT {@code amr} claim is preferred over the client
 *       {@code X-Sas-Amr} header; no amr evidence at all answers
 *       {@code 403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK}.</li>
 *   <li>Single use: one completed call per token key — any further use
 *       answers {@code 401 UNAUTHENTICATED}.</li>
 *   <li>Replay: a token key may only ever be paired with ONE x-correlator;
 *       the same key under a different correlator is a replayed transaction
 *       and answers {@code 401 UNAUTHENTICATED}. The same key+correlator is an
 *       idempotent retry served through the shared reqId path while the first
 *       call is in flight.</li>
 * </ol>
 *
 * <p>Lab mode ({@code sas.security.token-validation-enabled=false}) keeps the
 * presence-only P0 behaviour, but derives the request id from
 * {@code sha256(raw Authorization header | x-correlator)} so retries of an
 * identical request stay idempotent instead of minting a fresh UUID.</p>
 */
@Path("/")
public class VerifyResource {

    private static final Logger LOG = LogManager.getLogger(VerifyResource.class);

    private static final String CODE_NOT_MOBILE =
            "NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK";
    private static final String CODE_VALIDATION = "VALIDATION.Failed";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_PERMISSION_DENIED = "PERMISSION_DENIED";

    @Inject
    SasBootstrap bootstrap;

    @Inject
    TokenValidator tokenValidator;

    @Inject
    ReplayGuard replayGuard;

    @Inject
    OperatorTokenSupport operatorTokenSupport;

    @Inject
    SasSecurityConfig securityConfig;

    @POST
    @Path("/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verify(VerifyRequestDto body,
                           @HeaderParam("x-correlator") String xCorrelator,
                           @HeaderParam("Authorization") String authorization,
                           @HeaderParam("X-Sas-Amr") String amr,
                           @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                           @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                           @HeaderParam("X-Sas-Access-Tech") String accessTechHeader,
                           @HeaderParam("X-Sas-Operator-Token") String operatorTokenHeader) {
        String correlator = xCorrelator == null ? "" : xCorrelator;

        // Wi-Fi / CIBA path — operator token as identity anchor, checked before
        // the normal bearer/amr validation (fail-closed on any token failure).
        String operatorCandidate = operatorTokenSupport.extractCandidate(
                authorization, null, operatorTokenHeader);
        EntitlementTokenService.EntitlementRecord operatorBinding = null;
        if (operatorCandidate != null) {
            operatorBinding = operatorTokenSupport.resolve(operatorCandidate);
            if (operatorBinding == null || operatorBinding.msisdn() == null
                    || operatorBinding.msisdn().isBlank()) {
                return error(401, "INVALID_TOKEN",
                        "operator token is invalid, expired or already used", correlator);
            }
        }

        String claimed;
        String hashed;
        String srcIp = notBlank(srcIpHeader) ? srcIpHeader : "10.20.30.40";
        int srcPort = parseInt(srcPortHeader, 55555);
        AccessTech accessTech;

        boolean validationEnabled = securityConfig.tokenValidationEnabled();
        String tokenKey = null;

        if (operatorBinding != null) {
            // Identity anchor from the signed entitlement token; body phone
            // claims are ignored — the binding is authoritative on this path.
            claimed = operatorBinding.msisdn();
            hashed = null;
            accessTech = AccessTech.WIFI;
            LOG.info("[SAS] /verify operator-token anchor applied (eap={})",
                    operatorBinding.eapMethod());
        } else {
            // H14 — single-use, short-lived token, full claims validation.
            TokenValidator.DetailedAuth auth = tokenValidator.validateDetailed(authorization);
            if (!auth.ok()) {
                return error(401, CODE_UNAUTHENTICATED, auth.error(), correlator);
            }
            tokenKey = auth.tokenKey();

            if (validationEnabled) {
                // Per-endpoint CAMARA scope (family match on the granted scopes).
                if (!TokenValidator.hasScope(auth.scopes(),
                        TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY)) {
                    return error(403, CODE_PERMISSION_DENIED,
                            "missing required scope: "
                                    + TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY,
                            correlator);
                }
                // H14 — 403 when mobile-network authentication is not proven.
                String amrError = tokenValidator.resolveAmrError(auth.amrValues(), amr);
                if (amrError != null) {
                    return error(403, CODE_NOT_MOBILE, amrError, correlator);
                }
                // Single-use bearer token: one completed call per token key.
                if (replayGuard.isConsumed(tokenKey)) {
                    return error(401, CODE_UNAUTHENTICATED,
                            "token already used (single-use)", correlator);
                }
            } else {
                // Lab mode: legacy header-only amr leniency (P0 behaviour).
                String amrError = tokenValidator.validateAmr(amr);
                if (amrError != null) {
                    return error(403, CODE_NOT_MOBILE, amrError, correlator);
                }
            }

            boolean hasPhone = body != null && body.phoneNumber() != null
                    && !body.phoneNumber().isBlank();
            boolean hasHashed = body != null && body.hashedPhoneNumber() != null
                    && !body.hashedPhoneNumber().isBlank();
            if (hasPhone == hasHashed) {
                return error(400, CODE_VALIDATION,
                        "exactly one of phoneNumber / hashedPhoneNumber is required", correlator);
            }
            claimed = hasPhone ? body.phoneNumber().trim() : null;
            hashed = hasHashed ? body.hashedPhoneNumber().trim() : null;
            if (claimed != null && !RequestValidator.isE164(claimed)) {
                return error(400, CODE_VALIDATION,
                        "phoneNumber must be E.164 (+<digits>)", correlator);
            }
            if (hashed != null && !RequestValidator.isSha256Hex(hashed)) {
                return error(400, CODE_VALIDATION,
                        "hashedPhoneNumber must be a SHA-256 hex digest (64 hex chars)",
                        correlator);
            }

            // Network tuple — pilot: from headers (production: minted from the
            // CIBA / network-auth token during device onboarding). CGNAT-safe.
            accessTech = parseAccessTech(accessTechHeader);
        }

        long ts = System.currentTimeMillis();
        String tsError = replayGuard.checkTimestamp(ts);
        if (tsError != null) {
            return error(400, CODE_VALIDATION, tsError, correlator);
        }

        String reqId;
        if (tokenKey == null) {
            // Operator-token anchor: its own single-use consumption applies.
            reqId = UUID.randomUUID().toString();
        } else if (validationEnabled) {
            // Replay gate: first (tokenKey, correlator) pair wins.
            String replayError = replayGuard.checkReplay(tokenKey, correlator);
            if (replayError != null) {
                return error(401, CODE_UNAUTHENTICATED, replayError, correlator);
            }
            reqId = tokenKey;
        } else {
            // Lab idempotency: identical curl retries share one derived key.
            reqId = RequestValidator.deriveLabReqId(
                    RequestValidator.sha256Hex(authorization), correlator);
        }

        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                claimed, accessTech);

        VerifyResult result = awaitResult(event, reqId);

        if (validationEnabled && tokenKey != null) {
            // The token has now driven one completed call — consume it.
            replayGuard.consume(tokenKey);
        }

        LOG.info("[SAS] /verify reqId={} verified={} fallback={}",
                reqId, result.match(), result.fallbackReason());

        if (result.fallbackReason() != null) {
            return ok(new VerifyResponseDto(false), correlator);
        }

        boolean verified;
        if (claimed != null) {
            verified = result.msisdn() != null && claimed.equals(result.msisdn());
        } else {
            verified = result.msisdn() != null
                    ? sha256("+" + result.msisdn()).equalsIgnoreCase(hashed) : false;
        }
        return ok(new VerifyResponseDto(verified), correlator);
    }

    @GET
    @Path("/retrieve-phone-number")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retrievePhoneNumber(@HeaderParam("x-correlator") String xCorrelator,
                                        @HeaderParam("Authorization") String authorization,
                                        @HeaderParam("X-Sas-Amr") String amr,
                                        @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                                        @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                                        @HeaderParam("X-Sas-Access-Tech") String accessTechHeader) {
        // P2 missing item #7 — CAMARA NV device-phone-number:read scope path.
        // Requires the number-verification:device-phone-number:read scope.
        String correlator = xCorrelator == null ? "" : xCorrelator;

        TokenValidator.DetailedAuth auth = tokenValidator.validateDetailed(authorization);
        if (!auth.ok()) {
            return error(401, CODE_UNAUTHENTICATED, auth.error(), correlator);
        }
        String tokenKey = auth.tokenKey();
        boolean validationEnabled = securityConfig.tokenValidationEnabled();

        if (validationEnabled) {
            if (!TokenValidator.hasScope(auth.scopes(),
                    TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ)) {
                return error(403, CODE_PERMISSION_DENIED,
                        "missing required scope: "
                                + TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ,
                        correlator);
            }
            String amrError = tokenValidator.resolveAmrError(auth.amrValues(), amr);
            if (amrError != null) {
                return error(403, CODE_NOT_MOBILE, amrError, correlator);
            }
            if (replayGuard.isConsumed(tokenKey)) {
                return error(401, CODE_UNAUTHENTICATED,
                        "token already used (single-use)", correlator);
            }
            String replayError = replayGuard.checkReplay(tokenKey, correlator);
            if (replayError != null) {
                return error(401, CODE_UNAUTHENTICATED, replayError, correlator);
            }
        }

        String srcIp = notBlank(srcIpHeader) ? srcIpHeader : "10.20.30.40";
        int srcPort = parseInt(srcPortHeader, 55555);
        AccessTech accessTech = parseAccessTech(accessTechHeader);

        long ts = System.currentTimeMillis();
        String tsError = replayGuard.checkTimestamp(ts);
        if (tsError != null) {
            return error(400, CODE_VALIDATION, tsError, correlator);
        }

        // Number-discovery mode: no claimed MSISDN. Namespaced key so /verify
        // and /retrieve-phone-number never share a reqId for one token.
        String reqId = RequestValidator.deriveRetrieveReqId(tokenKey, correlator);
        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                null, accessTech);

        VerifyResult result = awaitResult(event, reqId);

        if (validationEnabled) {
            replayGuard.consume(tokenKey);
        }

        if (result.fallbackReason() != null || result.msisdn() == null) {
            return error(403, CODE_NOT_MOBILE,
                    "unable to resolve device phone number", correlator);
        }

        LOG.info("[SAS] /retrieve-phone-number reqId={} resolved", reqId);
        return Response.ok(Map.of("devicePhoneNumber", result.msisdn()))
                .header("x-correlator", correlator)
                .build();
    }

    // ---- helpers ----

    /** Submit to the SLEE router under the total SAS budget; fail-closed. */
    private VerifyResult awaitResult(VerifyRequestEvent event, String reqId) {
        try {
            return bootstrap.submit(event).get(SasTimeouts.TOTAL_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return VerifyResult.fallback(reqId, FallbackReason.SAS_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return VerifyResult.fallback(reqId, FallbackReason.SAS_TIMEOUT);
        } catch (ExecutionException ex) {
            return VerifyResult.fallback(reqId, FallbackReason.VERIFY_ERROR);
        } finally {
            bootstrap.release(reqId);
        }
    }

    private static Response ok(VerifyResponseDto dto, String correlator) {
        return Response.ok(dto).header("x-correlator", correlator).build();
    }

    private static Response error(int status, String code, String message, String correlator) {
        return Response.status(status)
                .entity(Map.of("code", code, "message", message))
                .header("x-correlator", correlator)
                .build();
    }

    private static AccessTech parseAccessTech(String raw) {
        if (raw == null || raw.isBlank()) {
            return AccessTech.GS_2G3G;
        }
        try {
            return AccessTech.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AccessTech.GS_2G3G;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return dflt;
        }
    }

    private static String sha256(String value) {
        return RequestValidator.sha256Hex(value);
    }
}
