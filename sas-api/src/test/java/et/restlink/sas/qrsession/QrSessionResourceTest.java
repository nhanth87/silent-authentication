/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.qrsession;

import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.api.TenantRegistry;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.oauth.AccessTokenService;
import et.restlink.sas.oauth.IdentityAnchor;
import et.restlink.sas.security.ReplayGuard;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;
import et.restlink.sas.subscriber.SubscriberService;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QR hand-off exists so a desktop login can be proven by a phone. These tests
 * hold the line that makes it safe to ship: <strong>every</strong> outcome the
 * bank can observe is either a verdict the one verify FSM produced, or a refusal.
 * There is no path through this resource that approves on its own.
 */
class QrSessionResourceTest {

    private static final String MSISDN = "+251911111111";

    private StubBootstrap stub;
    private QrSessionResource resource;

    /** Deterministic core: returns a canned verdict and captures what was asked. */
    static class StubBootstrap implements SasVerifyEngine {
        VerifyResult result;
        VerifyRequestEvent lastEvent;
        boolean failWith;

        @Override
        public CompletableFuture<VerifyResult> submit(VerifyRequestEvent evt) {
            lastEvent = evt;
            if (failWith) {
                CompletableFuture<VerifyResult> broken = new CompletableFuture<>();
                broken.completeExceptionally(new IllegalStateException("core down"));
                return broken;
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void release(String reqId) {
            // no container in unit tests
        }

        @Override
        public SubscriberService subscribers() {
            return null;
        }

        @Override
        public String afId() {
            return "sas-camara";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubBootstrap();
        stub.result = VerifyResult.approved("req", MSISDN, AssuranceLevel.HIGH);

        SasSecurityConfig config = new SasSecurityConfig();
        setField(config, "tokenValidationEnabled", false);
        setField(config, "hmacSecret", "test-secret");
        setField(config, "expectedIssuer", "iss1");
        setField(config, "expectedAudience", "aud1");
        setField(config, "requiredScopesRaw", "");
        setField(config, "clockSkewSeconds", 30L);
        setField(config, "replayWindowSeconds", 300L);

        resource = new QrSessionResource();
        resource.sessions = new QrSessionStore();
        resource.bootstrap = stub;
        resource.tokenValidator = withConfig(new TokenValidator(), config);
        resource.replayGuard = withConfig(new ReplayGuard(), config);
        resource.accessTokens = new AccessTokenService();
        resource.securityConfig = config;
        resource.identityAnchor = candidate -> null;   // fail-closed: no TS.43 here
        resource.cdr = et.restlink.sas.api.ApiCdrRecorder.NOOP;

        TenantRegistry registry = new TenantRegistry();
        setField(registry, "enforceApiKeys", false);
        setField(registry, "apiKeysRaw", "");
        resource.tenants = registry;
        resource.httpHeaders = fixedHeaders("X-Api-Key", null);
    }

    // ---- happy path: the bank opens, the phone claims, the bank polls ----

    @Test
    void claimRunsTheOrdinaryVerifyFsmAndBanksSeeItsVerdict() {
        String sessionId = openSession(MSISDN);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "LTE", null),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(200, claim.getStatus());
        QrSessionRequests.ClaimResponse verdict =
                (QrSessionRequests.ClaimResponse) claim.getEntity();
        assertTrue(verdict.devicePhoneNumberVerified(),
                "an approved FSM verdict must surface as verified");
        assertEquals("RESOLVED", verdict.state());

        assertNotNull(stub.lastEvent, "the claim must go through the verify FSM");
        assertEquals(MSISDN, stub.lastEvent.claimedMsisdn());
        assertEquals(AccessTech.LTE, stub.lastEvent.accessTech());
        assertFalse(stub.lastEvent.operatorAnchored());

        Response status = resource.status(sessionId, null);
        QrSessionRequests.StatusResponse seen =
                (QrSessionRequests.StatusResponse) status.getEntity();
        assertEquals("RESOLVED", seen.state());
        assertTrue(seen.devicePhoneNumberVerified());
        assertEquals("APPROVED", seen.decision());
    }

    @Test
    void pendingSessionIsNotAuthenticated() {
        String sessionId = openSession(MSISDN);

        QrSessionRequests.StatusResponse seen =
                (QrSessionRequests.StatusResponse) resource.status(sessionId, null).getEntity();

        assertEquals("PENDING", seen.state());
        assertNull(seen.devicePhoneNumberVerified(),
                "PENDING must carry no verdict — the bank reads null as 'not yet'");
    }

    // ---- fail-closed: the FSM said no ----

    @Test
    void fallbackFromTheCoreSurfacesAsUnverified() {
        stub.result = VerifyResult.fallback("req", FallbackReason.SIM_SWAP_SUSPECT,
                60, 70, "LOGIN",
                new VerifyResult.Factor(1.0, 0.25), new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(0.0, 0.30), new VerifyResult.Factor(1.0, 0.15));
        String sessionId = openSession(MSISDN);

        QrSessionRequests.ClaimResponse verdict = (QrSessionRequests.ClaimResponse)
                resource.claim(sessionId,
                        new QrSessionRequests.ClaimRequest(null, "NR", null),
                        null, null, "10.20.30.40", "55555", null).getEntity();

        assertFalse(verdict.devicePhoneNumberVerified());
        assertEquals("SIM_SWAP_SUSPECT", verdict.fallbackReason());
    }

    @Test
    void coreFailureFailsClosed() {
        stub.failWith = true;
        String sessionId = openSession(MSISDN);

        QrSessionRequests.ClaimResponse verdict = (QrSessionRequests.ClaimResponse)
                resource.claim(sessionId,
                        new QrSessionRequests.ClaimRequest(null, "LTE", null),
                        null, null, "10.20.30.40", "55555", null).getEntity();

        assertFalse(verdict.devicePhoneNumberVerified(),
                "a broken core must never read as an approval");
        assertEquals("NETWORK_ERROR", verdict.fallbackReason());
    }

    // ---- refusals ----

    @Test
    void unknownSessionIsNotFound() {
        assertEquals(404, resource.claim("no-such-session",
                new QrSessionRequests.ClaimRequest(null, "LTE", null),
                null, null, "10.20.30.40", "55555", null).getStatus());
        assertEquals(404, resource.status("no-such-session", null).getStatus());
        assertNull(stub.lastEvent, "an unknown session must not reach the core");
    }

    @Test
    void expiredSessionIsNotFound() throws Exception {
        String sessionId = openSession(MSISDN);
        // age the session past its budget without waiting two minutes
        resource.sessions.create(new QrSessionStore.QrSession(sessionId, MSISDN,
                "corr", "lab-tenant", System.currentTimeMillis()
                - QrSessionStore.SESSION_TTL_MS - 1));

        assertEquals(404, resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "LTE", null),
                null, null, "10.20.30.40", "55555", null).getStatus());
        assertNull(stub.lastEvent, "an expired session must not reach the core");
    }

    @Test
    void secondHandsetOnTheSameCodeIsRefused() {
        String sessionId = openSession(MSISDN);
        claimOk(sessionId);

        Response second = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "LTE", null),
                null, null, "10.20.30.41", "55556", null);

        assertEquals(409, second.getStatus(),
                "two handsets on one code would make the evidence ambiguous");
    }

    @Test
    void wifiClaimIsRefusedOnTheIpMatchHandOff() {
        String sessionId = openSession(MSISDN);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "WIFI", null),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(400, claim.getStatus());
        assertEquals("ACCESS_TECH_NOT_CELLULAR", codeOf(claim));
        assertNull(stub.lastEvent, "a non-cellular bearer must not seed an IP binding");
    }

    @Test
    void fixedAccessClaimIsRefused() {
        String sessionId = openSession(MSISDN);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "FIXED", null),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(400, claim.getStatus());
        assertNull(stub.lastEvent);
    }

    @Test
    void unknownBearerNameIsRefusedRatherThanGuessed() {
        String sessionId = openSession(MSISDN);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "HOLOGRAM", null),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(400, claim.getStatus());
        assertEquals("INVALID_ARGUMENT", codeOf(claim));
        assertNull(stub.lastEvent, "an unparseable declaration must not become a default");
    }

    @Test
    void discoverySessionRequiresThePhoneToSupplyTheNumber() {
        String sessionId = openSession(null);   // discovery variant

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "LTE", null),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(422, claim.getStatus());
        assertEquals("MISSING_IDENTIFIER", codeOf(claim));
        assertNull(stub.lastEvent);
    }

    @Test
    void discoverySessionProceedsWhenThePhoneSuppliesTheNumber() {
        String sessionId = openSession(null);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(MSISDN, "LTE", null),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(200, claim.getStatus());
        assertEquals(MSISDN, stub.lastEvent.claimedMsisdn());
    }

    @Test
    void operatorTokenIsPassedToTheAnchorWithoutTheSchemePrefix() {
        // The anchor redeems the BARE token at the entitlement server. Re-adding
        // the `operatortoken:` scheme here would send sas-ecs a token it never
        // issued, and every Wi-Fi claim would fail with a bare 401.
        java.util.List<String> seen = new java.util.ArrayList<>();
        resource.identityAnchor = candidate -> {
            seen.add(candidate);
            return new IdentityAnchor.OperatorBinding(MSISDN, "655010000000001", "EAP-AKA");
        };
        String sessionId = openSession(null);   // discovery variant: the token names the number

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "WIFI", "tk-123"),
                null, null, "192.168.1.55", "55555", null);

        assertEquals(java.util.List.of("tk-123"), seen,
                "the anchor must see the bare token, not operatortoken:tk-123");
        assertEquals(200, claim.getStatus());
        assertEquals(MSISDN, stub.lastEvent.claimedMsisdn(),
                "a redeemed operator token is authoritative for the number");
        assertEquals(AccessTech.WIFI, stub.lastEvent.accessTech());
        assertTrue(stub.lastEvent.operatorAnchored(),
                "the Wi-Fi variant must be marked as operator-anchored");
    }

    @Test
    void aWifiClaimIsAllowedWhenAnOperatorTokenIsPresent() {
        resource.identityAnchor = candidate ->
                new IdentityAnchor.OperatorBinding(MSISDN, "655010000000001", "EAP-AKA");
        String sessionId = openSession(MSISDN);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "WIFI", "tk-123"),
                null, null, "192.168.1.55", "55555", null);

        assertEquals(200, claim.getStatus(),
                "the TS.43 path is exactly the Wi-Fi case; the bearer gate must not block it");
    }

    @Test
    void operatorTokenIsRejectedWhenTheAnchorCannotResolveIt() {
        String sessionId = openSession(MSISDN);

        Response claim = resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "WIFI", "not-a-real-token"),
                null, null, "10.20.30.40", "55555", null);

        assertEquals(401, claim.getStatus());
        assertNull(stub.lastEvent,
                "an unresolved TS.43 token must not reach the verify FSM");
    }

    @Test
    void createRejectsANonE164Number() {
        Response created = resource.create(
                new QrSessionRequests.CreateRequest("0911111111"), "corr", "Bearer lab");

        assertEquals(400, created.getStatus());
        assertEquals("INVALID_ARGUMENT", codeOf(created));
    }

    @Test
    void qrPayloadCarriesOnlyTheOpaqueId() {
        Response created = resource.create(
                new QrSessionRequests.CreateRequest(MSISDN), "corr", "Bearer lab");

        QrSessionRequests.CreateResponse body =
                (QrSessionRequests.CreateResponse) created.getEntity();
        assertNotNull(body.sessionId());
        assertTrue(body.qrPayload().endsWith(body.sessionId()));
        assertFalse(body.qrPayload().contains(MSISDN),
                "the QR image must not leak the number the bank is asking about");
        assertFalse(body.qrPayload().contains("tenant"),
                "the QR image must not leak billing context");
    }

    // ---- helpers ----

    private String openSession(String phoneNumber) {
        Response created = resource.create(
                new QrSessionRequests.CreateRequest(phoneNumber), "corr-1", "Bearer lab");
        assertEquals(200, created.getStatus());
        return ((QrSessionRequests.CreateResponse) created.getEntity()).sessionId();
    }

    private void claimOk(String sessionId) {
        assertEquals(200, resource.claim(sessionId,
                new QrSessionRequests.ClaimRequest(null, "LTE", null),
                null, null, "10.20.30.40", "55555", null).getStatus());
    }

    private static String codeOf(Response response) {
        return ((QrSessionRequests.QrError) response.getEntity()).code();
    }

    private static <T> T withConfig(T target, SasSecurityConfig config) {
        try {
            var field = target.getClass().getDeclaredField("config");
            field.setAccessible(true);
            field.set(target, config);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return target;
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

    /** Minimal container-headers stub carrying exactly one header. */
    private static HttpHeaders fixedHeaders(String name, String value) {
        return new HttpHeaders() {
            @Override
            public List<Locale> getAcceptableLanguages() {
                return List.of();
            }

            @Override
            public List<MediaType> getAcceptableMediaTypes() {
                return List.of();
            }

            @Override
            public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
                return java.util.Map.of();
            }

            @Override
            public java.util.Date getDate() {
                return null;
            }

            @Override
            public String getHeaderString(String header) {
                return name.equalsIgnoreCase(header) ? value : null;
            }

            @Override
            public List<String> getRequestHeader(String header) {
                return name.equalsIgnoreCase(header) && value != null
                        ? List.of(value) : List.of();
            }

            @Override
            public Locale getLanguage() {
                return null;
            }

            @Override
            public int getLength() {
                return -1;
            }

            @Override
            public MediaType getMediaType() {
                return null;
            }

            @Override
            public jakarta.ws.rs.core.MultivaluedMap<String, String> getRequestHeaders() {
                var map = new jakarta.ws.rs.core.MultivaluedHashMap<String, String>();
                if (value != null) {
                    map.putSingle(name, value);
                }
                return map;
            }
        };
    }
}
