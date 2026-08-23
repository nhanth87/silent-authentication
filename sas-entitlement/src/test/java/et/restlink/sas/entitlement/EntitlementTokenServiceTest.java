/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signed entitlement token tests: sign-verify roundtrip, tamper, expiry,
 * single-use replay, fail-closed misconfiguration, unsigned lab fallback,
 * B3 EAP-method whitelist and B7 future-iat skew.
 */
class EntitlementTokenServiceTest {

    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "251910000000001";
    private static final String EAP = "EAP-AKA";
    private static final String SECRET = "test-secret";

    private EntitlementTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new EntitlementTokenService();
        inject(service, newConfig(SECRET, true));
    }

    @Test
    void signVerifyRoundtrip_singleUse() {
        String token = service.issueToken(MSISDN, IMSI, EAP);
        assertNotNull(token);
        assertTrue(token.indexOf('.') > 0, "signed token must have two parts");

        EntitlementTokenService.EntitlementRecord r = service.exchange(token);
        assertNotNull(r, "valid signed token must exchange");
        assertEquals(MSISDN, r.msisdn());
        assertEquals(IMSI, r.imsi());
        assertEquals(EAP, r.eapMethod());
        assertTrue(r.expiresEpochMs() > r.issuedEpochMs());

        assertNull(service.exchange(token), "second exchange must fail (single-use)");
    }

    @Test
    void tamperedPayload_rejected() {
        String token = service.issueToken(MSISDN, IMSI, EAP);
        int dot = token.indexOf('.');
        byte[] payload = Base64.getUrlDecoder().decode(token.substring(0, dot));
        payload[2] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + token.substring(dot);
        assertNull(service.exchange(tampered), "tampered payload must fail signature");
    }

    @Test
    void expired_rejected() throws Exception {
        EntitlementConfig zeroTtl = newConfig("test-secret", true);
        set(zeroTtl, "tokenTtlSeconds", 0L);
        inject(service, zeroTtl);

        String token = service.issueToken(MSISDN, IMSI, EAP);
        assertNotNull(token);
        assertNull(service.exchange(token), "expired token must be rejected");
        assertFalse(service.isValid(token));
    }

    @Test
    void replay_secondExchange_rejected() {
        String token = service.issueToken(MSISDN, IMSI, EAP);
        assertNotNull(service.exchange(token));
        assertFalse(service.isValid(token), "consumed jti must fail pre-check");
        assertNull(service.exchange(token), "replayed token must be rejected");
    }

    @Test
    void blankSecret_requireSigned_throwsAtIssueTime() throws Exception {
        inject(service, newConfig("", true));
        assertThrows(IllegalStateException.class,
                () -> service.issueToken(MSISDN, IMSI, EAP),
                "blank secret with require-signed=true must throw (fail-closed)");
    }

    @Test
    void unsignedFallback_whenEnforcementDisabled() throws Exception {
        inject(service, newConfig("", false));

        String token = service.issueToken(MSISDN, IMSI, EAP);
        assertNotNull(token);
        assertEquals(-1, token.indexOf('.'), "fallback token stays opaque");

        EntitlementTokenService.EntitlementRecord r = service.exchange(token);
        assertNotNull(r);
        assertEquals(MSISDN, r.msisdn());
        assertNull(service.exchange(token), "unsigned tokens stay single-use");
    }

    @Test
    void wrongSecret_rejected() throws Exception {
        String token = service.issueToken(MSISDN, IMSI, EAP);
        inject(service, newConfig("other-secret", true));
        assertNull(service.exchange(token), "signature from another secret must fail");
    }

    // ---- B3: eapMethod whitelist + canonical encoding ----

    @Test
    void eapAkaPrime_spellings_normalizeToCanonicalEncoding() {
        String apostrophe = service.exchange(
                service.issueToken(MSISDN, IMSI, "EAP-AKA'")).eapMethod();
        assertEquals("EAP-AKA'", apostrophe);

        String primeWord = service.exchange(
                service.issueToken(MSISDN, IMSI, "eap-aka-prime")).eapMethod();
        assertEquals("EAP-AKA'", primeWord);
    }

    @Test
    void unknownEapMethod_rejectedAtIssue_failClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> service.issueToken(MSISDN, IMSI, "EAP-SIM"));
        assertThrows(IllegalArgumentException.class,
                () -> service.issueToken(MSISDN, IMSI, "EAP-TLS"));
        assertThrows(IllegalArgumentException.class,
                () -> service.issueToken(MSISDN, IMSI, null));
    }

    // ---- B7: future-iat skew (mirror of TokenValidator) ----

    @Test
    void futureIat_beyondSkewWindow_rejected() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        String forged = forgedSignedToken(nowSec + 3600, nowSec + 3900, "future-iat-jti");
        assertFalse(service.isValid(forged), "iat >60s in the future must fail pre-check");
        assertNull(service.exchange(forged), "future-dated token must never be consumable");
    }

    @Test
    void futureIat_withinSkewWindow_stillValid() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        String token = forgedSignedToken(nowSec + 30, nowSec + 330, "skew-ok-jti");
        assertTrue(service.isValid(token));
        assertNotNull(service.exchange(token));
    }

    /** Correctly signed payload with attacker-chosen claims (valid HMAC). */
    private static String forgedSignedToken(long iatSec, long expSec, String jti)
            throws Exception {
        String json = "{\"msisdn\":\"" + MSISDN + "\",\"imsi\":\"" + IMSI
                + "\",\"eapMethod\":\"" + EAP + "\",\"iat\":" + iatSec
                + ",\"exp\":" + expSec + ",\"jti\":\"" + jti + "\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    @Test
    void garbage_rejected() {
        assertNull(service.exchange(null));
        assertNull(service.exchange(""));
        assertNull(service.exchange("garbage"));
        assertNull(service.exchange("not.base64!"));
        assertNull(service.exchange("a.b.c"));
    }

    @Test
    void activeTokenCount_tracksUnconsumedSignedTokens() {
        int before = service.activeTokenCount();
        String t1 = service.issueToken(MSISDN, IMSI, EAP);
        String t2 = service.issueToken(MSISDN, IMSI, EAP);
        assertEquals(before + 2, service.activeTokenCount());
        service.exchange(t1);
        assertEquals(before + 1, service.activeTokenCount(), "consumed jti leaves active set");
        service.exchange(t2);
        assertEquals(before, service.activeTokenCount());
    }

    // ---- plumbing (no Quarkus boot) ----

    private static EntitlementConfig newConfig(String secret, boolean requireSigned)
            throws Exception {
        EntitlementConfig c = new EntitlementConfig();
        set(c, "enabled", true);
        set(c, "hmacSecret", secret);
        set(c, "tokenTtlSeconds", 300L);
        set(c, "cibaEnabled", false);
        set(c, "requireSigned", requireSigned);
        return c;
    }

    private static void inject(Object target, Object dependency) throws Exception {
        var field = target.getClass().getDeclaredField("config");
        field.setAccessible(true);
        field.set(target, dependency);
    }

    private static void set(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == java.util.Optional.class) {
            field.set(obj, java.util.Optional.ofNullable((String) value));
        } else {
            field.set(obj, value);
        }
    }
}
