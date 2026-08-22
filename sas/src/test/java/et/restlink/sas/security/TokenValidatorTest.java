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

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 token validation tests.
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

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private static String makeJwt(String secret, String iss, String aud, String scope, long expDeltaSec)
            throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        StringBuilder payload = new StringBuilder();
        payload.append("{\"iss\":\"").append(iss).append("\"");
        payload.append(",\"aud\":\"").append(aud).append("\"");
        if (scope != null) {
            payload.append(",\"scope\":\"").append(scope).append("\"");
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
