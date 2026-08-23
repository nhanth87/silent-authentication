/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 token validation tests: signature/claims, jti-derived replay keys,
 * per-endpoint scope family matching and strict amr resolution.
 */
class TokenValidatorTest {

    private TokenValidator validator;
    private SasSecurityConfig config;

    @BeforeEach
    void setUp() {
        config = new SasSecurityConfig();
        validator = new TokenValidator();
        try {
            var field = TokenValidator.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(validator, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void missingHeader_rejected() {
        assertNotNull(validator.validate(null));
        assertNotNull(validator.validate(""));
    }

    @Test
    void nonBearer_rejected() {
        assertNotNull(validator.validate("Basic abc123"));
    }

    @Test
    void pilotMode_presenceOnly_passes() {
        assertNull(validator.validate("Bearer anything"));
    }

    @Test
    void pilotMode_detailed_stableKeyFromRawHeader() {
        TokenValidator.DetailedAuth auth = validator.validateDetailed("Bearer anything");
        assertTrue(auth.ok());
        assertEquals(RequestValidator.sha256Hex("Bearer anything"), auth.tokenKey());
        // stable across calls → idempotency preserved in lab mode
        assertEquals(auth.tokenKey(), validator.validateDetailed("Bearer anything").tokenKey());
        assertTrue(auth.scopes().isEmpty());
        assertTrue(auth.amrValues().isEmpty());
    }

    @Test
    void validJwt_passes() throws Exception {
        enableValidation();
        String token = makeJwt("test-secret", "iss1", "aud1", "scope1", 3600);
        assertNull(validator.validate("Bearer " + token));
    }

    @Test
    void expiredJwt_rejected() throws Exception {
        enableValidation();
        String token = makeJwt("test-secret", "iss1", "aud1", "scope1", -100);
        assertNotNull(validator.validate("Bearer " + token));
    }

    @Test
    void wrongSecret_rejected() throws Exception {
        enableValidation();
        String token = makeJwt("wrong-secret", "iss1", "aud1", "scope1", 3600);
        assertNotNull(validator.validate("Bearer " + token));
    }

    @Test
    void wrongIssuer_rejected() throws Exception {
        enableValidation();
        String token = makeJwt("test-secret", "wrong-iss", "aud1", "scope1", 3600);
        assertNotNull(validator.validate("Bearer " + token));
    }

    @Test
    void wrongAudience_rejected() throws Exception {
        enableValidation();
        String token = makeJwt("test-secret", "iss1", "wrong-aud", "scope1", 3600);
        assertNotNull(validator.validate("Bearer " + token));
    }

    @Test
    void missingScope_rejected() throws Exception {
        enableValidation();
        String token = makeJwt("test-secret", "iss1", "aud1", null, 3600);
        assertNotNull(validator.validate("Bearer " + token));
    }

    @Test
    void amrMobile_passes() {
        assertNull(validator.validateAmr("mobile"));
        assertNull(validator.validateAmr("mno"));
        assertNull(validator.validateAmr("cellular"));
        assertNull(validator.validateAmr(null));
    }

    @Test
    void amrNonMobile_rejected() {
        assertNotNull(validator.validateAmr("password"));
        assertNotNull(validator.validateAmr("otp"));
    }

    // ---- jti / replay-key derivation ----

    @Test
    void validJwt_jtiClaim_becomesTokenKey() throws Exception {
        enableValidationCamaraScopes();
        String token = makeJwt("test-secret", "iss1", "aud1",
                "number-verification:verify", 3600, "my-jti-123", null);
        TokenValidator.DetailedAuth auth = validator.validateDetailed("Bearer " + token);
        assertTrue(auth.ok());
        assertEquals("my-jti-123", auth.tokenKey());
    }

    @Test
    void validJwt_missingJti_sha256OfRawTokenIsKey() throws Exception {
        enableValidationCamaraScopes();
        String token = makeJwt("test-secret", "iss1", "aud1",
                "number-verification:verify", 3600, null, null);
        TokenValidator.DetailedAuth auth = validator.validateDetailed("Bearer " + token);
        assertTrue(auth.ok());
        assertEquals(RequestValidator.sha256Hex(token), auth.tokenKey());
        assertTrue(auth.tokenKey().matches("[0-9a-f]{64}"));
    }

    @Test
    void validJwt_scopesExposedForEndpointCheck() throws Exception {
        enableValidationCamaraScopes();
        String token = makeJwt("test-secret", "iss1", "aud1",
                "number-verification:verify  number-verification:device-phone-number:read",
                3600, null, null);
        TokenValidator.DetailedAuth auth = validator.validateDetailed("Bearer " + token);
        assertTrue(auth.ok());
        assertTrue(TokenValidator.hasScope(auth.scopes(),
                TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY));
        assertTrue(TokenValidator.hasScope(auth.scopes(),
                TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ));
    }

    // ---- scope family matching ----

    @Test
    void scope_exactMatch() {
        assertTrue(TokenValidator.hasScope(
                Set.of("number-verification:verify"), "number-verification:verify"));
    }

    @Test
    void scope_legacyUnderscoreForm_matches() {
        assertTrue(TokenValidator.hasScope(
                Set.of("number-verification_verify"), "number-verification:verify"));
    }

    @Test
    void scope_familyRoot_grantsSubScopes() {
        assertTrue(TokenValidator.hasScope(
                Set.of("number-verification"), "number-verification:verify"));
        assertTrue(TokenValidator.hasScope(
                Set.of("number-verification"),
                "number-verification:device-phone-number:read"));
    }

    @Test
    void scope_wildcards_match() {
        assertTrue(TokenValidator.hasScope(Set.of("*"), "number-verification:verify"));
        assertTrue(TokenValidator.hasScope(Set.of("number-verification:*"),
                "number-verification:device-phone-number:read"));
    }

    @Test
    void scope_unrelatedScope_rejected() {
        assertFalse(TokenValidator.hasScope(
                Set.of("sms:send"), "number-verification:verify"));
        assertFalse(TokenValidator.hasScope(
                Set.of("number-verification-something"), "number-verification:verify"));
    }

    @Test
    void scope_nullOrEmpty_rejected() {
        assertFalse(TokenValidator.hasScope(null, "number-verification:verify"));
        assertFalse(TokenValidator.hasScope(Set.of(), "number-verification:verify"));
        Set<String> withNull = new java.util.HashSet<>();
        withNull.add(null);
        assertFalse(TokenValidator.hasScope(withNull, "number-verification:verify"));
    }

    // ---- amr resolution (JWT claim preferred over header; fail-closed) ----

    @Test
    void amResolution_jwtPreferredOverHeader() {
        // JWT says password but header claims mobile → signed claim wins, reject.
        assertNotNull(validator.resolveAmrError(List.of("pwd"), "mobile"));
        // JWT proves mobile even when the header is junk.
        assertNull(validator.resolveAmrError(List.of("mobile"), "password"));
    }

    @Test
    void amResolution_headerUsedOnlyWhenJwtSilent() {
        assertNull(validator.resolveAmrError(List.of(), "mno"));
        assertNull(validator.resolveAmrError(null, "cellular"));
        assertNotNull(validator.resolveAmrError(List.of(), "otp"));
    }

    @Test
    void amResolution_noEvidenceAtAll_failsClosed() {
        String err = validator.resolveAmrError(List.of(), null);
        assertNotNull(err);
        err = validator.resolveAmrError(null, "");
        assertNotNull(err);
        err = validator.resolveAmrError(null, null);
        assertNotNull(err);
    }

    @Test
    void validJwt_amrArray_parsedAndResolved() throws Exception {
        enableValidationCamaraScopes();
        String good = makeJwt("test-secret", "iss1", "aud1",
                "number-verification:verify", 3600, "jti-amr-1", "[\"pwd\",\"mobile\"]");
        TokenValidator.DetailedAuth ok = validator.validateDetailed("Bearer " + good);
        assertTrue(ok.ok());
        assertEquals(List.of("pwd", "mobile"), ok.amrValues());
        assertNull(validator.resolveAmrError(ok.amrValues(), null));

        String bad = makeJwt("test-secret", "iss1", "aud1",
                "number-verification:verify", 3600, "jti-amr-2", "[\"pwd\",\"sms\"]");
        TokenValidator.DetailedAuth notOk = validator.validateDetailed("Bearer " + bad);
        assertTrue(notOk.ok());
        assertNotNull(validator.resolveAmrError(notOk.amrValues(), null));

        String single = makeJwt("test-secret", "iss1", "aud1",
                "number-verification:verify", 3600, "jti-amr-3", "\"mobile\"");
        TokenValidator.DetailedAuth str = validator.validateDetailed("Bearer " + single);
        assertTrue(str.ok());
        assertEquals(List.of("mobile"), str.amrValues());
    }

    private void enableValidation() {
        try {
            setField(config, "tokenValidationEnabled", true);
            setField(config, "hmacSecret", "test-secret");
            setField(config, "expectedIssuer", "iss1");
            setField(config, "expectedAudience", "aud1");
            setField(config, "requiredScopesRaw", "scope1");
            setField(config, "clockSkewSeconds", 30L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Validation on, no additional global required-scopes requirement. */
    private void enableValidationCamaraScopes() {
        try {
            enableValidation();
            setField(config, "requiredScopesRaw", "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    private static String makeJwt(String secret, String iss, String aud, String scope,
                                  long expDeltaSec) throws Exception {
        return makeJwt(secret, iss, aud, scope, expDeltaSec, null, null);
    }

    private static String makeJwt(String secret, String iss, String aud, String scope,
                                  long expDeltaSec, String jti, String amrJson) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        StringBuilder payload = new StringBuilder();
        payload.append("{\"iss\":\"").append(iss).append("\"");
        payload.append(",\"aud\":\"").append(aud).append("\"");
        if (scope != null) {
            payload.append(",\"scope\":\"").append(scope).append("\"");
        }
        if (jti != null) {
            payload.append(",\"jti\":\"").append(jti).append("\"");
        }
        if (amrJson != null) {
            payload.append(",\"amr\":").append(amrJson);
        }
        payload.append(",\"exp\":").append(now + expDeltaSec);
        payload.append(",\"iat\":").append(now);
        payload.append("}");
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payloadB64;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + sig;
    }
}
