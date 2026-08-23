/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.security.TokenValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CIBA authorization-request tests: cellular binding happy path, ambiguous /
 * no-binding / timeout failures, operator-token anchor via the seam,
 * scope validation matrix and atomic single-use consumption.
 */
class AuthorizationRequestServiceTest {

    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "251910000000001";
    private static final String VALID_TOKEN = "valid-token";
    private static final String SCOPE_VERIFY = TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;
    private static final String SCOPE_SHARE =
            TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ;

    private AuthorizationRequestService service;

    @BeforeEach
    void setUp() {
        service = new AuthorizationRequestService();
        inject(service, (IdentityAnchor) candidate ->
                VALID_TOKEN.equals(candidate)
                        ? new IdentityAnchor.OperatorBinding(MSISDN, IMSI)
                        : null);
        service.resolverOverride = (srcIp, srcPort, ts) ->
                CompletableFuture.completedFuture(ResolverResult.bound(MSISDN, IMSI, 0L));
    }

    // ---- cellular path ----

    @Test
    void cellularBindHappy_mints22CharId_andPollReturnsBinding() {
        AuthorizationRequestService.AuthRequest auth =
                service.start(null, "10.20.30.40", 55555, SCOPE_VERIFY);

        assertNotNull(auth.authReqId());
        assertEquals(22, auth.authReqId().length(), "16 random bytes -> 22 b64url chars");
        assertEquals(16, Base64.getUrlDecoder().decode(auth.authReqId()).length);
        assertEquals(AuthorizationRequestService.AUTH_REQ_TTL_SECONDS, auth.expiresInSeconds());

        PendingBinding binding = service.poll(auth.authReqId());
        assertNotNull(binding, "live binding must be pollable");
        assertEquals(MSISDN, binding.msisdn());
        assertEquals(IMSI, binding.imsi());
        assertEquals(Set.of(SCOPE_VERIFY), binding.scopes());
        assertEquals(AuthorizationRequestService.AUTH_REQ_TTL_SECONDS,
                binding.expiresEpochSec() - binding.issuedEpochSec());
    }

    @Test
    void cellularBind_normalizesMsisdn() {
        service.resolverOverride = (ip, port, ts) -> CompletableFuture.completedFuture(
                ResolverResult.bound("251911111111", IMSI, 0L));

        PendingBinding binding = service.poll(
                service.start(null, "10.20.30.40", 0, SCOPE_VERIFY).authReqId());
        assertNotNull(binding);
        assertEquals(MSISDN, binding.msisdn(), "resolver msisdn must normalize to +E164");
    }

    @Test
    void ambiguousBinding_failsAccessDenied() {
        service.resolverOverride = (ip, port, ts) -> CompletableFuture.completedFuture(
                ResolverResult.miss(FallbackReason.AMBIGUOUS_BINDING));
        CibaException e = assertThrows(CibaException.class,
                () -> service.start(null, "10.20.30.40", 55555, SCOPE_VERIFY));
        assertEquals("access_denied", e.error());
        assertEquals(403, e.httpStatus());
    }

    @Test
    void noBinding_failsAccessDenied() {
        service.resolverOverride = (ip, port, ts) -> CompletableFuture.completedFuture(
                ResolverResult.miss(FallbackReason.NO_BINDING));
        CibaException e = assertThrows(CibaException.class,
                () -> service.start(null, "10.20.30.40", 55555, SCOPE_VERIFY));
        assertEquals("access_denied", e.error());
    }

    @Test
    void resolverError_failsAccessDenied() {
        service.resolverOverride = (ip, port, ts) ->
                CompletableFuture.failedFuture(new RuntimeException("backend down"));
        CibaException e = assertThrows(CibaException.class,
                () -> service.start(null, "10.20.30.40", 55555, SCOPE_VERIFY));
        assertEquals("access_denied", e.error());
    }

    @Test
    void resolverTimeout_failsAccessDenied() {
        service.resolverOverride = (ip, port, ts) -> new CompletableFuture<>();
        CibaException e = assertThrows(CibaException.class,
                () -> service.start(null, "10.20.30.40", 55555, SCOPE_VERIFY));
        assertEquals("access_denied", e.error());
    }

    @Test
    void blankSrcIp_withoutHint_invalidRequest() {
        CibaException e = assertThrows(CibaException.class,
                () -> service.start(null, "  ", 0, SCOPE_VERIFY));
        assertEquals("invalid_request", e.error());
        assertEquals(400, e.httpStatus());
    }

    @Test
    void unsupportedLoginHintShape_invalidRequest() {
        CibaException e = assertThrows(CibaException.class,
                () -> service.start("tel:+251911111111", "", 0, SCOPE_VERIFY));
        assertEquals("invalid_request", e.error());
    }

    // ---- operator-token path ----

    @Test
    void operatorTokenHappy_anchorReceivesStrippedCandidate() {
        AuthorizationRequestService.AuthRequest auth =
                service.start("operatortoken:" + VALID_TOKEN, null, 0, SCOPE_VERIFY);

        PendingBinding binding = service.poll(auth.authReqId());
        assertNotNull(binding);
        assertEquals(MSISDN, binding.msisdn());
        assertEquals(IMSI, binding.imsi());
    }

    @Test
    void operatorTokenRejected_accessDenied() {
        CibaException e = assertThrows(CibaException.class,
                () -> service.start("operatortoken:bogus", null, 0, SCOPE_VERIFY));
        assertEquals("access_denied", e.error());
        assertTrue(e.getMessage().contains("invalid, expired or already used"));
    }

    @Test
    void operatorTokenTakesPrecedence_overCellularTuple() {
        service.resolverOverride = (ip, port, ts) -> CompletableFuture.completedFuture(
                ResolverResult.bound("+251999999999", null, 0L));

        PendingBinding binding = service.poll(service.start(
                "operatortoken:" + VALID_TOKEN, "10.20.30.40", 55555, SCOPE_VERIFY).authReqId());
        assertNotNull(binding);
        assertEquals(MSISDN, binding.msisdn(), "hint anchor wins over the network tuple");
    }

    // ---- scope validation matrix ----

    @Test
    void scope_verifyOnly_accepted() {
        Set<String> scopes = AuthorizationRequestService.validateScope(SCOPE_VERIFY);
        assertEquals(Set.of(SCOPE_VERIFY), scopes);
    }

    @Test
    void scope_devicePhoneNumberRead_accepted() {
        Set<String> scopes = AuthorizationRequestService.validateScope(SCOPE_SHARE);
        assertEquals(Set.of(SCOPE_SHARE), scopes);
    }

    @Test
    void scope_bothAccepted() {
        Set<String> scopes = AuthorizationRequestService.validateScope(
                SCOPE_VERIFY + " " + SCOPE_SHARE);
        assertEquals(Set.of(SCOPE_VERIFY, SCOPE_SHARE), scopes);
    }

    @Test
    void scope_unknown_rejected() {
        CibaException e = assertThrows(CibaException.class,
                () -> AuthorizationRequestService.validateScope("openid"));
        assertEquals("invalid_scope", e.error());
        assertEquals(400, e.httpStatus());
    }

    @Test
    void scope_validPlusUnknown_rejectedEntirely() {
        CibaException e = assertThrows(CibaException.class,
                () -> AuthorizationRequestService.validateScope(SCOPE_VERIFY + " openid"));
        assertEquals("invalid_scope", e.error());
    }

    @Test
    void scope_blankOrMissing_invalidRequest() {
        assertThrows(CibaException.class,
                () -> AuthorizationRequestService.validateScope(null));
        CibaException e = assertThrows(CibaException.class,
                () -> AuthorizationRequestService.validateScope("   "));
        assertEquals("invalid_request", e.error());
    }

    // ---- poll / consume lifecycle ----

    @Test
    void poll_unknownOrBlank_returnsNull() {
        assertNull(service.poll("nope"));
        assertNull(service.poll(""));
        assertNull(service.poll(null));
    }

    @Test
    void consume_singleUse_secondConsumeFailsAsInvalidGrant() {
        String id = service.start(null, "10.20.30.40", 55555, SCOPE_VERIFY).authReqId();

        AuthorizationRequestService.ConsumeResult first = service.consume(id);
        assertNotNull(first.binding(), "first exchange must yield the binding");

        AuthorizationRequestService.ConsumeResult second = service.consume(id);
        assertNull(second.binding(), "second exchange must fail (single-use)");
        assertFalse(second.knownExpired(), "already-used maps to invalid_grant");
    }

    @Test
    void consume_expiredBinding_reportsKnownExpired() throws Exception {
        var pendingsField = AuthorizationRequestService.class.getDeclaredField("pendings");
        pendingsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var pendings = (java.util.concurrent.ConcurrentHashMap<String, PendingBinding>)
                pendingsField.get(service);

        long past = System.currentTimeMillis() / 1000L - 60L;
        pendings.put("expired-id", new PendingBinding(
                "expired-id", MSISDN, null, Set.of(SCOPE_VERIFY),
                past - AuthorizationRequestService.AUTH_REQ_TTL_SECONDS, past));

        AuthorizationRequestService.ConsumeResult result = service.consume("expired-id");
        assertNull(result.binding());
        assertTrue(result.knownExpired(), "recently expired id maps to expired_token");
    }

    // ---- plumbing (no Quarkus boot) ----

    private static void inject(Object target, Object dependency) throws RuntimeException {
        try {
            var field = target.getClass().getDeclaredField("operatorAnchor");
            field.setAccessible(true);
            field.set(target, dependency);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
