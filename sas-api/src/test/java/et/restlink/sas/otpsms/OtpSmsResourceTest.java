/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.api.ApiCdrRecorder.ApiCdrRecord;
import et.restlink.sas.api.RecordingApiCdrRecorder;
import et.restlink.sas.api.TenantRegistry;
import et.restlink.sas.oauth.AccessTokenService;
import et.restlink.sas.security.ReplayGuard;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAMARA OneTimePasswordSMS v1.1.1 surface tests: template + identifier
 * validation, the operator-refusal mapping (403 {@code PHONE_NUMBER_*}),
 * fail-closed delivery ({@code 500 INTERNAL_ERROR}, no {@code authenticationId}),
 * per-MSISDN rate limiting, the validate-code state machine (INVALID_OTP →
 * VERIFICATION_FAILED → VERIFICATION_EXPIRED, single-use success = 204), the
 * scope/single-use gates shared with the rest of the northbound, and the rule
 * that the plaintext OTP is never stored.
 */
class OtpSmsResourceTest {

    private static final String MSISDN_A = "+251911111111";
    private static final String MSISDN_B = "+251922222222";
    private static final String TEMPLATE = "{{code}} is your Restlink code";
    private static final String SECRET = "test-secret";

    private final ObjectMapper mapper = new ObjectMapper();

    private OtpConfig otpConfig;
    private SasSecurityConfig securityConfig;
    private OtpAttemptStore store;
    private StubDelivery delivery;
    private OtpSmsResource resource;
    private RecordingApiCdrRecorder cdr;
    private int jtiCounter;

    /** Delivery seam stub: records what would be sent, outcome is settable. */
    static class StubDelivery implements SmsDeliveryPort {
        final List<String[]> sent = new ArrayList<>();
        DeliveryResult result = DeliveryResult.delivered("stub");

        @Override
        public DeliveryResult deliver(String msisdn, String smsText) {
            sent.add(new String[] {msisdn, smsText});
            return result;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        otpConfig = new OtpConfig();
        setField(otpConfig, "enabledRaw", "true");
        setField(otpConfig, "smsDeliveryRaw", "log");
        setField(otpConfig, "codeLength", 6);
        setField(otpConfig, "ttlSeconds", 300L);
        setField(otpConfig, "maxAttempts", 3);
        setField(otpConfig, "maxCodesPerNumber", 3);
        setField(otpConfig, "rateWindowSeconds", 3600L);

        securityConfig = new SasSecurityConfig();
        setField(securityConfig, "tokenValidationEnabled", false);
        setField(securityConfig, "hmacSecret", SECRET);
        setField(securityConfig, "expectedIssuer", "iss1");
        setField(securityConfig, "expectedAudience", "aud1");
        setField(securityConfig, "requiredScopesRaw", "");
        setField(securityConfig, "clockSkewSeconds", 30L);
        setField(securityConfig, "replayWindowSeconds", 300L);
        setField(securityConfig, "reqIdTtlSeconds", 600L);

        store = new OtpAttemptStore();
        delivery = new StubDelivery();

        cdr = new RecordingApiCdrRecorder();
        resource = new OtpSmsResource();
        resource.cdr = cdr;
        resource.otpConfig = otpConfig;
        resource.attempts = store;
        resource.smsDelivery = delivery;
        resource.tokenValidator = tokenValidatorWith(securityConfig);
        resource.replayGuard = replayGuardWith(securityConfig);
        resource.accessTokens = new AccessTokenService();
        resource.securityConfig = securityConfig;
    }

    // ---- send-code: happy path ----

    @Test
    void sendCode_happyPath_issuesAuthenticationIdAndComposesTheSms() throws Exception {
        Response r = resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                "otp-1", "Bearer lab");

        assertEquals(200, r.getStatus());
        assertEquals("otp-1", r.getHeaderString("x-correlator"));
        JsonNode n = toJsonNode(r.getEntity());
        String authenticationId = n.get("authenticationId").asText();
        assertEquals(36, authenticationId.length(), "spec caps AuthenticationId at 36");
        assertEquals(1, n.size(), "the OTP itself is never returned");

        // the {{code}} label was replaced by a 6-digit OTP for the right number
        assertEquals(1, delivery.sent.size());
        assertEquals(MSISDN_A, delivery.sent.get(0)[0]);
        String text = delivery.sent.get(0)[1];
        assertFalse(text.contains("{{code}}"));
        assertTrue(text.matches("\\d{6} is your Restlink code"), text);

        // stored: hash only, bound to the attempt id
        OtpAttemptStore.Attempt attempt = store.find(authenticationId).orElseThrow();
        String code = text.substring(0, 6);
        assertEquals(OtpSmsResource.hash(code, authenticationId), attempt.codeHash());
        assertFalse(attempt.codeHash().contains(code), "the plaintext OTP is never stored");
        assertEquals(0, attempt.attempts());
        assertFalse(attempt.burned());
    }

    @Test
    void sendCode_surfaceDisabled_404NotFound() throws Exception {
        setField(otpConfig, "enabledRaw", "false");
        assertCamaraError(404, "NOT_FOUND",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-2", "Bearer lab"));
        assertEquals(0, delivery.sent.size(), "nothing is handed to the operator");
    }

    // ---- send-code: request validation ----

    @Test
    void sendCode_missingPhoneNumber_400InvalidArgument() {
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.sendCode(new SendCodeRequest(null, TEMPLATE), "otp-3", "Bearer lab"));
    }

    @Test
    void sendCode_missingMessage_400InvalidArgument() {
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.sendCode(new SendCodeRequest(MSISDN_A, null), "otp-4", "Bearer lab"));
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.sendCode(new SendCodeRequest(MSISDN_A, "   "), "otp-4b", "Bearer lab"));
    }

    @Test
    void sendCode_messageWithoutCodeLabel_400InvalidArgument() {
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.sendCode(new SendCodeRequest(MSISDN_A, "your code is coming"),
                        "otp-5", "Bearer lab"));
    }

    @Test
    void sendCode_messageOverOneSegment_400InvalidArgument() {
        String tooLong = "{{code}} " + "x".repeat(OtpSmsResource.MESSAGE_MAX_LENGTH);
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.sendCode(new SendCodeRequest(MSISDN_A, tooLong), "otp-6", "Bearer lab"));
    }

    @Test
    void sendCode_malformedPhoneNumber_400InvalidArgument() {
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.sendCode(new SendCodeRequest("not-a-number", TEMPLATE),
                        "otp-7", "Bearer lab"));
    }

    @Test
    void sendCode_missingAuthorization_401Unauthenticated() {
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-8", null));
    }

    // ---- send-code: operator refusals + fail-closed delivery ----

    @Test
    void sendCode_operatorRefusesNumber_403PhoneNumberNotAllowed_noAttemptStored() {
        delivery.result = SmsDeliveryPort.DeliveryResult.notAllowed("fraud list");
        assertCamaraError(403, "ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_NOT_ALLOWED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-9", "Bearer lab"));
        assertEquals(0, store.attemptCount(), "no authenticationId without a delivered SMS");
    }

    @Test
    void sendCode_operatorBlocksNumber_403PhoneNumberBlocked() {
        delivery.result = SmsDeliveryPort.DeliveryResult.blocked("SMS barred");
        assertCamaraError(403, "ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_BLOCKED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-10", "Bearer lab"));
        assertEquals(0, store.attemptCount());
    }

    @Test
    void sendCode_noDeliveryRoute_500InternalError_failClosed() {
        delivery.result = SmsDeliveryPort.DeliveryResult.unavailable("no operator SMSC adapter");
        assertCamaraError(500, "INTERNAL_ERROR",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-11", "Bearer lab"));
        assertEquals(0, store.attemptCount());
    }

    @Test
    void sendCode_deliverySeamMissingOrThrows_500InternalError() {
        resource.smsDelivery = null;
        assertCamaraError(500, "INTERNAL_ERROR",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-12", "Bearer lab"));

        resource.smsDelivery = (msisdn, text) -> {
            throw new IllegalStateException("SMSC unreachable");
        };
        assertCamaraError(500, "INTERNAL_ERROR",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-13", "Bearer lab"));
    }

    @Test
    void sendCode_perMsisdnRateLimit_403MaxOtpCodesExceeded() {
        for (int i = 0; i < 3; i++) {
            assertEquals(200, resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                    "otp-14-" + i, "Bearer lab").getStatus());
        }
        assertCamaraError(403, "ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-15", "Bearer lab"));
        // another subscriber is unaffected
        assertEquals(200, resource.sendCode(new SendCodeRequest(MSISDN_B, TEMPLATE),
                "otp-16", "Bearer lab").getStatus());
    }

    // ---- validate-code state machine ----

    @Test
    void validateCode_correctCode_204AndSingleUse() throws Exception {
        String id = sendAndGetId(MSISDN_A);
        String code = deliveredCode();

        Response ok = resource.validateCode(new ValidateCodeRequest(id, code), "otp-17", "Bearer lab");
        assertEquals(204, ok.getStatus());
        assertNull(ok.getEntity(), "204 carries no body");
        assertEquals("otp-17", ok.getHeaderString("x-correlator"));

        // the attempt is gone: replaying the same correct code is a 404
        assertCamaraError(404, "NOT_FOUND",
                resource.validateCode(new ValidateCodeRequest(id, code), "otp-18", "Bearer lab"));
    }

    @Test
    void validateCode_wrongCode_400InvalidOtp_thenBurnsAtMaxAttempts() throws Exception {
        String id = sendAndGetId(MSISDN_A);
        String code = deliveredCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.INVALID_OTP",
                resource.validateCode(new ValidateCodeRequest(id, wrong), "otp-19", "Bearer lab"));
        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.INVALID_OTP",
                resource.validateCode(new ValidateCodeRequest(id, wrong), "otp-20", "Bearer lab"));
        // third wrong attempt exhausts the budget → VERIFICATION_FAILED
        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED",
                resource.validateCode(new ValidateCodeRequest(id, wrong), "otp-21", "Bearer lab"));
        // …and the correct code no longer works
        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED",
                resource.validateCode(new ValidateCodeRequest(id, code), "otp-22", "Bearer lab"));
    }

    @Test
    void validateCode_expiredAttempt_400VerificationExpired() {
        String id = "11111111-2222-3333-4444-555555555555";
        String code = "123456";
        store.createWithDeadline(id, MSISDN_A, OtpSmsResource.hash(code, id),
                (System.currentTimeMillis() / 1000L) - 1);

        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.VERIFICATION_EXPIRED",
                resource.validateCode(new ValidateCodeRequest(id, code), "otp-23", "Bearer lab"));
        assertTrue(store.find(id).isEmpty(), "an expired attempt is dropped, not extended");
    }

    @Test
    void validateCode_unknownAttempt_404NotFound() {
        assertCamaraError(404, "NOT_FOUND", resource.validateCode(
                new ValidateCodeRequest("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "123456"),
                "otp-24", "Bearer lab"));
    }

    @Test
    void validateCode_requestValidation_400InvalidArgument() {
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.validateCode(new ValidateCodeRequest(null, "123456"), "otp-25", "Bearer lab"));
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.validateCode(new ValidateCodeRequest("x".repeat(37), "123456"),
                        "otp-26", "Bearer lab"));
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.validateCode(new ValidateCodeRequest("abc", null), "otp-27", "Bearer lab"));
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.validateCode(new ValidateCodeRequest("abc", "12345678901"),
                        "otp-28", "Bearer lab"));
    }

    @Test
    void validateCode_surfaceDisabled_404NotFound() throws Exception {
        setField(otpConfig, "enabledRaw", "false");
        assertCamaraError(404, "NOT_FOUND",
                resource.validateCode(new ValidateCodeRequest("abc", "123456"),
                        "otp-29", "Bearer lab"));
    }

    // ---- tenant / quota gate ----

    @Test
    void quotaExhausted_429QuotaExceeded() {
        resource.tenants = new TenantRegistry() {
            @Override
            public boolean checkAndIncrement(String tenantId) {
                return false;
            }
        };
        assertCamaraError(429, "QUOTA_EXCEEDED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-30", "Bearer lab"));
    }

    @Test
    void unknownApiKey_401Unauthenticated() {
        resource.tenants = new TenantRegistry() {
            @Override
            public TenantInfo resolve(String apiKey) {
                return null;
            }
        };
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-31", "Bearer lab"));
    }

    // ---- validation enabled: scope, binding, single use ----

    @Test
    void threeLegged_boundTokenMatchingNumber_accepted() throws Exception {
        enableValidation();
        String token = makeJwt("jti-otp-ok",
                TokenValidator.SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE,
                "\"phone_number\":\"" + MSISDN_A + "\"");

        Response r = resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-32",
                "Bearer " + token);

        assertEquals(200, r.getStatus());
    }

    @Test
    void threeLegged_boundTokenOtherNumber_403PermissionDenied() throws Exception {
        enableValidation();
        String token = makeJwt("jti-otp-other",
                TokenValidator.SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE,
                "\"phone_number\":\"" + MSISDN_A + "\"");

        assertCamaraError(403, "PERMISSION_DENIED",
                resource.sendCode(new SendCodeRequest(MSISDN_B, TEMPLATE), "otp-33",
                        "Bearer " + token));
        assertEquals(0, delivery.sent.size(), "the OTP is not even composed for a foreign number");
    }

    @Test
    void threeLegged_missingScope_403PermissionDenied() throws Exception {
        enableValidation();
        String token = makeJwt("jti-otp-scope", TokenValidator.SCOPE_SIM_SWAP_CHECK,
                "\"phone_number\":\"" + MSISDN_A + "\"");

        assertCamaraError(403, "PERMISSION_DENIED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE), "otp-34",
                        "Bearer " + token));
    }

    @Test
    void threeLegged_familyScopeGrantsTheApi() throws Exception {
        enableValidation();
        String token = makeJwt("jti-otp-family", "one-time-password-sms",
                "\"phone_number\":\"" + MSISDN_A + "\"");

        assertEquals(200, resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                "otp-35", "Bearer " + token).getStatus());
    }

    @Test
    void threeLegged_tokenIsSingleUsePerCall() throws Exception {
        enableValidation();
        String token = makeJwt("jti-otp-reuse",
                TokenValidator.SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE,
                "\"phone_number\":\"" + MSISDN_A + "\"");

        assertEquals(200, resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                "otp-36", "Bearer " + token).getStatus());
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                        "otp-36", "Bearer " + token));
    }

    // ---- CDR audit ----

    @Test
    void sendCode_successWritesMaskedCdrWithoutTheOtp() throws Exception {
        cdr.clear();
        Response r = resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                "cdr-send-ok", "Bearer lab");
        assertEquals(200, r.getStatus());
        String authenticationId = toJsonNode(r.getEntity()).get("authenticationId").asText();
        String code = deliveredCode();

        ApiCdrRecord record = cdr.last();
        assertEquals("cdr-send-ok", record.correlationId());
        assertEquals("OTP", record.phase());
        assertEquals("OTP", record.operation());
        assertEquals("+251****11", record.msisdn());
        assertTrue(record.ok());
        assertEquals(200, record.httpStatus());
        assertNull(record.errorCode());
        assertEquals("lab", record.tenantId());
        assertTrue(record.detail().contains("action=send-code"));
        assertTrue(record.detail().contains("result=SENT"));
        assertTrue(record.detail().contains("authenticationId=" + authenticationId));
        assertTrue(record.detail().contains("messageChars=" + TEMPLATE.length()));
        assertFalse(record.detail().contains(code));
        assertFalse(record.detail().contains(MSISDN_A));
        assertFalse(record.detail().contains(TEMPLATE));
    }

    @Test
    void sendCode_deliveryFailureWritesFailClosedCdr() {
        delivery.result = SmsDeliveryPort.DeliveryResult.unavailable("no operator SMSC adapter");
        cdr.clear();
        assertCamaraError(500, "INTERNAL_ERROR",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                        "cdr-send-500", "Bearer lab"));

        ApiCdrRecord record = cdr.last();
        assertFalse(record.ok());
        assertEquals(500, record.httpStatus());
        assertEquals("INTERNAL_ERROR", record.errorCode());
        assertEquals("+251****11", record.msisdn());
        assertTrue(record.detail().contains("deliveryOutcome=UNAVAILABLE"));
        assertFalse(record.detail().contains("result=SENT"));
    }

    @Test
    void sendCode_rateLimitWritesFailedCdr() {
        for (int i = 0; i < 3; i++) {
            assertEquals(200, resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                    "cdr-rate-" + i, "Bearer lab").getStatus());
        }
        cdr.clear();
        assertCamaraError(403, "ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED",
                resource.sendCode(new SendCodeRequest(MSISDN_A, TEMPLATE),
                        "cdr-rate-limit", "Bearer lab"));

        ApiCdrRecord record = cdr.last();
        assertFalse(record.ok());
        assertEquals(403, record.httpStatus());
        assertEquals("ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED", record.errorCode());
        assertTrue(record.detail().contains("rateLimited=true"));
    }

    @Test
    void validateCode_successWritesCompletedCdrWithoutTheOtp() throws Exception {
        String authenticationId = sendAndGetId(MSISDN_A);
        String code = deliveredCode();
        cdr.clear();

        Response ok = resource.validateCode(new ValidateCodeRequest(authenticationId, code),
                "cdr-validate-ok", "Bearer lab");
        assertEquals(204, ok.getStatus());

        ApiCdrRecord record = cdr.last();
        assertEquals("cdr-validate-ok", record.correlationId());
        assertEquals("OTP", record.phase());
        assertEquals("+251****11", record.msisdn());
        assertTrue(record.ok());
        assertEquals(204, record.httpStatus());
        assertTrue(record.detail().contains("action=validate-code"));
        assertTrue(record.detail().contains("result=VALID"));
        assertTrue(record.detail().contains("authenticationId=" + authenticationId));
        assertFalse(record.detail().contains(code));
        assertFalse(record.detail().contains(MSISDN_A));
    }

    @Test
    void validateCode_wrongCodeWritesAttemptCountButNotTheCode() throws Exception {
        String authenticationId = sendAndGetId(MSISDN_A);
        String code = deliveredCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        cdr.clear();

        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.INVALID_OTP",
                resource.validateCode(new ValidateCodeRequest(authenticationId, wrong),
                        "cdr-validate-wrong", "Bearer lab"));

        ApiCdrRecord record = cdr.last();
        assertFalse(record.ok());
        assertEquals(400, record.httpStatus());
        assertEquals("ONE_TIME_PASSWORD_SMS.INVALID_OTP", record.errorCode());
        assertTrue(record.detail().contains("result=INVALID"));
        assertTrue(record.detail().contains("attempts=1"));
        assertTrue(record.detail().contains("maxAttempts=3"));
        assertFalse(record.detail().contains(wrong));
        assertFalse(record.detail().contains(code));
    }

    @Test
    void validateCode_burnedAttemptWritesVerificationFailedCdr() throws Exception {
        String authenticationId = sendAndGetId(MSISDN_A);
        String code = deliveredCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 2; i++) {
            assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.INVALID_OTP",
                    resource.validateCode(new ValidateCodeRequest(authenticationId, wrong),
                            "cdr-burn-" + i, "Bearer lab"));
        }
        cdr.clear();
        assertCamaraError(400, "ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED",
                resource.validateCode(new ValidateCodeRequest(authenticationId, wrong),
                        "cdr-burn-final", "Bearer lab"));

        ApiCdrRecord record = cdr.last();
        assertFalse(record.ok());
        assertEquals("ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED", record.errorCode());
        assertTrue(record.detail().contains("result=BURNED"));
        assertTrue(record.detail().contains("attempts=3"));
    }

    // ---- helpers ----

    private String sendAndGetId(String msisdn) throws Exception {
        Response r = resource.sendCode(new SendCodeRequest(msisdn, TEMPLATE), "otp-send",
                "Bearer lab");
        assertEquals(200, r.getStatus());
        return toJsonNode(r.getEntity()).get("authenticationId").asText();
    }

    /** The OTP the stub delivery seam would have texted (lab visibility). */
    private String deliveredCode() {
        String text = delivery.sent.get(delivery.sent.size() - 1)[1];
        return text.substring(0, text.indexOf(' '));
    }

    private void enableValidation() throws Exception {
        setField(securityConfig, "tokenValidationEnabled", true);
    }

    private void assertCamaraError(int status, String code, Response r) {
        assertEquals(status, r.getStatus());
        OtpSmsResource.CamaraError body = (OtpSmsResource.CamaraError) r.getEntity();
        assertEquals(status, body.status());
        assertEquals(code, body.code());
        assertFalse(body.message().isBlank());
    }

    private JsonNode toJsonNode(Object entity) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(entity));
    }

    private String makeJwt(String jtiSeed, String scope, String extraClaimsJson) {
        jtiCounter++;
        long now = System.currentTimeMillis() / 1000L;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                        .getBytes(StandardCharsets.UTF_8));
        StringBuilder p = new StringBuilder();
        p.append("{\"iss\":\"iss1\",\"aud\":\"aud1\"");
        if (scope != null) {
            p.append(",\"scope\":\"").append(scope).append("\"");
        }
        if (extraClaimsJson != null) {
            p.append(",").append(extraClaimsJson);
        }
        p.append(",\"jti\":\"").append(jtiSeed).append('-').append(jtiCounter).append("\"");
        p.append(",\"exp\":").append(now + 300).append(",\"iat\":").append(now).append("}");
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((header + "." + payloadB64)
                            .getBytes(StandardCharsets.UTF_8)));
            return header + "." + payloadB64 + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TokenValidator tokenValidatorWith(SasSecurityConfig cfg) {
        TokenValidator v = new TokenValidator();
        try {
            var f = TokenValidator.class.getDeclaredField("config");
            f.setAccessible(true);
            f.set(v, cfg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return v;
    }

    private static ReplayGuard replayGuardWith(SasSecurityConfig cfg) {
        ReplayGuard g = new ReplayGuard();
        try {
            var f = ReplayGuard.class.getDeclaredField("config");
            f.setAccessible(true);
            f.set(g, cfg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return g;
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == Optional.class) {
            field.set(obj, Optional.ofNullable((String) value));
        } else {
            field.set(obj, value);
        }
    }
}
