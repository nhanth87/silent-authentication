/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.api.dto.VerifyRequestDto;
import et.restlink.sas.api.dto.VerifyResponseDto;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.oauth.IdentityAnchor;
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
import jakarta.ws.rs.core.HttpHeaders;
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
 * {@code /verify} flow, served under the spec server prefix
 * {@code /number-verification/v2} (F1/F7); the root-level lab aliases are
 * deprecated but fully equivalent. Fail-closed: any FALLBACK returns
 * {@code devicePhoneNumberVerified:false} — never a soft pass.
 *
 * <p><strong>Wire contract (r3.2)</strong>:</p>
 * <ul>
 *   <li>POST {apiRoot}/number-verification/v2/verify →
 *       {@code {"devicePhoneNumberVerified":boolean}}.</li>
 *   <li>GET {apiRoot}/number-verification/v2/device-phone-number →
 *       {@code {"devicePhoneNumber":"+E164"}}.</li>
 *   <li>Every error body is {@code {"status":int,"code":string,"message":string}}
 *       with codes {@code INVALID_ARGUMENT} (400), {@code UNAUTHENTICATED}
 *       (401), {@code PERMISSION_DENIED} or
 *       {@code NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK}
 *       (403) — F3.</li>
 *   <li>The assurance snapshot ({@code reqId}/{@code decision}/{@code
 *       assurance}/{@code fallbackReason}) is opt-in: header
 *       {@code X-Sas-Assurance-Detail: true} or config
 *       {@code sas.api.assurance-detail-enabled=true} — F2. The risk class
 *       moved out of the request body into {@code X-Sas-Risk-Class}
 *       (LOGIN|TRANSFER|HIGH_VALUE, case-insensitive); unknown body
 *       properties are ignored, never parsed.</li>
 * </ul>
 *
 * <p><strong>User-bound token (F4)</strong>: when token validation is enabled,
 * the bearer JWT must carry a user-number binding — the {@code phone_number}
 * claim or the custom {@code msisdn} claim (normalized E.164). The verified
 * boolean is then
 * {@code networkEvidencePass && normalize(claimed) == normalize(bound)}; a
 * token without a usable binding answers
 * {@code 403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK}.
 * On the hashed path both sides compare as sha256 of the normalized
 * "+E164" string. Lab mode (validation disabled) keeps the P0 behaviour with
 * no binding requirement — only normalization is applied; the v2-compatibility
 * claim therefore requires validation-enabled=true.</p>
 *
 * <p><strong>Identity anchors</strong>: besides the normal OIDC bearer + amr
 * validation, this endpoint accepts an operator token (a signed SAS
 * entitlement token minted after EAP-AKA over Wi-Fi) via
 * {@code Authorization: Bearer operatortoken:<tk>} or — when
 * {@code sas.entitlement.ciba-enabled=true} — the {@code X-Sas-Operator-Token}
 * header. On that path the token binding becomes the claimed MSISDN with
 * {@code accessTech=WIFI}; normal bearer/amr/body checks are skipped and an
 * invalid/expired/replayed token answers {@code 401 UNAUTHENTICATED}. The
 * token is single-use. The {@code operatortoken:} direct-bearer form and the
 * entitlement endpoints are Restlink extensions (TS.43 track), not part of
 * the CAMARA contract.</p>
 *
 * <p><strong>Bearer-path security (token validation enabled)</strong>:</p>
 * <ol>
 *   <li>JWT signature/claims validated; token lifetime capped at 300 s with
 *       {@code iat} required (F5); the token key is the {@code jti}
 *       claim, else SHA-256 of the raw token.</li>
 *   <li>Per-endpoint scope: {@value TokenValidator#SCOPE_NUMBER_VERIFICATION_VERIFY}
 *       here, {@value TokenValidator#SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ}
 *       on GET /device-phone-number (family/prefix match) — missing scope
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
    private static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_PERMISSION_DENIED = "PERMISSION_DENIED";
    private static final String CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

    @Inject
    SasVerifyEngine bootstrap;

    @Inject
    TokenValidator tokenValidator;

    @Inject
    ReplayGuard replayGuard;

    @Inject
    et.restlink.sas.oauth.AccessTokenService accessTokens;

    @Inject
    IdentityAnchor identityAnchor;

    @Inject
    SasSecurityConfig securityConfig;

    @Inject
    ApiTogglesConfig apiToggles;

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

    // ---- CAMARA-pure primary endpoints (/number-verification/v2) ----

    /** CAMARA NV v2.1.0 phoneNumberVerify (primary path). */
    @POST
    @Path("/number-verification/v2/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verifyV2(VerifyRequestDto body,
                             @HeaderParam("x-correlator") String xCorrelator,
                             @HeaderParam("Authorization") String authorization,
                             @HeaderParam("X-Sas-Amr") String amr,
                             @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                             @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                             @HeaderParam("X-Sas-Access-Tech") String accessTechHeader,
                             @HeaderParam("X-Sas-Operator-Token") String operatorTokenHeader,
                             @HeaderParam("X-Sas-Risk-Class") String riskClassHeader,
                             @HeaderParam("X-Sas-Assurance-Detail") String assuranceDetailHeader) {
        return doVerify(body, xCorrelator, authorization, amr, srcIpHeader,
                srcPortHeader, accessTechHeader, operatorTokenHeader,
                riskClassHeader, assuranceDetailHeader);
    }

    /** CAMARA NV v2.1.0 phoneNumberShare (spec-correct name, primary path). */
    @GET
    @Path("/number-verification/v2/device-phone-number")
    @Produces(MediaType.APPLICATION_JSON)
    public Response devicePhoneNumber(@HeaderParam("x-correlator") String xCorrelator,
                                      @HeaderParam("Authorization") String authorization,
                                      @HeaderParam("X-Sas-Amr") String amr,
                                      @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                                      @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                                      @HeaderParam("X-Sas-Access-Tech") String accessTechHeader) {
        return doShare(xCorrelator, authorization, amr, srcIpHeader,
                srcPortHeader, accessTechHeader);
    }

    // ---- deprecated lab aliases (same handlers, legacy paths) ----

    /**
     * @deprecated lab alias for pilot banks — use
     *             {@code POST /number-verification/v2/verify}; this root path
     *             is not part of the CAMARA server prefix.
     */
    @Deprecated
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
                           @HeaderParam("X-Sas-Operator-Token") String operatorTokenHeader,
                           @HeaderParam("X-Sas-Risk-Class") String riskClassHeader,
                           @HeaderParam("X-Sas-Assurance-Detail") String assuranceDetailHeader) {
        return doVerify(body, xCorrelator, authorization, amr, srcIpHeader,
                srcPortHeader, accessTechHeader, operatorTokenHeader,
                riskClassHeader, assuranceDetailHeader);
    }

    /**
     * @deprecated lab alias — use
     *             {@code GET /number-verification/v2/device-phone-number};
     *             no {@code /retrieve-phone-number} exists in either NV YAML.
     */
    @Deprecated
    @GET
    @Path("/retrieve-phone-number")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retrievePhoneNumber(@HeaderParam("x-correlator") String xCorrelator,
                                        @HeaderParam("Authorization") String authorization,
                                        @HeaderParam("X-Sas-Amr") String amr,
                                        @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                                        @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                                        @HeaderParam("X-Sas-Access-Tech") String accessTechHeader) {
        return doShare(xCorrelator, authorization, amr, srcIpHeader,
                srcPortHeader, accessTechHeader);
    }

    // ---- shared handlers ----

    private Response doVerify(VerifyRequestDto body,
                              String xCorrelator,
                              String authorization,
                              String amr,
                              String srcIpHeader,
                              String srcPortHeader,
                              String accessTechHeader,
                              String operatorTokenHeader,
                              String riskClassHeader,
                              String assuranceDetailHeader) {
        String correlator = xCorrelator == null ? "" : xCorrelator;
        boolean detail = ApiTogglesConfig.assuranceDetailRequested(
                assuranceDetailHeader, apiToggles.assuranceDetailEnabled());

        // Wi-Fi / CIBA path — operator token as identity anchor, checked before
        // the normal bearer/amr validation (fail-closed on any token failure).
        String operatorCandidate = identityAnchor.extractCandidate(
                authorization, null, operatorTokenHeader);
        IdentityAnchor.OperatorBinding operatorBinding = null;
        if (operatorCandidate != null) {
            operatorBinding = identityAnchor.resolveOperatorToken(operatorCandidate);
            if (operatorBinding == null || operatorBinding.msisdn() == null
                    || operatorBinding.msisdn().isBlank()) {
                return error(401, CODE_UNAUTHENTICATED,
                        "operator token is invalid, expired or already used", correlator);
            }
        }

        String claimed;
        String hashed;
        String boundNumber = null;
        String srcIp = notBlank(srcIpHeader) ? srcIpHeader : "10.20.30.40";
        int srcPort = parseInt(srcPortHeader, 55555);
        AccessTech accessTech;
        String claimedImsi = null;

        boolean validationEnabled = securityConfig.tokenValidationEnabled();
        String tokenKey = null;

        if (operatorBinding != null) {
            // Identity anchor from the signed entitlement token; body phone
            // claims are ignored — the binding is authoritative on this path.
            claimed = RequestValidator.normalizeE164(operatorBinding.msisdn())
                    .orElse(null);
            if (claimed == null) {
                return error(401, CODE_UNAUTHENTICATED,
                        "operator token binding is not a valid E.164 number", correlator);
            }
            hashed = null;
            accessTech = AccessTech.WIFI;
            claimedImsi = operatorBinding.imsi();
            LOG.info("[SAS] /verify operator-token anchor applied (eap={})",
                    operatorBinding.eapMethod());
        } else {
            // H14 — single-use, short-lived, user-bound token, full claims validation.
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
                // F4 — fail closed without a user-number binding: the spec
                // compares the requested number against the number bound to
                // the access token at issuance.
                boundNumber = auth.boundNumber();
                if (boundNumber == null) {
                    return error(403, CODE_NOT_MOBILE,
                            "access token carries no user phone-number binding "
                                    + "(phone_number/msisdn claim)", correlator);
                }
                // Single-use bearer token: one completed call per token key.
                if (replayGuard.isConsumed(tokenKey) || accessTokens.isConsumed(tokenKey)) {
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
                return error(400, CODE_INVALID_ARGUMENT,
                        "exactly one of phoneNumber / hashedPhoneNumber is required", correlator);
            }
            // F6 — one normalization point feeds validation, hashing, comparing.
            claimed = hasPhone
                    ? RequestValidator.normalizeE164(body.phoneNumber()).orElse(null)
                    : null;
            hashed = hasHashed ? body.hashedPhoneNumber().trim() : null;
            if (hasPhone && claimed == null) {
                return error(400, CODE_INVALID_ARGUMENT,
                        "phoneNumber must be E.164 (+<digits>)", correlator);
            }
            if (hashed != null && !RequestValidator.isSha256Hex(hashed)) {
                return error(400, CODE_INVALID_ARGUMENT,
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
            return error(400, CODE_INVALID_ARGUMENT, tsError, correlator);
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

        // Billing gate: tenant resolution + quota metering, post-auth and
        // before any network work (unknown key under enforcement → 401).
        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            return error(401, CODE_UNAUTHENTICATED,
                    "unknown X-Api-Key (no tenant)", correlator);
        }
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(),
                    correlator);
        }

        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                claimed, claimedImsi, accessTech,
                VerifyRequestDto.parse(riskClassHeader), tenant.tenantId());

        VerifyResult result = awaitResult(event, reqId);

        if (validationEnabled && tokenKey != null) {
            // The token has now driven one completed call — consume it.
            replayGuard.consume(tokenKey);
            accessTokens.markConsumed(tokenKey);
        }

        LOG.info("[SAS] /verify reqId={} verified={} fallback={}",
                reqId, result.match(), result.fallbackReason());

        if (result.fallbackReason() != null) {
            // Fail-closed — the opt-in assurance snapshot still rides along so
            // the bank backend can make its own risk decision.
            return ok(VerifyResponseDto.from(false, result, detail), correlator);
        }

        // F4 — verify compares the requested number against BOTH the live
        // network resolution and the token-bound number (when validated).
        String resolved = RequestValidator.normalizeE164(result.msisdn()).orElse(null);
        boolean verified;
        if (resolved == null) {
            verified = false;
        } else if (claimed != null) {
            verified = claimed.equals(resolved)
                    && (boundNumber == null || boundNumber.equals(resolved));
        } else {
            // hashedPhoneNumber path — sha256("+E164") of both sides.
            verified = RequestValidator.sha256Hex(resolved).equalsIgnoreCase(hashed)
                    && (boundNumber == null
                        || RequestValidator.sha256Hex(boundNumber).equalsIgnoreCase(hashed));
        }
        return ok(VerifyResponseDto.from(verified, result, detail), correlator);
    }

    private Response doShare(String xCorrelator,
                             String authorization,
                             String amr,
                             String srcIpHeader,
                             String srcPortHeader,
                             String accessTechHeader) {
        // CAMARA NV device-phone-number:read — number-discovery surface.
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
            return error(400, CODE_INVALID_ARGUMENT, tsError, correlator);
        }

        // Number-discovery mode: no claimed MSISDN. Namespaced key so /verify
        // and /device-phone-number never share a reqId for one token.
        String reqId = RequestValidator.deriveRetrieveReqId(tokenKey, correlator);

        // Billing gate: tenant resolution + quota metering, post-auth and
        // before any network work (unknown key under enforcement → 401).
        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            return error(401, CODE_UNAUTHENTICATED,
                    "unknown X-Api-Key (no tenant)", correlator);
        }
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(),
                    correlator);
        }

        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                null, null, accessTech, null, tenant.tenantId());

        VerifyResult result = awaitResult(event, reqId);

        if (validationEnabled) {
            replayGuard.consume(tokenKey);
            accessTokens.markConsumed(tokenKey);
        }

        if (result.fallbackReason() != null || result.msisdn() == null) {
            return error(403, CODE_NOT_MOBILE,
                    "unable to resolve device phone number", correlator);
        }

        // F6 — share response always normalized E.164 (spec pattern ^\+...).
        String devicePhoneNumber = RequestValidator.normalizeE164(result.msisdn())
                .orElse(null);
        if (devicePhoneNumber == null) {
            return error(403, CODE_NOT_MOBILE,
                    "unable to resolve device phone number", correlator);
        }

        LOG.info("[SAS] /device-phone-number reqId={} resolved", reqId);
        return Response.ok(Map.of("devicePhoneNumber", devicePhoneNumber))
                .header("x-correlator", correlator)
                .build();
    }

    // ---- helpers ----

    /** X-Api-Key from the container headers; null-safe outside a container. */
    private String apiKeyHeader() {
        return httpHeaders == null ? null : httpHeaders.getHeaderString("X-Api-Key");
    }

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

    /** Spec ErrorInfo: status+code+message are all required (F3). */
    record CamaraError(int status, String code, String message) {
    }

    private static Response ok(VerifyResponseDto dto, String correlator) {
        return Response.ok(dto).header("x-correlator", correlator).build();
    }

    private static Response error(int status, String code, String message, String correlator) {
        return Response.status(status)
                .entity(new CamaraError(status, code, message))
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
}
