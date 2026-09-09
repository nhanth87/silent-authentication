/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

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

import java.security.SecureRandom;
import java.util.UUID;

/**
 * CAMARA OneTimePasswordSMS v1.1.1 northbound surface
 * ({@code {apiRoot}/one-time-password-sms/v1}) — the <strong>fallback</strong>
 * branch of silent auth. When {@code /verify} answers FALLBACK (Wi-Fi without
 * TS.43, stale binding, assurance under threshold) the bank falls back to an
 * OTP over SMS; this surface mints and validates that OTP.
 *
 * <p><strong>Product boundary (AGENTS §2, proposal ch.7 §7.6.2)</strong>: the
 * SAS orchestrates policy only — it composes the message, keeps the attempt
 * state and validates the code, while the SMS itself is delivered by the
 * operator (Ethio Telecom SMSC, or SGd per TS 29.338) through
 * {@link SmsDeliveryPort}. Restlink does not wholesale SMS, and the OTP traffic
 * stays subject to Strategy B (Home Routing + signalling firewall, SG.22).</p>
 *
 * <p><strong>Wire contract (r3.2 / v1.1.1)</strong>:</p>
 * <ul>
 *   <li>POST /send-code {@code {"phoneNumber","+E164","message":"…{{code}}…"}} →
 *       {@code 200 {"authenticationId":"<uuid>"}}. {@code message} is required,
 *       must contain {@value #MESSAGE_CODE_TOKEN} and is capped at
 *       {@value #MESSAGE_MAX_LENGTH} chars.</li>
 *   <li>POST /validate-code {@code {"authenticationId","code"}} → {@code 204}
 *       on success (no body).</li>
 *   <li>One scope for both operations:
 *       {@value TokenValidator#SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE}.</li>
 *   <li>Errors: {@code INVALID_ARGUMENT} (400) plus the API-specific
 *       {@code ONE_TIME_PASSWORD_SMS.INVALID_OTP} /
 *       {@code …VERIFICATION_EXPIRED} / {@code …VERIFICATION_FAILED} (400),
 *       {@code UNAUTHENTICATED} (401), {@code PERMISSION_DENIED} /
 *       {@code …MAX_OTP_CODES_EXCEEDED} / {@code …PHONE_NUMBER_NOT_ALLOWED} /
 *       {@code …PHONE_NUMBER_BLOCKED} (403), {@code NOT_FOUND} (404),
 *       {@code QUOTA_EXCEEDED} (429) and {@code INTERNAL_ERROR} (500, from
 *       CAMARA_common — used when no delivery route exists, so a bank can never
 *       be told an OTP was sent when it was not). {@code x-correlator} echoed.</li>
 * </ul>
 *
 * <p><strong>Fail-closed</strong>: the whole surface answers
 * {@code 404 NOT_FOUND} unless {@code sas.otp.enabled=true}; a delivery seam
 * that is not the lab log sender answers {@code 500 INTERNAL_ERROR} until a real
 * operator adapter exists (production preflight {@code PRO-29} refuses a lab
 * sender); a wrong code is counted and the attempt burns at
 * {@code sas.otp.max-attempts}; a correct code consumes the attempt (one OTP
 * validates once).</p>
 *
 * <p><strong>Privacy</strong>: the OTP and the MSISDN never appear in a response
 * body; only the hashed code is stored ({@code sha256(authenticationId|code)}),
 * and logs mask the number. With a user-bound (3-legged) token the requested
 * {@code phoneNumber} must equal the token binding, else
 * {@code 403 PERMISSION_DENIED}.</p>
 */
@Path("/one-time-password-sms/v1")
public class OtpSmsResource {

    private static final Logger LOG = LogManager.getLogger(OtpSmsResource.class);

    /** Label the SMS template must carry where the OTP goes. */
    public static final String MESSAGE_CODE_TOKEN = "{{code}}";

    /** CAMARA {@code Message} schema cap — one GSM segment. */
    public static final int MESSAGE_MAX_LENGTH = 160;

    /** CAMARA {@code Code} schema cap. */
    public static final int CODE_MAX_LENGTH = 10;

    /** CAMARA {@code AuthenticationId} schema cap (a UUID is exactly 36). */
    public static final int AUTHENTICATION_ID_MAX_LENGTH = 36;

    private static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_PERMISSION_DENIED = "PERMISSION_DENIED";
    private static final String CODE_NOT_FOUND = "NOT_FOUND";
    private static final String CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String CODE_INVALID_OTP = "ONE_TIME_PASSWORD_SMS.INVALID_OTP";
    private static final String CODE_VERIFICATION_EXPIRED =
            "ONE_TIME_PASSWORD_SMS.VERIFICATION_EXPIRED";
    private static final String CODE_VERIFICATION_FAILED =
            "ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED";
    private static final String CODE_MAX_OTP_CODES_EXCEEDED =
            "ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED";
    private static final String CODE_PHONE_NUMBER_NOT_ALLOWED =
            "ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_NOT_ALLOWED";
    private static final String CODE_PHONE_NUMBER_BLOCKED =
            "ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_BLOCKED";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    OtpConfig otpConfig;

    @Inject
    OtpAttemptStore attempts;

    @Inject
    SmsDeliveryPort smsDelivery;

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

    /** CAMARA OneTimePasswordSMS sendCode. */
    @POST
    @Path("/send-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendCode(SendCodeRequest body,
                             @HeaderParam("x-correlator") String xCorrelator,
                             @HeaderParam("Authorization") String authorization) {
        String correlator = xCorrelator == null ? "" : xCorrelator;
        ApiAudit audit = ApiAudit.start("OTP", "OTP", correlator)
                .detail("action", "send-code");
        return audit(audit, doSendCode(body, correlator, authorization, audit));
    }

    private Response doSendCode(SendCodeRequest body,
                                String correlator,
                                String authorization,
                                ApiAudit audit) {
        Auth auth = authorize(authorization, correlator);
        if (auth.rejection() != null) {
            return auth.rejection();
        }
        if (!otpConfig.enabled()) {
            return error(404, CODE_NOT_FOUND,
                    "the OTP SMS surface is not offered by this deployment", correlator);
        }

        String rawNumber = body == null ? null : trimToNull(body.phoneNumber());
        String message = body == null ? null : body.message();
        if (rawNumber == null) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "phoneNumber is required", correlator);
        }
        if (message == null || message.isBlank()) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "message is required (SMS template containing " + MESSAGE_CODE_TOKEN + ")",
                    correlator);
        }
        if (!message.contains(MESSAGE_CODE_TOKEN)) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "message must contain the " + MESSAGE_CODE_TOKEN + " label", correlator);
        }
        if (message.length() > MESSAGE_MAX_LENGTH) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "message must be at most " + MESSAGE_MAX_LENGTH + " characters", correlator);
        }
        audit.detail("messageChars", message.length());
        String msisdn = RequestValidator.normalizeE164(rawNumber).orElse(null);
        if (msisdn == null) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "phoneNumber must be E.164 (+<digits>)", correlator);
        }
        audit.msisdn(mask(msisdn));
        // 3-legged privacy gate: the token binding is the only number this
        // caller may ask the SAS to text.
        if (auth.boundNumber() != null && !auth.boundNumber().equals(msisdn)) {
            audit.detail("bindingMismatch", true);
            return error(403, CODE_PERMISSION_DENIED,
                    "phoneNumber does not match the access-token binding", correlator);
        }

        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            return error(401, CODE_UNAUTHENTICATED,
                    "unknown X-Api-Key (no tenant)", correlator);
        }
        audit.tenant(tenant.tenantId());
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(), correlator);
        }

        if (attempts.sendRateLimited(msisdn, otpConfig.maxCodesPerNumber(),
                otpConfig.rateWindowSeconds())) {
            audit.detail("rateLimited", true);
            LOG.warn("[SAS] /send-code msisdn={} rate-limited ({} per {}s)",
                    mask(msisdn), otpConfig.maxCodesPerNumber(), otpConfig.rateWindowSeconds());
            return error(403, CODE_MAX_OTP_CODES_EXCEEDED,
                    "too many OTPs have been requested for this MSISDN — try later", correlator);
        }

        String code = generateCode(otpConfig.codeLength());
        audit.detail("delivery", otpConfig.smsDelivery());
        SmsDeliveryPort.DeliveryResult delivery = deliver(msisdn,
                message.replace(MESSAGE_CODE_TOKEN, code), correlator);
        if (!delivery.delivered()) {
            audit.detail("deliveryOutcome", delivery.outcome());
            return deliveryRejection(delivery, correlator);
        }

        // The id salts the hash, so the stored value is bound to this attempt.
        String authenticationId = newAuthenticationId();
        attempts.create(authenticationId, msisdn, hash(code, authenticationId),
                otpConfig.ttlSeconds());
        attempts.noteSend(msisdn);
        consume(auth);

        audit.detail("result", "SENT")
                .detail("authenticationId", authenticationId)
                .detail("ttlSeconds", otpConfig.ttlSeconds());
        LOG.info("[SAS] /send-code msisdn={} authenticationId={} ttl={}s tenant={}",
                mask(msisdn), authenticationId, otpConfig.ttlSeconds(), tenant.tenantId());
        return Response.ok(new SendCodeResponse(authenticationId))
                .header("x-correlator", correlator)
                .build();
    }

    /** CAMARA OneTimePasswordSMS validateCode. */
    @POST
    @Path("/validate-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response validateCode(ValidateCodeRequest body,
                                 @HeaderParam("x-correlator") String xCorrelator,
                                 @HeaderParam("Authorization") String authorization) {
        String correlator = xCorrelator == null ? "" : xCorrelator;
        ApiAudit audit = ApiAudit.start("OTP", "OTP", correlator)
                .detail("action", "validate-code");
        return audit(audit, doValidateCode(body, correlator, authorization, audit));
    }

    private Response doValidateCode(ValidateCodeRequest body,
                                    String correlator,
                                    String authorization,
                                    ApiAudit audit) {
        Auth auth = authorize(authorization, correlator);
        if (auth.rejection() != null) {
            return auth.rejection();
        }
        if (!otpConfig.enabled()) {
            return error(404, CODE_NOT_FOUND,
                    "the OTP SMS surface is not offered by this deployment", correlator);
        }

        String authenticationId = body == null ? null : trimToNull(body.authenticationId());
        String code = body == null ? null : trimToNull(body.code());
        if (authenticationId == null) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "authenticationId is required", correlator);
        }
        if (authenticationId.length() > AUTHENTICATION_ID_MAX_LENGTH) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "authenticationId must be at most " + AUTHENTICATION_ID_MAX_LENGTH
                            + " characters", correlator);
        }
        audit.detail("authenticationId", authenticationId);
        if (code == null) {
            return error(400, CODE_INVALID_ARGUMENT, "code is required", correlator);
        }
        if (code.length() > CODE_MAX_LENGTH) {
            return error(400, CODE_INVALID_ARGUMENT,
                    "code must be at most " + CODE_MAX_LENGTH + " characters", correlator);
        }

        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            return error(401, CODE_UNAUTHENTICATED,
                    "unknown X-Api-Key (no tenant)", correlator);
        }
        audit.tenant(tenant.tenantId());
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(), correlator);
        }

        OtpAttemptStore.Attempt attempt = attempts.find(authenticationId).orElse(null);
        if (attempt == null) {
            return error(404, CODE_NOT_FOUND,
                    "unknown authenticationId", correlator);
        }
        audit.msisdn(mask(attempt.msisdn()));
        long nowSec = System.currentTimeMillis() / 1000L;
        if (attempt.expired(nowSec)) {
            attempts.remove(authenticationId);
            audit.detail("result", "EXPIRED");
            return error(400, CODE_VERIFICATION_EXPIRED,
                    "the authenticationId is no longer valid", correlator);
        }
        if (attempt.burned()) {
            audit.detail("result", "BURNED");
            return error(400, CODE_VERIFICATION_FAILED,
                    "the maximum number of attempts for this authenticationId was exceeded",
                    correlator);
        }

        boolean matches = attempt.codeHash().equals(hash(code, authenticationId));
        if (!matches) {
            OtpAttemptStore.Attempt updated = attempts
                    .registerFailure(authenticationId, otpConfig.maxAttempts())
                    .orElse(attempt);
            consume(auth);
            audit.detail("attempts", updated.attempts())
                    .detail("maxAttempts", otpConfig.maxAttempts());
            LOG.warn("[SAS] /validate-code authenticationId={} wrong code (attempt {}/{})",
                    authenticationId, updated.attempts(), otpConfig.maxAttempts());
            if (updated.burned()) {
                audit.detail("result", "BURNED");
                return error(400, CODE_VERIFICATION_FAILED,
                        "the maximum number of attempts for this authenticationId was exceeded",
                        correlator);
            }
            audit.detail("result", "INVALID");
            return error(400, CODE_INVALID_OTP,
                    "the provided OTP is not valid for this authenticationId", correlator);
        }

        // One OTP validates exactly once.
        attempts.remove(authenticationId);
        consume(auth);
        audit.detail("result", "VALID");
        LOG.info("[SAS] /validate-code authenticationId={} verified msisdn={} tenant={}",
                authenticationId, mask(attempt.msisdn()), tenant.tenantId());
        return Response.noContent().header("x-correlator", correlator).build();
    }

    // ---- helpers ----

    /** Token key (single-use) + user binding, or the rejection to return. */
    private record Auth(String tokenKey, String boundNumber, Response rejection) {

        static Auth reject(Response rejection) {
            return new Auth(null, null, rejection);
        }
    }

    /** Token gate shared by both operations (one scope for the whole API). */
    private Auth authorize(String authorization, String correlator) {
        TokenValidator.DetailedAuth auth = tokenValidator.validateDetailed(authorization);
        if (!auth.ok()) {
            return Auth.reject(error(401, CODE_UNAUTHENTICATED, auth.error(), correlator));
        }
        if (!securityConfig.tokenValidationEnabled()) {
            return new Auth(null, auth.boundNumber(), null);
        }
        if (!TokenValidator.hasScope(auth.scopes(),
                TokenValidator.SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE)) {
            return Auth.reject(error(403, CODE_PERMISSION_DENIED,
                    "missing required scope: "
                            + TokenValidator.SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE,
                    correlator));
        }
        String tokenKey = auth.tokenKey();
        if (replayGuard.isConsumed(tokenKey) || accessTokens.isConsumed(tokenKey)) {
            return Auth.reject(error(401, CODE_UNAUTHENTICATED,
                    "token already used (single-use)", correlator));
        }
        String replayError = replayGuard.checkReplay(tokenKey, correlator);
        if (replayError != null) {
            return Auth.reject(error(401, CODE_UNAUTHENTICATED, replayError, correlator));
        }
        return new Auth(tokenKey, auth.boundNumber(), null);
    }

    private SmsDeliveryPort.DeliveryResult deliver(String msisdn, String smsText, String correlator) {
        if (smsDelivery == null) {
            LOG.error("[SAS] no SMS delivery seam wired — failing closed (correlator={})", correlator);
            return SmsDeliveryPort.DeliveryResult.unavailable("no delivery seam");
        }
        try {
            SmsDeliveryPort.DeliveryResult result = smsDelivery.deliver(msisdn, smsText);
            return result == null
                    ? SmsDeliveryPort.DeliveryResult.unavailable("delivery seam returned null")
                    : result;
        } catch (RuntimeException e) {
            LOG.error("[SAS] SMS delivery failed — failing closed", e);
            return SmsDeliveryPort.DeliveryResult.unavailable(e.getClass().getSimpleName());
        }
    }

    private static Response deliveryRejection(SmsDeliveryPort.DeliveryResult delivery,
                                              String correlator) {
        return switch (delivery.outcome()) {
            case NOT_ALLOWED -> error(403, CODE_PHONE_NUMBER_NOT_ALLOWED,
                    "the phone number cannot receive an SMS for operator business reasons",
                    correlator);
            case BLOCKED -> error(403, CODE_PHONE_NUMBER_BLOCKED,
                    "the phone number is blocked from receiving SMS", correlator);
            case DELIVERED, UNAVAILABLE -> error(500, CODE_INTERNAL_ERROR,
                    "no OTP SMS delivery route is available (" + delivery.detail() + ")",
                    correlator);
        };
    }

    /** The token has now driven one completed call — consume it. */
    private void consume(Auth auth) {
        if (auth.tokenKey() != null) {
            replayGuard.consume(auth.tokenKey());
            accessTokens.markConsumed(auth.tokenKey());
        }
    }

    /** Digits only — a numeric OTP is what a handset keypad can type. */
    static String generateCode(int length) {
        int len = Math.max(1, length);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** Spec {@code AuthenticationId} (≤36 chars) — a UUID is exactly 36. */
    static String newAuthenticationId() {
        return UUID.randomUUID().toString();
    }

    /** Attempt-bound hash: the plaintext code is never stored. */
    static String hash(String code, String authenticationId) {
        return RequestValidator.sha256Hex((authenticationId == null ? "" : authenticationId)
                + "|" + code);
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
