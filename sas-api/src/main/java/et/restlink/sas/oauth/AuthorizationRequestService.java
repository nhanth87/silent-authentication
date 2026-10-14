/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.api.ExposureAdapters;
import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.security.RequestValidator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.SecureRandom;
import java.util.List;
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
        return start(loginHint, srcIp, srcPort, requestedScope, null);
    }

    public AuthRequest start(String loginHint,
                             String srcIp,
                             int srcPort,
                             String requestedScope,
                             String clientId) {
        Set<String> scopes = validateScope(requestedScope);
        long nowSec = System.currentTimeMillis() / 1000L;
        evictExpired(nowSec);

        Anchored anchored = resolveAnchor(loginHint, srcIp, srcPort, nowSec, clientId);
        IdentityAnchor.OperatorBinding anchor = anchored.binding();
        String msisdn = PhoneNumberNormalizer.toE164(anchor.msisdn()).orElse(null);
        if (msisdn == null) {
            LOG.warn("bc-authorize: anchor resolved a non-E.164 identity — rejecting");
            throw CibaException.accessDenied("identity anchor is not a valid E.164 subscriber");
        }

        String authReqId = randomAuthReqId();
        pendings.put(authReqId, new PendingBinding(
                authReqId, msisdn, anchor.imsi(), scopes,
                nowSec, nowSec + AUTH_REQ_TTL_SECONDS, clientId, anchored.amr()));
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

    /** The resolved subscriber and how it was authenticated ({@link AuthMethods}). */
    record Anchored(IdentityAnchor.OperatorBinding binding, List<String> amr) {}

    /**
     * CAMARA login hints, in order:
     * <ul>
     *   <li>{@code operatortoken:<tk>} — SIM-based (GSMA TS.43), redeemed at the
     *       entitlement server; amr {@link AuthMethods#SIM}</li>
     *   <li>{@code ipport:<ipv4>:<port>} / {@code ipport:[<ipv6>]:<port>} — network-based,
     *       the device's public address as the client observed it; amr
     *       {@link AuthMethods#NETWORK}</li>
     *   <li>{@code tel:+<E.164>} — an asserted number, nobody authenticated it; no amr,
     *       so Number Verification refuses the token (SIM Swap and similar
     *       consent-based APIs accept it)</li>
     *   <li>no hint — the legacy {@code X-Sas-Src-Ip/Port} headers, network-based</li>
     * </ul>
     */
    private Anchored resolveAnchor(
            String loginHint, String srcIp, int srcPort, long nowSec, String clientId) {
        String hint = loginHint == null ? "" : loginHint.trim();
        String candidate = IdentityAnchor.parseLoginHint(hint);
        if (candidate != null) {
            IdentityAnchor.OperatorBinding binding =
                    operatorAnchor.resolveOperatorToken(candidate, clientId);
            if (binding == null || binding.msisdn() == null || binding.msisdn().isBlank()) {
                throw CibaException.accessDenied(
                        "operator token is invalid, expired or already used");
            }
            return new Anchored(binding, AuthMethods.SIM);
        }
        if (hint.regionMatches(true, 0, "tel:", 0, 4)) {
            String e164 = PhoneNumberNormalizer.toE164(hint.substring(4).trim()).orElseThrow(() ->
                    CibaException.invalidRequest("login_hint tel: must carry an E.164 number"));
            return new Anchored(new IdentityAnchor.OperatorBinding(e164, null), AuthMethods.NONE);
        }
        if (hint.regionMatches(true, 0, "ipport:", 0, 7)) {
            IpPort ipport = IpPort.parse(hint.substring(7).trim());
            return new Anchored(resolveNetwork(ipport.ip(), ipport.port(), nowSec), AuthMethods.NETWORK);
        }
        if (!hint.isEmpty()) {
            throw CibaException.invalidRequest(
                    "login_hint must be operatortoken:<token>, ipport:<ip>:<port> or tel:+<E.164>");
        }
        if (srcIp == null || srcIp.isBlank()) {
            throw CibaException.invalidRequest(
                    "no identity anchor: provide login_hint=operatortoken:<tk>, ipport:<ip>:<port> "
                            + "or X-Sas-Src-Ip/X-Sas-Src-Port headers");
        }
        return new Anchored(resolveNetwork(srcIp.trim(), Math.max(0, srcPort), nowSec), AuthMethods.NETWORK);
    }

    private IdentityAnchor.OperatorBinding resolveNetwork(String ip, int port, long nowSec) {
        ResolverResult result = awaitResolver(ip, port, nowSec * 1000L);
        if (!result.found()) {
            throw CibaException.accessDenied(
                    "no unique subscriber bound to the network tuple (" + result.miss() + ")");
        }
        return new IdentityAnchor.OperatorBinding(result.msisdn(), result.imsi());
    }

    /**
     * {@code <ipv4>:<port>} or {@code [<ipv6>]:<port>}. The port is mandatory: behind
     * CGNAT an address alone names many subscribers (IP + port + ts rule).
     */
    record IpPort(String ip, int port) {
        static IpPort parse(String raw) {
            String ip;
            String portText;
            if (raw.startsWith("[")) {
                int close = raw.indexOf(']');
                if (close < 0 || close + 1 >= raw.length() || raw.charAt(close + 1) != ':') {
                    throw CibaException.invalidRequest("login_hint ipport: must be [ipv6]:port");
                }
                ip = raw.substring(1, close);
                portText = raw.substring(close + 2);
            } else {
                int colon = raw.lastIndexOf(':');
                if (colon <= 0 || raw.indexOf(':') != colon) {
                    throw CibaException.invalidRequest("login_hint ipport: must be ipv4:port or [ipv6]:port");
                }
                ip = raw.substring(0, colon);
                portText = raw.substring(colon + 1);
            }
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw CibaException.invalidRequest("login_hint ipport: port is not a number");
            }
            if (port < 1 || port > 65535 || !validAddress(ip)) {
                throw CibaException.invalidRequest("login_hint ipport: address or port out of range");
            }
            return new IpPort(ip, port);
        }

        private static boolean validAddress(String ip) {
            if (ip.isEmpty() || !ip.matches("[0-9A-Fa-f:.]+")) {
                return false;   // literal addresses only: never a hostname lookup
            }
            try {
                java.net.InetAddress.ofLiteral(ip);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    /**
     * Network-based authentication shared by CIBA and the Authorization Code flow:
     * resolve the live IP:port:ts tuple to exactly one subscriber through the SAS
     * Resolver. Fail-closed — any miss, ambiguity or timeout throws.
     *
     * @throws CibaException access_denied / invalid_request
     */
    public IdentityAnchor.OperatorBinding resolveNetworkAnchor(String srcIp, int srcPort) {
        if (srcIp == null || srcIp.isBlank()) {
            throw CibaException.invalidRequest("no network source address for authentication");
        }
        long nowMs = System.currentTimeMillis();
        ResolverResult result = awaitResolver(srcIp.trim(), Math.max(0, srcPort), nowMs);
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
            LOG.error("No subscriber exposure wired — failing closed");
            return (ip, port, ts) ->
                    CompletableFuture.completedFuture(ResolverResult.miss(
                            et.restlink.sas.model.FallbackReason.RESOLVER_ERROR));
        }
        // UE ID retrieval at the entitlement server (layered ADR §4.1).
        return ExposureAdapters.resolver(bootstrap.subscribers(), bootstrap.afId());
    }

    // ---- helpers ----

    static Set<String> validateScope(String requestedScope) {
        return OAuthScopePolicy.parse(requestedScope).responseScopes();
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
