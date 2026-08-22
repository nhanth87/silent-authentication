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
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.security.ReplayGuard;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 */
@Path("/")
public class VerifyResource {

    private static final Logger LOG = LogManager.getLogger(VerifyResource.class);

    private static final String CODE_NOT_MOBILE =
            "NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK";

    @Inject
    SasBootstrap bootstrap;

    @Inject
    TokenValidator tokenValidator;

    @Inject
    ReplayGuard replayGuard;

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
                           @HeaderParam("X-Sas-Access-Tech") String accessTechHeader) {
        String correlator = xCorrelator == null ? "" : xCorrelator;

        // H14 — single-use, short-lived token. P1: full JWT validation.
        String tokenError = tokenValidator.validate(authorization);
        if (tokenError != null) {
            return error(401, "UNAUTHENTICATED", tokenError, correlator);
        }
        // H14 — 403 when the token was NOT authenticated by the mobile network.
        String amrError = tokenValidator.validateAmr(amr);
        if (amrError != null) {
            return error(403, CODE_NOT_MOBILE, amrError, correlator);
        }

        boolean hasPhone = body != null && body.phoneNumber() != null
                && !body.phoneNumber().isBlank();
        boolean hasHashed = body != null && body.hashedPhoneNumber() != null
                && !body.hashedPhoneNumber().isBlank();
        if (hasPhone == hasHashed) {
            return error(400, "VALIDATION.FAILED",
                    "exactly one of phoneNumber / hashedPhoneNumber is required", correlator);
        }

        String claimed = hasPhone ? body.phoneNumber().trim() : null;
        String hashed = hasHashed ? body.hashedPhoneNumber().trim() : null;

        // Network tuple — pilot: from headers (production: minted from the
        // CIBA / network-auth token during device onboarding). CGNAT-safe.
        String srcIp = notBlank(srcIpHeader) ? srcIpHeader : "10.20.30.40";
        int srcPort = parseInt(srcPortHeader, 55555);
        AccessTech accessTech = parseAccessTech(accessTechHeader);

        String reqId = UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();

        // P1 — replay window + reqId dedup enforcement.
        String replayError = replayGuard.check(ts, reqId);
        if (replayError != null) {
            return error(400, "VALIDATION.FAILED", replayError, correlator);
        }

        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                claimed, accessTech);

        VerifyResult result;
        try {
            result = bootstrap.submit(event).get(SasTimeouts.TOTAL_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            result = VerifyResult.fallback(reqId, FallbackReason.SAS_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = VerifyResult.fallback(reqId, FallbackReason.SAS_TIMEOUT);
        } catch (ExecutionException ex) {
            result = VerifyResult.fallback(reqId, FallbackReason.VERIFY_ERROR);
        } finally {
            bootstrap.release(reqId);
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

        String tokenError = tokenValidator.validate(authorization);
        if (tokenError != null) {
            return error(401, "UNAUTHENTICATED", tokenError, correlator);
        }
        String amrError = tokenValidator.validateAmr(amr);
        if (amrError != null) {
            return error(403, CODE_NOT_MOBILE, amrError, correlator);
        }

        String srcIp = notBlank(srcIpHeader) ? srcIpHeader : "10.20.30.40";
        int srcPort = parseInt(srcPortHeader, 55555);
        AccessTech accessTech = parseAccessTech(accessTechHeader);

        String reqId = UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();

        String replayError = replayGuard.check(ts, reqId);
        if (replayError != null) {
            return error(400, "VALIDATION.FAILED", replayError, correlator);
        }

        // Number-discovery mode: no claimed MSISDN.
        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                null, accessTech);

        VerifyResult result;
        try {
            result = bootstrap.submit(event).get(SasTimeouts.TOTAL_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            result = VerifyResult.fallback(reqId, FallbackReason.SAS_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = VerifyResult.fallback(reqId, FallbackReason.SAS_TIMEOUT);
        } catch (ExecutionException ex) {
            result = VerifyResult.fallback(reqId, FallbackReason.VERIFY_ERROR);
        } finally {
            bootstrap.release(reqId);
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}