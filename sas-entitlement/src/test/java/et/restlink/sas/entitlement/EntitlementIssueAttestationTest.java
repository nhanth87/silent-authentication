/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import et.restlink.sas.security.ApiKeyAuthenticator;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1 — AAA/EAP attestation on {@code POST /entitlement/issue}: valid MAC
 * passes once, replay/expiry/tamper fail closed, required-but-blank secret
 * rejects everything (503), and the disabled path keeps the old behaviour.
 */
class EntitlementIssueAttestationTest {

    private static final String SECRET = "attest-secret";
    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";
    private static final String EAP = "EAP-AKA";

    private EntitlementConfig config;
    private EntitlementResource resource;

    @BeforeEach
    void wire() throws Exception {
        config = new EntitlementConfig();
        set(config, "enabled", true);
        set(config, "tokenTtlSeconds", 300L);
        set(config, "requireSigned", false);
        set(config, "hmacSecret", Optional.empty());
        set(config, "issueAttestationRequired", false);
        set(config, "issueAttestationSecret", Optional.<String>empty());

        ApiKeyAuthenticator apiKeys = new ApiKeyAuthenticator();
        set(apiKeys, "config", new et.restlink.sas.security.SasSecurityConfig());

        EntitlementTokenService tokens = new EntitlementTokenService();
        set(tokens, "config", config);

        AttestationVerifier attestation = new AttestationVerifier();
        set(attestation, "config", config);

        resource = new EntitlementResource();
        resource.config = config;
        resource.tokenService = tokens;
        resource.apiKeys = apiKeys;
        resource.attestation = attestation;
    }

    // ---- happy path ----

    @Test
    void validMac_issuesTokenOnce() throws Exception {
        requireAttestation(true, SECRET);
        long ts = System.currentTimeMillis();
        Response r = resource.issue(new EntitlementResource.IssueRequest(MSISDN, IMSI, EAP),
                null, Long.toString(ts), mac(ts));
        assertEquals(200, r.getStatus());
        var body = (EntitlementResource.IssueResponse) r.getEntity();
        assertFalse(body.token().isBlank());
        assertEquals(300L, body.expiresInSeconds());
    }

    // ---- single use ----

    @Test
    void sameMacReplayed_blocked() throws Exception {
        requireAttestation(true, SECRET);
        long ts = System.currentTimeMillis();
        String m = mac(ts);
        Response first = resource.issue(request(), null, Long.toString(ts), m);
        Response second = resource.issue(request(), null, Long.toString(ts), m);
        assertEquals(200, first.getStatus());
        assertRejected(second, 401, "ATTESTATION_REPLAY");
    }

    // ---- freshness ----

    @Test
    void timestampOutsideWindow_expired() throws Exception {
        requireAttestation(true, SECRET);
        long now = System.currentTimeMillis();
        assertRejected(resource.issue(request(), null,
                Long.toString(now - 61_000L), mac(now - 61_000L)),
                401, "ATTESTATION_EXPIRED");
        assertRejected(resource.issue(request(), null,
                Long.toString(now + 61_000L), mac(now + 61_000L)),
                401, "ATTESTATION_EXPIRED");
        // inside the window still passes (fresh MAC, never seen before)
        long fresh = now - 59_000L;
        assertEquals(200, resource.issue(request(), null,
                Long.toString(fresh), mac(fresh)).getStatus());
    }

    // ---- integrity ----

    @Test
    void tamperedMac_invalid() throws Exception {
        requireAttestation(true, SECRET);
        long ts = System.currentTimeMillis();
        String forged = mac(ts);
        forged = ("f" + forged.substring(1)).equals(forged) ? "0" + forged.substring(1)
                : "f" + forged.substring(1);
        assertRejected(resource.issue(request(), null, Long.toString(ts), forged),
                401, "ATTESTATION_INVALID");
    }

    @Test
    void missingHeaders_invalid() throws Exception {
        requireAttestation(true, SECRET);
        assertRejected(resource.issue(request(), null, null, null),
                401, "ATTESTATION_INVALID");
        assertRejected(resource.issue(request(), null,
                Long.toString(System.currentTimeMillis()), null),
                401, "ATTESTATION_INVALID");
    }

    // ---- misconfiguration fails closed ----

    @Test
    void requiredButBlankSecret_misconfigured503() throws Exception {
        requireAttestation(true, "");
        long ts = System.currentTimeMillis();
        assertRejected(resource.issue(request(), null, Long.toString(ts), mac(ts)),
                503, "ATTESTATION_MISCONFIGURED");
    }

    // ---- disabled path unchanged ----

    @Test
    void attestationDisabled_behaviourUnchangedNoHeadersNeeded() throws Exception {
        requireAttestation(false, "");
        Response r = resource.issue(new EntitlementResource.IssueRequest(MSISDN, IMSI, EAP),
                null, null, null);
        assertEquals(200, r.getStatus());
        assertFalse(((EntitlementResource.IssueResponse) r.getEntity()).token().isBlank());
    }

    // ---- helpers ----

    private static EntitlementResource.IssueRequest request() {
        return new EntitlementResource.IssueRequest(MSISDN, IMSI, EAP);
    }

    private void requireAttestation(boolean required, String secret) throws Exception {
        set(config, "issueAttestationRequired", required);
        set(config, "issueAttestationSecret",
                secret == null || secret.isEmpty()
                        ? Optional.<String>empty() : Optional.of(secret));
    }

    /** Test-side MAC over the canonical input (shared construction path). */
    private static String mac(long ts) {
        return AttestationVerifier.hmacSha256Hex(SECRET,
                AttestationVerifier.inputString(MSISDN, IMSI, EAP, ts));
    }

    private static void assertRejected(Response r, int status, String code) {
        assertEquals(status, r.getStatus());
        assertTrue(r.getEntity() instanceof Map<?, ?>, () -> "unexpected entity: " + r.getEntity());
        Map<?, ?> body = (Map<?, ?>) r.getEntity();
        assertEquals(code, body.get("code"), () -> "body: " + body);
        assertNotNull(body.get("message"));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
