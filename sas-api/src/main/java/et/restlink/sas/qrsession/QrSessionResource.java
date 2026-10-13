/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.qrsession;

import et.restlink.sas.api.ApiAudit;
import et.restlink.sas.api.ApiCdrRecorder;
import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.api.TenantRegistry;
import et.restlink.sas.api.dto.VerifyRequestDto;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.oauth.IdentityAnchor;
import et.restlink.sas.oauth.AccessTokenService;
import et.restlink.sas.security.ReplayGuard;
import et.restlink.sas.security.RequestValidator;
import et.restlink.sas.security.SasSecurityConfig;
import et.restlink.sas.security.TokenValidator;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Desktop → phone QR hand-off for CAMARA Number Verification v2.
 *
 * <p><strong>The problem it solves.</strong> A customer who signs in on a desktop
 * has no SIM in that machine, so the IP-match path cannot work there: there is no
 * cellular bearer to attest. The bank shows a QR code, the customer scans it with
 * the phone that <em>does</em> have the SIM, and the phone becomes the device that
 * proves the number. The desktop then polls for the verdict.</p>
 *
 * <p><strong>Three operations</strong> (spec server prefix
 * {@code {apiRoot}/qr-session/v1}):</p>
 * <ul>
 *   <li>{@code POST /qr-session} — the bank backend creates the session and gets
 *       the payload to render. Authenticated with a single-use NV bearer, exactly
 *       like {@code /verify}, so a QR code is never handed out anonymously.</li>
 *   <li>{@code POST /qr-session/{id}/claim} — the phone claims it over the
 *       cellular bearer. This runs the <strong>same</strong>
 *       {@link VerifyRequestEvent} the IP-match path runs, so the verdict comes
 *       from the one FSM, not from a second scoring path.</li>
 *   <li>{@code GET /qr-session/{id}} — the bank polls the verdict. Anything other
 *       than {@code RESOLVED} reads as "not authenticated".</li>
 * </ul>
 *
 * <p><strong>Layer split.</strong> This resource lives in the CAMARA gateway and
 * therefore signals no core network. It submits a
 * {@link VerifyRequestEvent} and reads the verdict back; every subscriber fact
 * still arrives through {@link SasVerifyEngine#subscribers()} → sas-ecs. Adding
 * a QR code does not widen what the gateway can see (layered ADR D3/D7, gate H25).</p>
 *
 * <p><strong>Privacy.</strong> The claim response carries a verdict, never a
 * number: the MSISDN is known to the bank (it supplied it) and to sas-ecs, and
 * stops there. Logs mask it.</p>
 *
 * <p><strong>Fail-closed.</strong> A missing session, an expired budget, a second
 * claimant, a bearer that declares itself non-cellular, or an FSM that fails all
 * answer "not authenticated". There is no path here that approves a login the
 * verifier did not approve.</p>
 */
@Path("/qr-session/v1")
public class QrSessionResource {

    private static final Logger LOG = LogManager.getLogger(QrSessionResource.class);

    /**
     * Budget for the FSM call made by a claim. Slightly above the SAS total
     * ({@code SasTimeouts}, 3 s) so the FSM's own verdict wins over this timeout;
     * a claim that outruns it is reported as unverified rather than left hanging.
     */
    private static final long CLAIM_AWAIT_MS = 5_000L;

    private static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String CODE_PERMISSION_DENIED = "PERMISSION_DENIED";
    private static final String CODE_NOT_FOUND = "NOT_FOUND";
    private static final String CODE_CONFLICT = "ALREADY_CLAIMED";
    private static final String CODE_NOT_CELLULAR = "ACCESS_TECH_NOT_CELLULAR";
    private static final String CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

    @Inject
    QrSessionStore sessions;

    @Inject
    SasVerifyEngine bootstrap;

    @Inject
    TokenValidator tokenValidator;

    @Inject
    ReplayGuard replayGuard;

    @Inject
    AccessTokenService accessTokens;

    @Inject
    SasSecurityConfig securityConfig;

    @Inject
    IdentityAnchor identityAnchor;

    @Inject
    TenantRegistry tenants = new TenantRegistry();

    @Inject
    HttpHeaders httpHeaders;

    @Inject
    ApiCdrRecorder cdr = ApiCdrRecorder.NOOP;

    // ------------------------------------------------------------------ create

    /** Bank backend opens a hand-off and receives the payload to render. */
    @POST
    @Path("")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(QrSessionRequests.CreateRequest body,
                           @HeaderParam("x-correlator") String xCorrelator,
                           @HeaderParam("Authorization") String authorization) {
        String correlator = xCorrelator == null ? "" : xCorrelator;
        ApiAudit audit = ApiAudit.start("QRSESSION", "QRSESSION", correlator)
                .detail("action", "create");

        String claimed = null;
        String phoneNumber = body == null ? null : trimToNull(body.phoneNumber());
        if (phoneNumber != null) {
            claimed = PhoneNumberNormalizer.toE164(phoneNumber).orElse(null);
            if (claimed == null) {
                audit.complete(400, CODE_INVALID_ARGUMENT);
                return error(400, CODE_INVALID_ARGUMENT,
                        "phoneNumber must be E.164 (+<digits>)", correlator);
            }
            audit.msisdn(mask(claimed));
        }

        // Same precedence as /verify: a GSMA TS.43 operator token is checked first,
        // because `operatortoken:<tk>` is not a JWT and would otherwise be read as
        // a malformed bearer. The Wi-Fi variant authenticates the bank session this
        // way; the cellular variant uses an ordinary NV bearer.
        String operatorCandidate = identityAnchor.extractCandidate(authorization, null, null);
        IdentityAnchor.OperatorBinding bankBinding = null;
        String tokenKey = null;
        if (operatorCandidate != null) {
            bankBinding = identityAnchor.resolveOperatorToken(operatorCandidate);
            if (bankBinding == null || bankBinding.msisdn() == null
                    || bankBinding.msisdn().isBlank()) {
                audit.complete(401, CODE_UNAUTHENTICATED);
                return error(401, CODE_UNAUTHENTICATED,
                        "operator token is invalid, expired or already used", correlator);
            }
            // The operator token is authoritative on this path, so a body number
            // that disagrees with it is a caller bug, not something to compare.
            if (claimed != null) {
                String bound = PhoneNumberNormalizer.toE164(bankBinding.msisdn()).orElse(null);
                if (!claimed.equals(bound)) {
                    audit.complete(422, "UNNECESSARY_IDENTIFIER");
                    return error(422, "UNNECESSARY_IDENTIFIER",
                            "the operator token already identifies the subscriber; "
                                    + "do not also state a number", correlator);
                }
            }
            claimed = PhoneNumberNormalizer.toE164(bankBinding.msisdn()).orElse(null);
        }

        if (bankBinding == null) {
            TokenValidator.DetailedAuth auth = tokenValidator.validateDetailed(authorization);
            if (!auth.ok()) {
                audit.complete(401, CODE_UNAUTHENTICATED);
                return error(401, CODE_UNAUTHENTICATED, auth.error(), correlator);
            }
            tokenKey = auth.tokenKey();

            if (securityConfig.tokenValidationEnabled()) {
                if (!TokenValidator.hasScope(auth.scopes(),
                        TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY)) {
                    audit.complete(403, CODE_PERMISSION_DENIED);
                    return error(403, CODE_PERMISSION_DENIED,
                            "missing required scope: "
                                    + TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY,
                            correlator);
                }
                if (replayGuard.isConsumed(tokenKey) || accessTokens.isConsumed(tokenKey)) {
                    audit.complete(401, CODE_UNAUTHENTICATED);
                    return error(401, CODE_UNAUTHENTICATED, "token already used (single-use)",
                            correlator);
                }
                String replayError = replayGuard.checkReplay(tokenKey, correlator);
                if (replayError != null) {
                    audit.complete(401, CODE_UNAUTHENTICATED);
                    return error(401, CODE_UNAUTHENTICATED, replayError, correlator);
                }
            }
        }

        TenantRegistry.TenantInfo tenant = tenants.resolve(apiKeyHeader());
        if (tenant == null) {
            audit.complete(401, CODE_UNAUTHENTICATED);
            return error(401, CODE_UNAUTHENTICATED, "unknown X-Api-Key (no tenant)", correlator);
        }
        if (!tenants.checkAndIncrement(tenant.tenantId())) {
            audit.complete(429, CODE_QUOTA_EXCEEDED);
            return error(429, CODE_QUOTA_EXCEEDED,
                    "monthly quota exhausted for tenant " + tenant.tenantId(), correlator);
        }
        audit.tenant(tenant.tenantId());

        String sessionId = UUID.randomUUID().toString();
        sessions.create(new QrSessionStore.QrSession(sessionId, claimed, correlator,
                tenant.tenantId(), System.currentTimeMillis()));
        sessions.sweep(System.currentTimeMillis());

        // The payload carries only the opaque id: the phone cannot read the bank
        // session, the claimed number, or anything else from the QR image.
        String payload = "silentauth://qr-session/" + sessionId;
        long expiresInSeconds = QrSessionStore.SESSION_TTL_MS / 1000L;

        // The creating token has now driven one completed call.
        if (securityConfig.tokenValidationEnabled()) {
            replayGuard.consume(tokenKey);
            accessTokens.markConsumed(tokenKey);
        }

        audit.detail("sessionId", sessionId);
        LOG.info("[SAS] /qr-session created session={} tenant={} claimed={}",
                sessionId, tenant.tenantId(), claimed == null ? "discovery" : mask(claimed));
        cdr.record(audit.complete(200, null));
        return Response.ok(new QrSessionRequests.CreateResponse(sessionId, payload,
                        expiresInSeconds))
                .header("x-correlator", correlator)
                .build();
    }

    // ------------------------------------------------------------------- claim

    /**
     * The phone claims the hand-off. Runs the ordinary verify FSM — this endpoint
     * adds a delivery mechanism, never a second way to be approved.
     */
    @POST
    @Path("/{id}/claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response claim(@PathParam("id") String sessionId,
                          QrSessionRequests.ClaimRequest body,
                          @HeaderParam("x-correlator") String xCorrelator,
                          @HeaderParam("Authorization") String authorization,
                          @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                          @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                          @HeaderParam("X-Sas-Risk-Class") String riskClassHeader) {
        long now = System.currentTimeMillis();
        QrSessionStore.QrSession session = sessions.get(sessionId, now).orElse(null);
        if (session == null) {
            return error(404, CODE_NOT_FOUND,
                    "qr session not found or expired", correlatorOf(xCorrelator));
        }
        String correlator = session.correlator();
        ApiAudit audit = ApiAudit.start("QRSESSION", "QRSESSION", correlator)
                .detail("action", "claim")
                .detail("sessionId", sessionId);

        if (!sessions.beginClaim(sessionId)) {
            // Second handset on the same code: the bank cannot tell which one the
            // evidence belonged to, so neither may be authenticated.
            audit.detail("reason", "ALREADY_CLAIMED");
            LOG.warn("[SAS] /qr-session claim session={} refused — already claimed", sessionId);
            cdr.record(audit.complete(409, CODE_CONFLICT));
            return error(409, CODE_CONFLICT,
                    "this QR session was already claimed", correlator);
        }



        // Wi-Fi variant first: the phone proves the SIM with a GSMA TS.43 temporary
        // token that sas-ecs redeems. That proof is cryptographic, not positional,
        // so the bearer gate below does not apply to it — and it must be evaluated
        // first, because this path legitimately declares WIFI.
        String operatorToken = body == null ? null : trimToNull(body.operatorToken());
        IdentityAnchor.OperatorBinding binding = null;
        if (operatorToken != null) {
            // The anchor takes the BARE token: it is what sas-ecs redeems. The
            // `operatortoken:` scheme belongs to the wire form the anchor strips
            // for us, so re-adding it here would send the entitlement server a
            // token it has never issued.
            binding = identityAnchor.resolveOperatorToken(operatorToken);
            if (binding == null || binding.msisdn() == null || binding.msisdn().isBlank()) {
                audit.detail("reason", "OPERATOR_TOKEN_REJECTED");
                cdr.record(audit.complete(401, CODE_UNAUTHENTICATED));
                return error(401, CODE_UNAUTHENTICATED,
                        "operator token is invalid, expired or already used", correlator);
            }
        }

        // Bearer declaration: advisory, exclusion-only (gate H22). Without an
        // operator token a phone that says it is on Wi-Fi cannot produce an
        // IP-match binding, so refuse it rather than let it seed one.
        AccessTech declared = parseAccessTech(body == null ? null : body.accessTech());
        if (declared == null && body != null && body.accessTech() != null
                && !body.accessTech().isBlank()) {
            // Declared something outside the contract: refuse rather than guess a
            // bearer, exactly as /session-tuple does.
            audit.detail("reason", CODE_INVALID_ARGUMENT);
            cdr.record(audit.complete(400, CODE_INVALID_ARGUMENT));
            return error(400, CODE_INVALID_ARGUMENT,
                    "accessTech must be one of GS_2G3G, LTE, NR, WIFI or FIXED", correlator);
        }
        if (binding == null && declared == AccessTech.WIFI) {  // also covers FIXED
            audit.detail("reason", CODE_NOT_CELLULAR);
            cdr.record(audit.complete(400, CODE_NOT_CELLULAR));
            return error(400, CODE_NOT_CELLULAR,
                    "this hand-off needs a cellular bearer; the device reports " + declared
                            + ". Use the TS.43 operator-token path instead.", correlator);
        }

        String claimed = session.claimedMsisdn();
        if (binding != null) {
            claimed = PhoneNumberNormalizer.toE164(binding.msisdn()).orElse(null);
            if (claimed == null) {
                cdr.record(audit.complete(401, CODE_UNAUTHENTICATED));
                return error(401, CODE_UNAUTHENTICATED,
                        "operator token binding is not a valid E.164 number", correlator);
            }
        } else if (claimed == null) {
            // Discovery variant: the phone states which number it wants proven.
            String phone = body == null ? null : trimToNull(body.msisdn());
            claimed = phone == null ? null : PhoneNumberNormalizer.toE164(phone).orElse(null);
            if (claimed == null) {
                cdr.record(audit.complete(422, "MISSING_IDENTIFIER"));
                return error(422, "MISSING_IDENTIFIER",
                        "this session was opened for number discovery; the phone must"
                                + " supply msisdn (or an operatorToken)", correlator);
            }
        }
        audit.msisdn(mask(claimed));

        // The bank session was authenticated at create time; the claim rides that
        // session rather than minting a second credential.
        if (securityConfig.tokenValidationEnabled() && authorization != null
                && !authorization.isBlank()) {
            TokenValidator.DetailedAuth auth = tokenValidator.validateDetailed(authorization);
            if (!auth.ok()) {
                cdr.record(audit.complete(401, CODE_UNAUTHENTICATED));
                return error(401, CODE_UNAUTHENTICATED, auth.error(), correlator);
            }
        }

        long ts = System.currentTimeMillis();
        String tsError = replayGuard.checkTimestamp(ts);
        if (tsError != null) {
            cdr.record(audit.complete(400, CODE_INVALID_ARGUMENT));
            return error(400, CODE_INVALID_ARGUMENT, tsError, correlator);
        }

        String srcIp = trimToNull(srcIpHeader) != null ? srcIpHeader : "10.20.30.40";
        int srcPort = parseInt(srcPortHeader, 55555);
        AccessTech accessTech = binding != null ? AccessTech.WIFI
                : (declared == null ? AccessTech.GS_2G3G : declared);
        String reqId = "qr-" + UUID.randomUUID();

        VerifyRequestEvent event = new VerifyRequestEvent(reqId, srcIp, srcPort, ts,
                claimed, binding == null ? null : binding.imsi(), accessTech,
                VerifyRequestDto.parse(riskClassHeader), session.tenantId(),
                binding != null);

        QrSessionStore.Verdict verdict = runVerdict(event, reqId);
        sessions.resolve(sessionId, verdict, System.currentTimeMillis());

        audit.detail("verified", verdict.verified());
        LOG.info("[SAS] /qr-session claim session={} reqId={} verified={} fallback={}",
                sessionId, reqId, verdict.verified(), verdict.fallbackReason());
        cdr.record(audit.complete(200, verdict.fallbackReason()));
        return Response.ok(new QrSessionRequests.ClaimResponse(sessionId, "RESOLVED",
                        verdict.verified(), verdict.fallbackReason()))
                .header("x-correlator", correlator)
                .build();
    }

    // ------------------------------------------------------------------- status

    /** The bank polls for the verdict. Not {@code RESOLVED} means not authenticated. */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") String sessionId,
                           @HeaderParam("x-correlator") String xCorrelator) {
        long now = System.currentTimeMillis();
        QrSessionStore.QrSession session = sessions.get(sessionId, now).orElse(null);
        if (session == null) {
            return error(404, CODE_NOT_FOUND,
                    "qr session not found or expired", correlatorOf(xCorrelator));
        }
        String correlator = session.correlator();
        QrSessionStore.State state = sessions.state(session, now);
        Optional<QrSessionStore.Verdict> verdict = state == QrSessionStore.State.RESOLVED
                ? sessions.verdict(sessionId)
                : Optional.empty();

        long expiresInSeconds = Math.max(0L,
                (QrSessionStore.SESSION_TTL_MS - (now - session.createdAtMs())) / 1000L);

        return Response.ok(new QrSessionRequests.StatusResponse(sessionId, state.name(),
                        verdict.map(QrSessionStore.Verdict::verified).orElse(null),
                        verdict.map(QrSessionStore.Verdict::fallbackReason).orElse(null),
                        verdict.map(QrSessionStore.Verdict::decision).orElse(null),
                        expiresInSeconds))
                .header("x-correlator", correlator)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Awaits the one FSM verdict for this claim. Any transport, timeout or
     * exception outcome becomes "not verified" — the QR path never invents an
     * approval the verifier did not produce.
     */
    private QrSessionStore.Verdict runVerdict(VerifyRequestEvent event, String reqId) {
        try {
            VerifyResult result = bootstrap.submit(event)
                    .get(CLAIM_AWAIT_MS, TimeUnit.MILLISECONDS);
            boolean verified = result.match() && result.fallbackReason() == null;
            String reason = result.fallbackReason() == null
                    ? null : result.fallbackReason().name();
            return new QrSessionStore.Verdict(verified, reason,
                    verified ? "APPROVED" : "FALLBACK");
        } catch (TimeoutException notInTime) {
            LOG.warn("[SAS] /qr-session claim reqId={} FSM did not answer within {}ms"
                    + " — failing closed", reqId, CLAIM_AWAIT_MS);
            return new QrSessionStore.Verdict(false, "TIMEOUT", "FALLBACK");
        } catch (ExecutionException | InterruptedException failed) {
            LOG.error("[SAS] /qr-session claim reqId={} FSM failed — failing closed", reqId, failed);
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new QrSessionStore.Verdict(false, "NETWORK_ERROR", "FALLBACK");
        } finally {
            bootstrap.release(reqId);
        }
    }

    /**
     * Normalises the device's bearer declaration.
     *
     * @return the declared value, {@code null} when nothing was declared. A
     *         {@code FIXED} declaration maps onto {@link AccessTech#WIFI}: the
     *         shared server enum carries no FIXED constant, and both mean "no
     *         cellular bearer", which {@code /session-tuple} refuses as well.
     */
    private static AccessTech parseAccessTech(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if ("FIXED".equals(value)) {
            return AccessTech.WIFI;
        }
        try {
            return AccessTech.valueOf(value);
        } catch (IllegalArgumentException notInContract) {
            return null;
        }
    }

    private String apiKeyHeader() {
        return httpHeaders == null ? null : httpHeaders.getHeaderString("X-Api-Key");
    }

    private static String correlatorOf(String header) {
        return header == null ? "" : header;
    }

    private static Response error(int status, String code, String message, String correlator) {
        return Response.status(status)
                .entity(new QrSessionRequests.QrError(status, code, message))
                .header("x-correlator", correlator)
                .build();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** Privacy: never log a full MSISDN. */
    static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) {
            return "***";
        }
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
