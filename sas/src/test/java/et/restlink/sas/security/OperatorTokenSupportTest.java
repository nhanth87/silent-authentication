/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import et.restlink.sas.entitlement.EntitlementConfig;
import et.restlink.sas.entitlement.EntitlementTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CIBA operator-token acceptance tests: login_hint parsing, candidate
 * extraction precedence, resolve binding + single-use consumption.
 */
class OperatorTokenSupportTest {

    private static final String MSISDN = "+251911222333";
    private static final String SECRET = "operator-secret";

    private EntitlementTokenService tokens;
    private OperatorTokenSupport support;

    @BeforeEach
    void setUp() throws Exception {
        tokens = new EntitlementTokenService();
        setField(tokens, "config", entitlementConfig(SECRET, true));

        support = new OperatorTokenSupport();
        setField(support, "entitlementTokens", tokens);
        setField(support, "entitlementConfig", entitlementConfig(SECRET, true));
    }

    @Test
    void loginHint_parsed() {
        assertEquals("abc123", OperatorTokenSupport.parseLoginHint("operatortoken:abc123"));
        assertEquals("abc123", OperatorTokenSupport.parseLoginHint(
                "  OPERATORTOKEN:abc123  "), "scheme match is case-insensitive");
        assertNull(OperatorTokenSupport.parseLoginHint(null));
        assertNull(OperatorTokenSupport.parseLoginHint(""));
        assertNull(OperatorTokenSupport.parseLoginHint("otp:sms"));
        assertNull(OperatorTokenSupport.parseLoginHint("operatortoken:"),
                "empty embedded token is not operator-token shaped");
    }

    @Test
    void extractCandidate_bearerScheme_wins() {
        assertEquals("tk1",
                support.extractCandidate("Bearer operatortoken:tk1", null, "hdr-tk"));
        assertEquals("tk-hint",
                support.extractCandidate(null, "operatortoken:tk-hint", null));
    }

    @Test
    void extractCandidate_header_onlyWhenCibaEnabled() throws Exception {
        assertNull(support.extractCandidate(null, null, "hdr-tk"),
                "X-Sas-Operator-Token ignored while ciba-enabled=false");
        setField(support, "entitlementConfig", entitlementConfig(SECRET, true, true));
        assertEquals("hdr-tk", support.extractCandidate(null, null, "hdr-tk"));
    }

    @Test
    void extractCandidate_noOperatorShape_returnsNull() {
        assertNull(support.extractCandidate("Bearer eyJhbGciOi...", null, null));
        assertNull(support.extractCandidate(null, "msisdn:+251911222333", null));
        assertNull(support.extractCandidate(null, null, null));
    }

    @Test
    void resolve_validToken_yieldsBinding_andConsumes() {
        String token = tokens.issueToken(MSISDN, "251910000000002", "EAP-AKA");

        EntitlementTokenService.EntitlementRecord r = support.resolve(token);
        assertNotNull(r, "signed token must resolve to its binding");
        assertEquals(MSISDN, r.msisdn());
        assertEquals("EAP-AKA", r.eapMethod());

        assertNull(support.resolve(token), "resolve consumes the token (single-use)");
    }

    @Test
    void resolve_eapAkaPrime_whitelisted() {
        String token = tokens.issueToken(MSISDN, "251910000000004", "eap-aka-prime");
        EntitlementTokenService.EntitlementRecord r = support.resolve(token);
        assertNotNull(r, "EAP-AKA-prime is inside the B3 whitelist");
        assertEquals(EntitlementTokenService.EAP_AKA_PRIME, r.eapMethod());
    }

    @Test
    void resolve_nonWhitelistedEapMethod_failsClosed() throws Exception {
        // Forge a correctly signed token carrying a scheme we never ran.
        String forged = forgedSignedTokenWithEap("EAP-SIM", "legacy-eap-jti");
        assertNull(support.resolve(forged),
                "non-EAP-AKA schemes must never anchor a Wi-Fi identity");
    }

    @Test
    void resolve_nullEapMethod_failsClosed() throws Exception {
        String forged = forgedSignedTokenWithEap(null, "null-eap-jti");
        assertNull(support.resolve(forged), "missing eapMethod claim must fail closed");
    }

    @Test
    void resolve_garbage_failsClosed() {
        assertNull(support.resolve(null));
        assertNull(support.resolve(""));
        assertNull(support.resolve("tampered-or-garbage"));
    }

    @Test
    void resolve_disabledService_failsClosed() throws Exception {
        String token = tokens.issueToken(MSISDN, "251910000000003", "EAP-AKA");
        EntitlementConfig disabled = entitlementConfig(SECRET, true);
        setField(disabled, "enabled", false);
        setField(support, "entitlementConfig", disabled);
        assertNull(support.resolve(token), "disabled entitlement must reject");
    }

    // ---- plumbing (no Quarkus boot) ----

    /** Correctly signed payload with an attacker-chosen eapMethod claim. */
    private static String forgedSignedTokenWithEap(String eapMethod, String jti)
            throws Exception {
        String eap = eapMethod == null ? "null" : "\"" + eapMethod + "\"";
        long nowSec = System.currentTimeMillis() / 1000L;
        String json = "{\"msisdn\":\"" + MSISDN + "\",\"imsi\":\"251910000000009"
                + "\",\"eapMethod\":" + eap + ",\"iat\":" + nowSec
                + ",\"exp\":" + (nowSec + 300) + ",\"jti\":\"" + jti + "\"}";
        byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    private static EntitlementConfig entitlementConfig(String secret, boolean requireSigned)
            throws Exception {
        return entitlementConfig(secret, requireSigned, false);
    }

    private static EntitlementConfig entitlementConfig(
            String secret, boolean requireSigned, boolean cibaEnabled) throws Exception {
        EntitlementConfig c = new EntitlementConfig();
        setField(c, "enabled", true);
        setField(c, "hmacSecret", secret);
        setField(c, "tokenTtlSeconds", 300L);
        setField(c, "cibaEnabled", cibaEnabled);
        setField(c, "requireSigned", requireSigned);
        return c;
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
}
