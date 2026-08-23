/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.security.RequestValidator;
import et.restlink.sas.security.TokenValidator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CIBA back-channel authorization requests ({@code POST /bc-authorize}):
 * resolves the identity anchor — {@code login_hint=operatortoken:<tk>} or the
 * live cellular IP tuple via the SAS Resolver — and binds the resulting
 * MSISDN into a short-lived pending binding keyed by a random
 * {@code auth_req_id}. Fail-closed: no anchor, ambiguous binding or resolver
 * failure throws {@link CibaException} and never mints an id.
 */
@ApplicationScoped
public class AuthorizationRequestService {

    private static final Logger LOG = LogManager.getLogger(AuthorizationRequestService.class);

    /** Pending-binding lifetime in seconds (CIBA auth_req_id expiry). */
    public static final long AUTH_REQ_TTL_SECONDS = 120L;

    /** How long past expiry a consumed/expired id stays queryable before lazy eviction. */
    static final long EVICTION_GRACE_SECONDS = 600L;

    /** Resolver wait budget, mirroring the SAS resolver stage budget. */
    static final long RESOLVER_WAIT_MS = 500L;

    /** CAMARA NV scope whitelist — anything else is invalid_scope. */
    private static final Set<String> SUPPORTED_SCOPES = Set.of(
            TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY,
            TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ);

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    SasVerifyEngine bootstrap;

    @Inject
    IdentityAnchor operatorAnchor;

    /** Lab/test seam: overrides the bootstrap resolver when non-null. */
    volatile ResolverBackend resolverOverride;

    private final Map<String, PendingBinding> pendings = new ConcurrentHashMap<>();

    /** Minted authorization request handle returned to the API consumer. */
    public record AuthRequest(String authReqId, long expiresInSeconds) {}

    /**
     * Validate the requested scopes, resolve the identity anchor and mint an
     * {@code auth_req_id} bound to exactly one subscriber.
     *
     * @throws CibaException invalid_request / invalid_scope / access_denied
     */
    public AuthRequest start(String loginHint, String srcIp, int srcPort, String requestedScope) {
        Set<String> scopes = validateScope(requestedScope);
        long nowSec = System.currentTimeMillis() / 1000L;
        evictExpired(nowSec);

        IdentityAnchor.OperatorBinding anchor = resolveAnchor(loginHint, srcIp, srcPort, nowSec);
        String msisdn = RequestValidator.normalizeE164(anchor.msisdn()).orElse(null);
        if (msisdn == null) {
            LOG.warn("bc-authorize: anchor resolved a non-E.164 identity — rejecting");
            throw CibaException.accessDenied("identity anchor is not a valid E.164 subscriber");
        }

        String authReqId = randomAuthReqId();
        pendings.put(authReqId, new PendingBinding(
                authReqId, msisdn, anchor.imsi(), scopes,
                nowSec, nowSec + AUTH_REQ_TTL_SECONDS));
        LOG.info("[SAS] bc-authorize bound {} to {} (ttl={}s)",
                maskMsisdn(msisdn), authReqId, AUTH_REQ_TTL_SECONDS);
        return new AuthRequest(authReqId, AUTH_REQ_TTL_SECONDS);
    }

    /**
     * Live pending binding for polling consumers, or null when unknown or
     * expired (CIBA poll semantics: repeat until consumed by /token).
     */
    public PendingBinding poll(String authReqId) {
        if (authReqId == null || authReqId.isBlank()) {
            return null;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        evictExpired(nowSec);
        PendingBinding binding = pendings.get(authReqId.trim());
        return binding == null || binding.expiredAt(nowSec) ? null : binding;
    }

    /** Outcome of the atomic token-exchange consumption. */
    public record ConsumeResult(PendingBinding binding, boolean knownExpired) {}

    /**
     * Atomically remove the binding: one {@code auth_req_id} yields ONE token.
     * {@code binding == null && !knownExpired} means unknown/already-used
     * (invalid_grant); {@code knownExpired} means expired_token.
     */
    public ConsumeResult consume(String authReqId) {
        if (authReqId == null || authReqId.isBlank()) {
            return new ConsumeResult(null, false);
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        evictExpired(nowSec);
        PendingBinding binding = pendings.remove(authReqId.trim());
        if (binding == null) {
            return new ConsumeResult(null, false);
        }
        if (binding.expiredAt(nowSec)) {
            return new ConsumeResult(null, true);
        }
        return new ConsumeResult(binding, false);
    }

    // ---- identity anchors ----

    private IdentityAnchor.OperatorBinding resolveAnchor(
            String loginHint, String srcIp, int srcPort, long nowSec) {
        String hint = loginHint == null ? "" : loginHint.trim();
        String candidate = IdentityAnchor.parseLoginHint(hint);
        if (candidate != null) {
            IdentityAnchor.OperatorBinding binding = operatorAnchor.resolveOperatorToken(candidate);
            if (binding == null || binding.msisdn() == null || binding.msisdn().isBlank()) {
                throw CibaException.accessDenied(
                        "operator token is invalid, expired or already used");
            }
            return binding;
        }
        if (!hint.isEmpty()) {
            throw CibaException.invalidRequest(
                    "login_hint must be operatortoken:<token>");
        }
        if (srcIp == null || srcIp.isBlank()) {
            throw CibaException.invalidRequest(
                    "no identity anchor: provide login_hint=operatortoken:<tk> "
                            + "or X-Sas-Src-Ip/X-Sas-Src-Port headers");
        }
        ResolverResult result = awaitResolver(srcIp.trim(), Math.max(0, srcPort), nowSec * 1000L);
        if (!result.found()) {
            throw CibaException.accessDenied(
                    "no unique subscriber bound to the network tuple (" + result.miss() + ")");
        }
        return new IdentityAnchor.OperatorBinding(result.msisdn(), result.imsi());
    }

    private ResolverResult awaitResolver(String ip, int port, long tsEpochMs) {
        try {
            return resolver().resolve(ip, port, tsEpochMs)
                    .get(RESOLVER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw CibaException.accessDenied("network binding lookup timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CibaException.accessDenied("network binding lookup interrupted");
        } catch (ExecutionException e) {
            throw CibaException.accessDenied("network binding lookup failed");
        }
    }

    private ResolverBackend resolver() {
        ResolverBackend override = resolverOverride;
        if (override != null) {
            return override;
        }
        if (bootstrap == null) {
            LOG.error("No resolver backend wired — failing closed");
            return (ip, port, ts) ->
                    CompletableFuture.completedFuture(ResolverResult.miss(
                            et.restlink.sas.model.FallbackReason.RESOLVER_ERROR));
        }
        return bootstrap.resolverBackend();
    }

    // ---- helpers ----

    static Set<String> validateScope(String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            throw CibaException.invalidRequest("scope is required");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String token : requestedScope.trim().split("\\s+")) {
            if (!token.isBlank()) {
                requested.add(token);
            }
        }
        if (requested.isEmpty()) {
            throw CibaException.invalidRequest("scope is required");
        }
        for (String scope : requested) {
            if (!SUPPORTED_SCOPES.contains(scope)) {
                throw CibaException.invalidScope("unsupported scope: " + scope);
            }
        }
        return requested;
    }

    private void evictExpired(long nowSec) {
        pendings.values().removeIf(b -> nowSec > b.expiresEpochSec() + EVICTION_GRACE_SECONDS);
    }

    /** 16 random bytes → 22-char base64url string (no padding). */
    private static String randomAuthReqId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) {
            return "***";
        }
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
