/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.api.dto.VerifyRequestDto;
import et.restlink.sas.bootstrap.SasBootstrap;
import et.restlink.sas.entitlement.EntitlementTokenService;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.security.OperatorTokenSupport;
import et.restlink.sas.security.ReplayGuard;
import et.restlink.sas.security.RequestValidator;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAMARA NV v2 northbound surface tests: alias parity (F1/F7), opt-in
 * assurance enrichment (F2), ErrorInfo contract (F3), user-bound token
 * compare matrix (F4) and normalization-driven compares (F6).
 */
class VerifyResourceTest {

    private static final String MSISDN_A = "+251911111111";
    private static final String MSISDN_B = "+251922222222";
    private static final String HASH_A = RequestValidator.sha256Hex(MSISDN_A);

    private final ObjectMapper mapper = new ObjectMapper();

    private StubBootstrap stub;
    private SasSecurityConfig config;
    private ApiTogglesConfig toggles;
    private VerifyResource resource;
    private int jtiCounter;

    /** Deterministic SAS core: returns the canned result, captures events. */
    static class StubBootstrap extends SasBootstrap {
        VerifyResult result;
        VerifyRequestEvent lastEvent;

        @Override
        public CompletableFuture<VerifyResult> submit(VerifyRequestEvent evt) {
            lastEvent = evt;
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void release(String reqId) {
            // no container to release in unit tests
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubBootstrap();
        config = new SasSecurityConfig();
        setField(config, "tokenValidationEnabled", false);
        setField(config, "hmacSecret", "test-secret");
        setField(config, "expectedIssuer", "iss1");
        setField(config, "expectedAudience", "aud1");
        setField(config, "requiredScopesRaw", "");
        setField(config, "clockSkewSeconds", 30L);
        setField(config, "replayWindowSeconds", 300L);

        toggles = new ApiTogglesConfig();
        setField(toggles, "assuranceDetailEnabledRaw", null);

        resource = new VerifyResource();
        resource.bootstrap = stub;
        resource.tokenValidator = tokenValidatorWith(config);
        resource.replayGuard = replayGuardWith(config);
        resource.securityConfig = config;
        resource.apiToggles = toggles;
        resource.operatorTokenSupport = failClosedOperatorStub();
    }

    // ---- F1/F7 — alias parity ----

    @Test
    void verifyAlias_parity_sameHandlerResult() {
        stub.result = VerifyResult.approved("req-alias", MSISDN_A, AssuranceLevel.HIGH);
        VerifyRequestDto body = new VerifyRequestDto(MSISDN_A, null);

        Response legacy = resource.verify(body, "corr-parity", "Bearer lab",
                "mobile", null, null, null, null, null, null);
        Response v2 = resource.verifyV2(body, "corr-parity", "Bearer lab",
                "mobile", null, null, null, null, null, null);

        assertEquals(200, legacy.getStatus());
        assertEquals(200, v2.getStatus());
        assertEquals(v2.getEntity(), legacy.getEntity());
        assertEquals(legacy.getHeaders().get("x-correlator"),
                v2.getHeaders().get("x-correlator"));
    }

    @Test
    void shareAlias_parity_sameHandlerResult() {
        stub.result = VerifyResult.approved("req-share", MSISDN_A, AssuranceLevel.HIGH);

        Response legacy = resource.retrievePhoneNumber(
                "corr-share", "Bearer lab", "mobile", null, null, null);
        Response v2 = resource.devicePhoneNumber(
                "corr-share", "Bearer lab", "mobile", null, null, null);

        assertEquals(200, legacy.getStatus());
        assertEquals(v2.getEntity(), legacy.getEntity());
        assertEquals(MSISDN_A,
                toJsonNode(legacy.getEntity()).get("devicePhoneNumber").asText());
    }

    // ---- F2 — opt-in enrichment gating ----

    @Test
    void verify_default_bodyIsPureCamaraBoolean() throws Exception {
        stub.result = approveWithAssurance();
        JsonNode n = verifyToJson(new VerifyRequestDto(MSISDN_A, null),
                null, null);
        assertTrue(n.get("devicePhoneNumberVerified").asBoolean());
        assertEquals("{\"devicePhoneNumberVerified\":true}", mapper.writeValueAsString(
                n));
        assertFalse(n.has("reqId"));
        assertFalse(n.has("decision"));
        assertFalse(n.has("assurance"));
        assertFalse(n.has("fallbackReason"));
    }

    @Test
    void verify_headerOptIn_enrichesWithAssuranceSnapshot() throws Exception {
        stub.result = approveWithAssurance();
        JsonNode n = verifyToJson(new VerifyRequestDto(MSISDN_A, null),
                "true", null);
        assertEquals("APPROVE", n.get("decision").asText());
        assertEquals(85, n.get("assurance").get("score").asInt());
        assertEquals("LOGIN", n.get("assurance").get("riskClass").asText());
    }

    @Test
    void verify_configToggleOn_enrichesWithoutHeader() throws Exception {
        setField(toggles, "assuranceDetailEnabledRaw", "true");
        stub.result = approveWithAssurance();
        JsonNode n = verifyToJson(new VerifyRequestDto(MSISDN_A, null),
                null, null);
        assertEquals("APPROVE", n.get("decision").asText());

        setField(toggles, "assuranceDetailEnabledRaw", "false");
        JsonNode pure = verifyToJson(new VerifyRequestDto(MSISDN_A, null),
                null, null);
        assertFalse(pure.has("decision"));
    }

    @Test
    void verify_fallback_failClosed_pureByDefault_reasonOptIn() throws Exception {
        stub.result = VerifyResult.fallback("req-fb", FallbackReason.LOW_ASSURANCE,
                60, 70, "LOGIN",
                new VerifyResult.Factor(0.0, 0.25), new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(1.0, 0.30), new VerifyResult.Factor(0.0, 0.15));

        JsonNode pure = verifyToJson(new VerifyRequestDto(MSISDN_A, null), null, null);
        assertFalse(pure.get("devicePhoneNumberVerified").asBoolean());
        assertFalse(pure.has("fallbackReason"));

        JsonNode detailed = verifyToJson(new VerifyRequestDto(MSISDN_A, null),
                "true", null);
        assertEquals("LOW_ASSURANCE", detailed.get("fallbackReason").asText());
    }

    // ---- F3 — ErrorInfo contract {status,code,message} ----

    @Test
    void xorViolation_400_INVALID_ARGUMENT_withStatusField() throws Exception {
        Response r = resource.verifyV2(new VerifyRequestDto(null, null),
                "corr", "Bearer lab", "mobile", null, null, null, null, null, null);
        assertError(r, 400, "INVALID_ARGUMENT");

        Response both = resource.verifyV2(new VerifyRequestDto(MSISDN_A, HASH_A),
                "corr", "Bearer lab", "mobile", null, null, null, null, null, null);
        assertError(both, 400, "INVALID_ARGUMENT");
    }

    @Test
    void malformedInputs_400_INVALID_ARGUMENT() {
        Response badPhone = resource.verifyV2(new VerifyRequestDto("+0123", null),
                "corr", "Bearer lab", "mobile", null, null, null, null, null, null);
        assertError(badPhone, 400, "INVALID_ARGUMENT");

        Response badHash = resource.verifyV2(new VerifyRequestDto(null, "xyz"),
                "corr", "Bearer lab", "mobile", null, null, null, null, null, null);
        assertError(badHash, 400, "INVALID_ARGUMENT");
    }

    @Test
    void authFailures_exactCodes() throws Exception {
        enableValidation();
        // Missing Authorization entirely.
        assertError(resource.verifyV2(new VerifyRequestDto(MSISDN_A, null),
                "corr", null, null, null, null, null, null, null, null),
                401, "UNAUTHENTICATED");
        // Signature failure.
        assertError(resource.verifyV2(new VerifyRequestDto(MSISDN_A, null),
                "corr", "Bearer " + makeJwt("wrong-secret", "j1", null, "mobile", null),
                null, null, null, null, null, null, null),
                401, "UNAUTHENTICATED");
        // Scope insufficient → PERMISSION_DENIED.
        assertError(resource.verifyV2(new VerifyRequestDto(MSISDN_A, null),
                "corr", "Bearer " + makeJwt("test-secret", "j2", "sms:send", "mobile", null),
                null, null, null, null, null, null, null),
                403, "PERMISSION_DENIED");
        // No amr evidence → NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK.
        assertError(resource.verifyV2(new VerifyRequestDto(MSISDN_A, null),
                "corr", "Bearer " + makeJwt("test-secret", "j3", "number-verification:verify", null, null),
                null, null, null, null, null, null, null),
                403, "NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK");
    }

    @Test
    void operatorTokenFailure_401_UNAUTHENTICATED_code() {
        resource.operatorTokenSupport = new OperatorTokenSupport() {
            @Override
            public String extractCandidate(String a, String h, String t) {
                return "broken-token";
            }

            @Override
            public EntitlementTokenService.EntitlementRecord resolve(String candidate) {
                return null;
            }
        };
        assertError(resource.verifyV2(new VerifyRequestDto(MSISDN_A, null),
                        "corr", "Bearer operatortoken:broken-token",
                        null, null, null, null, null, null, null),
                401, "UNAUTHENTICATED");
    }

    // ---- F4 — user-bound token compare matrix ----

    @Test
    void bindingMatch_e164_verifiedTrue() throws Exception {
        enableValidation();
        stub.result = resolved(MSISDN_A);
        String token = makeJwt("test-secret", "b1", "number-verification:verify",
                "mobile", "\"phone_number\":\"" + MSISDN_A + "\"");
        JsonNode n = verifyToJsonAuthz(new VerifyRequestDto(MSISDN_A, null), token);
        assertTrue(n.get("devicePhoneNumberVerified").asBoolean(),
                mapper.writeValueAsString(n));
    }

    @Test
    void bindingMismatch_e164_verifiedFalse() throws Exception {
        enableValidation();
        stub.result = resolved(MSISDN_A); // network resolves A…
        String token = makeJwt("test-secret", "b2", "number-verification:verify",
                "mobile", "\"phone_number\":\"" + MSISDN_B + "\""); // …token bound to B
        JsonNode n = verifyToJsonAuthz(new VerifyRequestDto(MSISDN_A, null), token);
        assertFalse(n.get("devicePhoneNumberVerified").asBoolean());
    }

    @Test
    void resolverDisagreesClaim_verifiedFalse_evenWhenBindingMatches()
            throws Exception {
        enableValidation();
        stub.result = resolved(MSISDN_B); // live device is B…
        String token = makeJwt("test-secret", "b3", "number-verification:verify",
                "mobile", "\"phone_number\":\"" + MSISDN_A + "\""); // …claim+bound say A
        JsonNode n = verifyToJsonAuthz(new VerifyRequestDto(MSISDN_A, null), token);
        assertFalse(n.get("devicePhoneNumberVerified").asBoolean());
    }

    @Test
    void noBindingClaim_403_failClosed() {
        enableValidation();
        stub.result = resolved(MSISDN_A);
        String token = makeJwt("test-secret", "b4", "number-verification:verify",
                "mobile", null);
        assertError(resource.verifyV2(new VerifyRequestDto(MSISDN_A, null),
                        "corr", "Bearer " + token, null,
                        null, null, null, null, null, null),
                403, "NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK");
    }

    @Test
    void bindingHashed_match_verifiedTrue_mismatchFalse() throws Exception {
        enableValidation();
        stub.result = resolved(MSISDN_A);

        // Bound A, hash of A → verified.
        String match = makeJwt("test-secret", "b5", "number-verification:verify",
                "mobile", "\"phone_number\":\"" + MSISDN_A + "\"");
        JsonNode ok = verifyToJsonAuthz(new VerifyRequestDto(null, HASH_A), match);
        assertTrue(ok.get("devicePhoneNumberVerified").asBoolean());

        // Bound B, hash of A → mismatch → false.
        String other = makeJwt("test-secret", "b6", "number-verification:verify",
                "mobile", "\"phone_number\":\"" + MSISDN_B + "\"");
        JsonNode no = verifyToJsonAuthz(new VerifyRequestDto(null, HASH_A), other);
        assertFalse(no.get("devicePhoneNumberVerified").asBoolean());
    }

    @Test
    void hashedPath_unnormalizedHashInput_matchesViaNormalization()
            throws Exception {
        enableValidation();
        stub.result = resolved(MSISDN_A);
        // Token bound to the unprefixed form of A — normalized at extraction.
        String token = makeJwt("test-secret", "b7", "number-verification:verify",
                "mobile", "\"msisdn\":\"251911111111\"");
        JsonNode n = verifyToJsonAuthz(new VerifyRequestDto(null, HASH_A), token);
        assertTrue(n.get("devicePhoneNumberVerified").asBoolean());
    }

    @Test
    void labMode_keepsP0Behaviour_noBindingRequired_butNormalized()
            throws Exception {
        // Validation disabled: no binding claim needed; unprefixed claim still
        // compares equal through the single normalization point (F6).
        stub.result = resolved(MSISDN_A);
        JsonNode n = verifyToJson(new VerifyRequestDto("251911111111", null),
                null, null);
        assertTrue(n.get("devicePhoneNumberVerified").asBoolean());

        JsonNode hashed = verifyToJson(new VerifyRequestDto(null, HASH_A),
                null, null);
        assertTrue(hashed.get("devicePhoneNumberVerified").asBoolean());
    }

    // ---- X-Sas-Risk-Class propagation (F2) ----

    @Test
    void riskClassHeader_flowsIntoEvent_garbageIgnored() {
        stub.result = resolved(MSISDN_A);

        resource.verifyV2(new VerifyRequestDto(MSISDN_A, null), "c1", "Bearer lab",
                "mobile", null, null, null, null, "transfer", null);
        assertEquals(et.restlink.sas.fsm.AssurancePolicy.RiskClass.TRANSFER,
                stub.lastEvent.riskClass());

        resource.verifyV2(new VerifyRequestDto(MSISDN_A, null), "c2", "Bearer lab",
                "mobile", null, null, null, null, "NOT_A_CLASS", null);
        assertNull(stub.lastEvent.riskClass());

        resource.verifyV2(new VerifyRequestDto(MSISDN_A, null), "c3", "Bearer lab",
                "mobile", null, null, null, null, null, null);
        assertNull(stub.lastEvent.riskClass());
    }

    // ---- F6 on the share surface ----

    @Test
    void share_responseNormalizedE164_andUnresolvableFailsClosed() {
        stub.result = VerifyResult.approved("req-norm", "251911222222",
                AssuranceLevel.HIGH);
        Response r = resource.devicePhoneNumber(
                "corr", "Bearer lab", "mobile", null, null, null);
        JsonNode n = toJsonNode(r.getEntity());
        assertEquals("+251911222222", n.get("devicePhoneNumber").asText());

        stub.result = VerifyResult.fallback("req-none", FallbackReason.NO_BINDING);
        Response err = resource.devicePhoneNumber(
                "corr", "Bearer lab", "mobile", null, null, null);
        assertError(err, 403, "NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK");
    }

    // ---- helpers ----

    private VerifyResult approveWithAssurance() {
        return VerifyResult.approved("req-approve", MSISDN_A,
                AssuranceLevel.HIGH, 85, 70, "LOGIN",
                new VerifyResult.Factor(1.0, 0.25),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(0.5, 0.15));
    }

    private VerifyResult resolved(String msisdn) {
        return VerifyResult.approved("req-resolved", msisdn, AssuranceLevel.HIGH);
    }

    private JsonNode verifyToJson(VerifyRequestDto body, String assuranceDetailHeader,
                                  String riskClassHeader) {
        Response r = resource.verifyV2(body, "corr", "Bearer lab", "mobile",
                null, null, null, null, riskClassHeader, assuranceDetailHeader);
        assertEquals(200, r.getStatus());
        return toJsonNode(r.getEntity());
    }

    private JsonNode verifyToJsonAuthz(VerifyRequestDto body, String bearerToken) {
        Response r = resource.verifyV2(body, "corr", "Bearer " + bearerToken, null,
                null, null, null, null, null, null);
        assertEquals(200, r.getStatus());
        return toJsonNode(r.getEntity());
    }

    private JsonNode toJsonNode(Object entity) {
        try {
            return mapper.readTree(mapper.writeValueAsString(entity));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertError(Response r, int status, String code) {
        assertEquals(status, r.getStatus());
        JsonNode n = toJsonNode(r.getEntity());
        assertTrue(n.has("status"), () -> "missing status: " + n);
        assertTrue(n.has("code"), () -> "missing code: " + n);
        assertTrue(n.has("message"), () -> "missing message: " + n);
        assertEquals(status, n.get("status").asInt());
        assertEquals(code, n.get("code").asText());
        assertFalse(n.get("message").asText().isBlank());
    }

    private void enableValidation() {
        try {
            setField(config, "tokenValidationEnabled", true);
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

    private static OperatorTokenSupport failClosedOperatorStub() {
        return new OperatorTokenSupport() {
            @Override
            public String extractCandidate(String a, String h, String t) {
                return null;
            }
        };
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == java.util.Optional.class) {
            field.set(obj, java.util.Optional.ofNullable((String) value));
        } else {
            field.set(obj, value);
        }
    }

    /**
     * Signed HS256 test token: camaraScope + amr JSON + extra payload claims
     * (e.g. {@code "phone_number":"+251..."}), lifetime exactly at the 300 s
     * cap, unique jti per call.
     */
    private String makeJwt(String secret, String jtiSeed, String scope,
                           String amr, String extraClaimsJson) {
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
        if (amr != null) {
            p.append(",\"amr\":[\"pwd\",\"").append(amr).append("\"]");
        }
        p.append(",\"exp\":").append(now + 300).append(",\"iat\":").append(now).append("}");
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((header + "." + payloadB64)
                            .getBytes(StandardCharsets.UTF_8)));
            return header + "." + payloadB64 + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
