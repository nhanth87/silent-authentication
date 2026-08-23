/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.security.TokenValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Access-token tests: sign-introspect roundtrip with payload claim shape,
 * tamper, expiry, wrong secret, garbage input, fail-closed blank secret and
 * the consumed-jti registry.
 */
class AccessTokenServiceTest {

    private static final String SECRET = "test-secret";
    private static final String MSISDN = "+251911111111";
    private static final String SCOPE_VERIFY = TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;
    private static final String SCOPE_SHARE =
            TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AccessTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AccessTokenService();
        setSecret(Optional.of(SECRET));
    }

    @Test
    void roundtrip_introspectReturnsBindingAndScopes() {
        String token = service.issue(binding(SCOPE_VERIFY + " " + SCOPE_SHARE));

        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "signed token must be JWS 3-part");

        AccessTokenService.Decoded decoded = service.introspect(token);
        assertNotNull(decoded, "freshly issued token must introspect");
        assertEquals(MSISDN, decoded.msisdn());
        assertEquals(Set.of(SCOPE_VERIFY, SCOPE_SHARE), decoded.scopes());
        assertFalse(decoded.jti().isBlank());
        assertTrue(decoded.expiresEpochSec() > System.currentTimeMillis() / 1000L);
    }

    @Test
    void payloadCarriesCamaraBindingClaims() throws Exception {
        String token = service.issue(binding(SCOPE_VERIFY));
        JsonNode payload = MAPPER.readTree(decodePayload(token));

        assertEquals("sas-restlink", payload.get("iss").asText());
        assertEquals(MSISDN, payload.get("sub").asText());
        assertEquals(MSISDN, payload.get("phone_number").asText(),
                "phone_number claim is the CAMARA user-number binding");
        assertEquals(SCOPE_VERIFY, payload.get("scope").asText());
        assertFalse(payload.get("jti").asText().isBlank());
        assertEquals(300L, payload.get("exp").asLong() - payload.get("iat").asLong());
    }

    @Test
    void tamperedPayload_rejected() {
        String token = service.issue(binding(SCOPE_VERIFY));
        String[] parts = token.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        payload[2] ^= 0x01;
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + parts[2];
        assertNull(service.introspect(tampered), "tampered payload must fail signature");
    }

    @Test
    void wrongSecret_rejected() {
        String forged = forge("{\"iss\":\"sas-restlink\",\"sub\":\"" + MSISDN
                + "\",\"exp\":" + (System.currentTimeMillis() / 1000L + 300)
                + ",\"jti\":\"forged-jti\"}", "other-secret");
        assertNull(service.introspect(forged), "signature from another key must fail");
    }

    @Test
    void expiredToken_rejected() {
        long nowSec = System.currentTimeMillis() / 1000L;
        String expired = forge("{\"iss\":\"sas-restlink\",\"sub\":\"" + MSISDN
                + "\",\"iat\":" + (nowSec - 400) + ",\"exp\":" + (nowSec - 100)
                + ",\"jti\":\"old-jti\"}", SECRET);
        assertNull(service.introspect(expired), "expired token must be rejected");
    }

    @Test
    void garbageInput_rejected() {
        assertNull(service.introspect(null));
        assertNull(service.introspect(""));
        assertNull(service.introspect("garbage"));
        assertNull(service.introspect("not.base64!"));
        assertNull(service.introspect("a.b.c"));
    }

    @Test
    void missingMsisdnClaim_rejected() {
        String noSub = forge("{\"iss\":\"sas-restlink\",\"exp\":"
                + (System.currentTimeMillis() / 1000L + 300) + ",\"jti\":\"no-sub\"}", SECRET);
        assertNull(service.introspect(noSub), "token without a bound msisdn must fail");
    }

    @Test
    void blankSecret_issueThrowsFailClosed() throws Exception {
        setSecret(Optional.empty());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.issue(binding(SCOPE_VERIFY)),
                "blank sas.oauth.secret must refuse issuance");
        assertTrue(e.getMessage().contains("sas.oauth.secret"));
        assertNull(service.introspect("anything"), "introspection also fails closed");
    }

    @Test
    void consumedJtiRegistry_roundTrip() {
        String jti = "some-jti";
        assertFalse(service.isConsumed(jti));
        service.markConsumed(jti);
        assertTrue(service.isConsumed(jti), "consumption must be visible to /verify wiring");
        assertFalse(service.isConsumed(null));
        assertFalse(service.isConsumed(""));
        service.markConsumed(null);
    }

    // ---- plumbing (no Quarkus boot) ----

    private static PendingBinding binding(String scope) {
        long nowSec = System.currentTimeMillis() / 1000L;
        return new PendingBinding("req-id", MSISDN, null,
                Set.of(scope.split(" ")), nowSec, nowSec + 120L);
    }

    private void setSecret(Optional<String> value) throws Exception {
        var field = AccessTokenService.class.getDeclaredField("secret");
        field.setAccessible(true);
        field.set(service, value);
    }

    private static String decodePayload(String token) {
        String[] parts = token.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    /** Forge a token with an attacker-chosen payload and HMAC key. */
    private static String forge(String payloadJson, String hmacSecret) {
        try {
            String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((header + "." + Base64.getUrlEncoder()
                    .withoutPadding().encodeToString(payload))
                    .getBytes(StandardCharsets.UTF_8));
            return header + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
