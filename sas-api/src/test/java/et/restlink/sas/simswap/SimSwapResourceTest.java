/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAMARA SimSwap v2.1.0 surface tests: {@code maxAge} window + spec range,
 * identifier resolution (3-legged binding vs 2-legged body), the CAMARA error
 * codes, single-use/replay/scope gates shared with {@code /verify}, and the
 * fail-closed rule that missing evidence is {@code 404 IDENTIFIER_NOT_FOUND} —
 * never {@code swapped:false}.
 */
class SimSwapResourceTest {

    private static final String MSISDN_A = "+251911111111";
    private static final String SECRET = "test-secret";

    private final ObjectMapper mapper = new ObjectMapper();

    /** Evidence table: msisdn → last SIM change. */
    private final Map<String, Instant> evidence = new HashMap<>();

    private SasSecurityConfig config;
    private SimSwapResource resource;
    private RecordingApiCdrRecorder cdr;
    private int jtiCounter;

    @BeforeEach
    void setUp() throws Exception {
        config = new SasSecurityConfig();
        setField(config, "tokenValidationEnabled", false);
        setField(config, "hmacSecret", SECRET);
        setField(config, "expectedIssuer", "iss1");
        setField(config, "expectedAudience", "aud1");
        setField(config, "requiredScopesRaw", "");
        setField(config, "clockSkewSeconds", 30L);
        setField(config, "replayWindowSeconds", 300L);
        setField(config, "reqIdTtlSeconds", 600L);

        cdr = new RecordingApiCdrRecorder();
        resource = new SimSwapResource();
        resource.cdr = cdr;
        resource.simSwapEvidence = msisdn -> Optional.ofNullable(evidence.get(msisdn));
        resource.tokenValidator = tokenValidatorWith(config);
        resource.replayGuard = replayGuardWith(config);
        resource.accessTokens = new AccessTokenService();
        resource.securityConfig = config;
    }

    // ---- /check — maxAge window ----

    @Test
    void check_recentSwap_swappedTrue() throws Exception {
        evidence.put(MSISDN_A, hoursAgo(1));
        JsonNode n = checkJson(new SimSwapCheckRequest(MSISDN_A, null), "corr-1");
        assertTrue(n.get("swapped").asBoolean());
        assertEquals("{\"swapped\":true}", mapper.writeValueAsString(n));
    }

    @Test
    void check_swapOlderThanDefaultWindow_swappedFalse() throws Exception {
        evidence.put(MSISDN_A, hoursAgo(24 * 30));
        JsonNode n = checkJson(new SimSwapCheckRequest(MSISDN_A, null), "corr-2");
        assertFalse(n.get("swapped").asBoolean());
    }

    @Test
    void check_explicitMaxAge_narrowsTheWindow() throws Exception {
        evidence.put(MSISDN_A, hoursAgo(5));
        assertFalse(checkJson(new SimSwapCheckRequest(MSISDN_A, 1), "corr-3")
                .get("swapped").asBoolean());
        assertTrue(checkJson(new SimSwapCheckRequest(MSISDN_A, 24), "corr-4")
                .get("swapped").asBoolean());
    }

    @Test
    void check_maxAgeBoundary_justInsideIsSwappedJustOutsideIsNot() throws Exception {
        // 30 s inside the 2 h window → swapped; 60 s outside → not swapped.
        evidence.put(MSISDN_A, Instant.now().minus(Duration.ofHours(2)).plusSeconds(30));
        assertTrue(checkJson(new SimSwapCheckRequest(MSISDN_A, 2), "corr-5")
                .get("swapped").asBoolean());

        evidence.put(MSISDN_A, Instant.now().minus(Duration.ofHours(2)).minusSeconds(60));
        assertFalse(checkJson(new SimSwapCheckRequest(MSISDN_A, 2), "corr-6")
                .get("swapped").asBoolean());
    }

    @Test
    void check_maxAgeOutsideSpecRange_400OutOfRange() {
        evidence.put(MSISDN_A, hoursAgo(1));
        assertCamaraError(400, "OUT_OF_RANGE",
                resource.check(new SimSwapCheckRequest(MSISDN_A, 0), "corr-7", "Bearer lab"));
        assertCamaraError(400, "OUT_OF_RANGE",
                resource.check(new SimSwapCheckRequest(MSISDN_A, 2401), "corr-8", "Bearer lab"));
    }

    @Test
    void check_maxAgeAtSpecLimit_accepted() throws Exception {
        evidence.put(MSISDN_A, hoursAgo(1));
        JsonNode n = checkJson(new SimSwapCheckRequest(MSISDN_A, 2400), "corr-9");
        assertTrue(n.get("swapped").asBoolean());
    }

    // ---- /check — identifier + evidence ----

    @Test
    void check_unknownIdentifier_404IdentifierNotFound_neverSwappedFalse() {
        Response r = resource.check(new SimSwapCheckRequest("+251999999999", null),
                "corr-10", "Bearer lab");
        assertCamaraError(404, "IDENTIFIER_NOT_FOUND", r);
    }

    @Test
    void check_noEvidenceSourceWired_failsClosed404() {
        resource.simSwapEvidence = null;
        assertCamaraError(404, "IDENTIFIER_NOT_FOUND",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "corr-11", "Bearer lab"));
    }

    @Test
    void check_evidenceLookupThrows_failsClosed404() {
        resource.simSwapEvidence = msisdn -> {
            throw new IllegalStateException("Sh UDR unavailable");
        };
        assertCamaraError(404, "IDENTIFIER_NOT_FOUND",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "corr-12", "Bearer lab"));
    }

    @Test
    void check_missingPhoneNumber_422MissingIdentifier() {
        assertCamaraError(422, "MISSING_IDENTIFIER",
                resource.check(new SimSwapCheckRequest(null, 24), "corr-13", "Bearer lab"));
        assertCamaraError(422, "MISSING_IDENTIFIER",
                resource.check(null, "corr-14", "Bearer lab"));
    }

    @Test
    void check_malformedPhoneNumber_400InvalidArgument() {
        // normalizeE164 collapses to one leading '+', so a bare digit string is
        // accepted; these are not normalizable to E.164 at all.
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.check(new SimSwapCheckRequest("00251911111", null), "corr-15", "Bearer lab"));
        assertCamaraError(400, "INVALID_ARGUMENT",
                resource.check(new SimSwapCheckRequest("not-a-number", null), "corr-15b", "Bearer lab"));
    }

    @Test
    void check_missingAuthorization_401Unauthenticated() {
        evidence.put(MSISDN_A, hoursAgo(1));
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "corr-16", null));
    }

    // ---- /retrieve-date ----

    @Test
    void retrieveDate_returnsRfc3339InstantAndEchoesCorrelator() throws Exception {
        Instant last = Instant.parse("2026-08-01T06:07:08Z");
        evidence.put(MSISDN_A, last);

        Response r = resource.retrieveDate(new SimSwapDateRequest(MSISDN_A), "corr-17", "Bearer lab");

        assertEquals(200, r.getStatus());
        assertEquals("corr-17", r.getHeaderString("x-correlator"));
        JsonNode n = toJsonNode(r.getEntity());
        assertEquals("2026-08-01T06:07:08Z", n.get("latestSimChange").asText());
        assertFalse(n.has("monitoredPeriod"), "no configured monitoring window is advertised");
        assertEquals("{\"latestSimChange\":\"2026-08-01T06:07:08Z\"}",
                mapper.writeValueAsString(n));
    }

    @Test
    void retrieveDate_unknownIdentifier_404IdentifierNotFound() {
        assertCamaraError(404, "IDENTIFIER_NOT_FOUND",
                resource.retrieveDate(new SimSwapDateRequest("+251999999999"),
                        "corr-18", "Bearer lab"));
    }

    @Test
    void retrieveDate_missingPhoneNumber_422MissingIdentifier() {
        assertCamaraError(422, "MISSING_IDENTIFIER",
                resource.retrieveDate(new SimSwapDateRequest(null), "corr-19", "Bearer lab"));
    }

    // ---- validation enabled: 3-legged identifier + token gates ----

    @Test
    void threeLegged_boundTokenEmptyBody_usesTokenIdentity() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-3leg", "sim-swap:check", "\"phone_number\":\"" + MSISDN_A + "\"");
        Response r = resource.check(new SimSwapCheckRequest(null, 240), "corr-20", "Bearer " + token);

        assertEquals(200, r.getStatus());
        assertTrue(toJsonNode(r.getEntity()).get("swapped").asBoolean());
    }

    @Test
    void threeLegged_explicitPhoneNumber_422UnnecessaryIdentifier() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-unnec", "sim-swap:check", "\"phone_number\":\"" + MSISDN_A + "\"");
        assertCamaraError(422, "UNNECESSARY_IDENTIFIER",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "corr-21", "Bearer " + token));
    }

    @Test
    void threeLegged_tokenWithoutBinding_403PermissionDenied() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-nobind", "sim-swap:check", null);
        assertCamaraError(403, "PERMISSION_DENIED",
                resource.check(new SimSwapCheckRequest(null, null), "corr-22", "Bearer " + token));
    }

    @Test
    void threeLegged_missingScope_403PermissionDenied() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-scope", "number-verification:verify",
                "\"phone_number\":\"" + MSISDN_A + "\"");
        assertCamaraError(403, "PERMISSION_DENIED",
                resource.check(new SimSwapCheckRequest(null, null), "corr-23", "Bearer " + token));
    }

    @Test
    void threeLegged_familyScopeGrantsBothOperations() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String checkToken = makeJwt("jti-family", "sim-swap", "\"phone_number\":\"" + MSISDN_A + "\"");
        assertEquals(200, resource.check(new SimSwapCheckRequest(null, null),
                "corr-24", "Bearer " + checkToken).getStatus());

        String dateToken = makeJwt("jti-family2", "sim-swap", "\"phone_number\":\"" + MSISDN_A + "\"");
        assertEquals(200, resource.retrieveDate(new SimSwapDateRequest(null),
                "corr-25", "Bearer " + dateToken).getStatus());
    }

    @Test
    void threeLegged_familyScopeSemantics_subScopeSatisfiesTheWholeFamily() throws Exception {
        // TokenValidator.hasScope is a family match (same rule as
        // number-verification): one granted sim-swap:* scope satisfies the
        // family, so a check-scoped token also answers retrieve-date.
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-cross", "sim-swap:check", "\"phone_number\":\"" + MSISDN_A + "\"");
        assertEquals(200, resource.retrieveDate(new SimSwapDateRequest(null),
                "corr-26", "Bearer " + token).getStatus());
    }

    @Test
    void threeLegged_unrelatedScope_403PermissionDenied() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-dpv", "dpv:FraudPreventionAndDetection",
                "\"phone_number\":\"" + MSISDN_A + "\"");
        assertCamaraError(403, "PERMISSION_DENIED",
                resource.retrieveDate(new SimSwapDateRequest(null), "corr-26b", "Bearer " + token));
    }

    @Test
    void threeLegged_tokenIsSingleUse() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-reuse", "sim-swap:check", "\"phone_number\":\"" + MSISDN_A + "\"");
        assertEquals(200, resource.check(new SimSwapCheckRequest(null, null),
                "corr-27", "Bearer " + token).getStatus());
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.check(new SimSwapCheckRequest(null, null), "corr-27", "Bearer " + token));
    }

    @Test
    void threeLegged_sameTokenDifferentCorrelator_401Replay() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        String token = makeJwt("jti-replay", "sim-swap:check", "\"phone_number\":\"" + MSISDN_A + "\"");

        // First attempt is refused by the quota gate: the (token, correlator)
        // pair is registered but the token is not consumed.
        resource.tenants = new TenantRegistry() {
            @Override
            public boolean checkAndIncrement(String tenantId) {
                return false;
            }
        };
        assertCamaraError(429, "QUOTA_EXCEEDED",
                resource.check(new SimSwapCheckRequest(null, null), "corr-28", "Bearer " + token));

        resource.tenants = new TenantRegistry();
        Response replayed = resource.check(new SimSwapCheckRequest(null, null),
                "corr-29", "Bearer " + token);
        assertCamaraError(401, "UNAUTHENTICATED", replayed);
        assertTrue(((SimSwapResource.CamaraError) replayed.getEntity()).message()
                .contains("different x-correlator"));
    }

    @Test
    void threeLegged_expiredToken_401Unauthenticated() throws Exception {
        enableValidation();
        evidence.put(MSISDN_A, hoursAgo(1));

        long now = System.currentTimeMillis() / 1000L;
        String token = makeJwtWithLifetime("jti-exp", "sim-swap:check",
                "\"phone_number\":\"" + MSISDN_A + "\"", now - 600, now - 300);
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.check(new SimSwapCheckRequest(null, null), "corr-30", "Bearer " + token));
    }

    // ---- tenant / quota gate ----

    @Test
    void quotaExhausted_429QuotaExceeded() {
        evidence.put(MSISDN_A, hoursAgo(1));
        resource.tenants = new TenantRegistry() {
            @Override
            public boolean checkAndIncrement(String tenantId) {
                return false;
            }
        };
        assertCamaraError(429, "QUOTA_EXCEEDED",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "corr-31", "Bearer lab"));
    }

    @Test
    void unknownApiKey_401Unauthenticated() {
        evidence.put(MSISDN_A, hoursAgo(1));
        resource.tenants = new TenantRegistry() {
            @Override
            public TenantInfo resolve(String apiKey) {
                return null;
            }
        };
        assertCamaraError(401, "UNAUTHENTICATED",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "corr-32", "Bearer lab"));
    }

    @Test
    void errorBody_isCamaraErrorInfoWithCorrelator() {
        Response r = resource.check(new SimSwapCheckRequest("+251999999999", null),
                "corr-33", "Bearer lab");
        assertEquals(404, r.getStatus());
        assertEquals("corr-33", r.getHeaderString("x-correlator"));
        SimSwapResource.CamaraError body = (SimSwapResource.CamaraError) r.getEntity();
        assertEquals(404, body.status());
        assertEquals("IDENTIFIER_NOT_FOUND", body.code());
        assertFalse(body.message().isBlank());
    }

    @Test
    void mask_neverLeaksTheFullMsisdn() {
        assertEquals("+251****11", SimSwapResource.mask(MSISDN_A));
        assertEquals("***", SimSwapResource.mask("+2519"));
        assertEquals("***", SimSwapResource.mask(null));
    }

    // ---- CDR audit ----

    @Test
    void check_successWritesMaskedCompletedCdr() throws Exception {
        evidence.put(MSISDN_A, hoursAgo(1));
        checkJson(new SimSwapCheckRequest(MSISDN_A, 24), "cdr-check-ok");

        ApiCdrRecord record = cdr.last();
        assertEquals("cdr-check-ok", record.correlationId());
        assertEquals("SIMSWAP", record.phase());
        assertEquals("SIMSWAP", record.operation());
        assertEquals("+251****11", record.msisdn());
        assertTrue(record.ok());
        assertEquals(200, record.httpStatus());
        assertNull(record.errorCode());
        assertEquals("lab", record.tenantId());
        assertTrue(record.detail().contains("action=check"));
        assertTrue(record.detail().contains("swapped=true"));
        assertTrue(record.detail().contains("maxAgeHours=24"));
        assertFalse(record.detail().contains(MSISDN_A));
    }

    @Test
    void check_missingEvidenceWritesFailedCdr() {
        assertCamaraError(404, "IDENTIFIER_NOT_FOUND",
                resource.check(new SimSwapCheckRequest("+251999999999", null),
                        "cdr-check-404", "Bearer lab"));

        ApiCdrRecord record = cdr.last();
        assertEquals("+251****99", record.msisdn());
        assertFalse(record.ok());
        assertEquals(404, record.httpStatus());
        assertEquals("IDENTIFIER_NOT_FOUND", record.errorCode());
        assertTrue(record.detail().contains("evidence=ABSENT"));
    }

    @Test
    void retrieveDate_successWritesCompletedCdr() throws Exception {
        evidence.put(MSISDN_A, Instant.parse("2026-08-01T06:07:08Z"));
        Response r = resource.retrieveDate(new SimSwapDateRequest(MSISDN_A),
                "cdr-date-ok", "Bearer lab");
        assertEquals(200, r.getStatus());

        ApiCdrRecord record = cdr.last();
        assertEquals("cdr-date-ok", record.correlationId());
        assertEquals("SIMSWAP", record.phase());
        assertEquals("+251****11", record.msisdn());
        assertTrue(record.ok());
        assertEquals(200, record.httpStatus());
        assertTrue(record.detail().contains("action=retrieve-date"));
        assertTrue(record.detail().contains("result=RETRIEVED"));
        assertFalse(record.detail().contains(MSISDN_A));
    }

    @Test
    void blankCorrelatorStillWritesTraceableCdr() {
        evidence.put(MSISDN_A, hoursAgo(1));
        assertEquals(200, resource.check(new SimSwapCheckRequest(MSISDN_A, null),
                "  ", "Bearer lab").getStatus());

        ApiCdrRecord record = cdr.last();
        assertNotNull(record.correlationId());
        assertFalse(record.correlationId().isBlank());
    }

    @Test
    void quotaExhaustedWritesFailedTenantCdr() {
        evidence.put(MSISDN_A, hoursAgo(1));
        resource.tenants = new TenantRegistry() {
            @Override
            public boolean checkAndIncrement(String tenantId) {
                return false;
            }
        };
        assertCamaraError(429, "QUOTA_EXCEEDED",
                resource.check(new SimSwapCheckRequest(MSISDN_A, null), "cdr-quota", "Bearer lab"));

        ApiCdrRecord record = cdr.last();
        assertFalse(record.ok());
        assertEquals(429, record.httpStatus());
        assertEquals("QUOTA_EXCEEDED", record.errorCode());
        assertEquals("lab", record.tenantId());
        assertEquals("+251****11", record.msisdn());
    }

    // ---- helpers ----

    private void enableValidation() throws Exception {
        setField(config, "tokenValidationEnabled", true);
    }

    private JsonNode checkJson(SimSwapCheckRequest body, String correlator) throws Exception {
        Response r = resource.check(body, correlator, "Bearer lab");
        assertEquals(200, r.getStatus(), () -> "unexpected status: " + r.getStatus());
        assertEquals(correlator, r.getHeaderString("x-correlator"));
        return toJsonNode(r.getEntity());
    }

    private void assertCamaraError(int status, String code, Response r) {
        assertEquals(status, r.getStatus());
        SimSwapResource.CamaraError body = (SimSwapResource.CamaraError) r.getEntity();
        assertEquals(status, body.status());
        assertEquals(code, body.code());
        assertFalse(body.message().isBlank());
    }

    private JsonNode toJsonNode(Object entity) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(entity));
    }

    private static Instant hoursAgo(long hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private String makeJwt(String jtiSeed, String scope, String extraClaimsJson) {
        long now = System.currentTimeMillis() / 1000L;
        return makeJwtWithLifetime(jtiSeed, scope, extraClaimsJson, now, now + 300);
    }

    /** Signed HS256 test token: iss/aud/scope/jti + extra claims, unique jti per call. */
    private String makeJwtWithLifetime(String jtiSeed, String scope, String extraClaimsJson,
                                       long iat, long exp) {
        jtiCounter++;
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
        p.append(",\"exp\":").append(exp).append(",\"iat\":").append(iat).append("}");
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
