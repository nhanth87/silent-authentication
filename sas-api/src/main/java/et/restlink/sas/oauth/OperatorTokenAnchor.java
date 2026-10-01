/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.api.ExposureAdapters;
import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CAMARA-side acceptance of {@code operatortoken:<token>} (CAMARA ICM): as a CIBA
 * {@code login_hint}, a JWT-bearer {@code sub}, an {@code /authorize} login hint, or —
 * lab-only SAS extension (D6) — {@code Authorization: Bearer operatortoken:<token>} on
 * {@code /verify}.
 *
 * <p>The gateway holds no key for these tokens and keeps no ledger (layered ADR D3/D7):
 * it forwards the token, unread, to the entitlement server's
 * {@code POST /operator-token/redeem}, which decrypts, verifies and spends it exactly once
 * and answers the subscriber verification result. Any refusal, timeout or transport error
 * is {@code null} (fail closed); the reason stays at the ECS.</p>
 */
@ApplicationScoped
public class OperatorTokenAnchor implements IdentityAnchor {

    private static final Logger LOG = LogManager.getLogger(OperatorTokenAnchor.class);

    /** Budget handed to the ECS for one redemption, and the gateway's own wait on top. */
    static final int REDEEM_BUDGET_MS = 300;
    private static final long WAIT_GRACE_MS = 200L;
    static final String UNKNOWN_CLIENT = "camara-gateway";

    @Inject
    OperatorTokenConfig config;

    @Inject
    SasVerifyEngine engine;

    @Override
    public OperatorBinding resolveOperatorToken(String candidate) {
        return resolveOperatorToken(candidate, null);
    }

    @Override
    public OperatorBinding resolveOperatorToken(String candidate, String clientId) {
        if (candidate == null || candidate.isBlank() || config == null || !config.enabled()) {
            return null;
        }
        SubscriberService service = engine == null ? null : engine.subscribers();
        if (service == null) {
            LOG.error("[SAS] operator token refused: no subscriber exposure wired");
            return null;
        }
        String client = clientId == null || clientId.isBlank() ? UNKNOWN_CLIENT : clientId.trim();
        ExposureOutcome<SubscriberVerificationResult> outcome;
        try {
            outcome = service.redeemOperatorToken(ExposureAdapters.context("redeem", REDEEM_BUDGET_MS),
                            new OperatorTokenRedeemReq(engine.afId(), candidate.trim(),
                                    client.length() > 128 ? client.substring(0, 128) : client))
                    .get(REDEEM_BUDGET_MS + WAIT_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("[SAS] operator token refused: the entitlement server did not answer");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            LOG.warn("[SAS] operator token refused: redemption failed ({})",
                    e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
            return null;
        }
        if (outcome == null || !outcome.ok()) {
            LOG.warn("[SAS] operator token refused by the entitlement server ({})",
                    outcome == null ? "no answer" : outcome.cause());
            return null;
        }
        SubscriberVerificationResult r = outcome.value();
        String msisdn = PhoneNumberNormalizer.toE164(r.msisdn()).orElse(null);
        if (msisdn == null || !Boolean.TRUE.equals(r.simVerified())) {
            LOG.warn("[SAS] operator token refused: the redemption answer proves nothing usable");
            return null;
        }
        LOG.info("[SAS] operator token redeemed at the entitlement server (method={}, jti={})",
                r.authMethod(), r.tokenId());
        return new OperatorBinding(msisdn, null, r.authMethod());
    }

    @Override
    public String extractCandidate(String authorizationHeader, String loginHint,
                                   String operatorTokenHeader) {
        if (authorizationHeader != null) {
            String h = authorizationHeader.trim();
            if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String bearer = h.substring(7).trim();
                if (bearer.regionMatches(true, 0, OPERATOR_SCHEME_PREFIX, 0, OPERATOR_SCHEME_PREFIX.length())) {
                    if (config == null || !config.directBearerEnabled()) {
                        // D6: not offered here; the normal bearer check then refuses it.
                        return null;
                    }
                    String token = bearer.substring(OPERATOR_SCHEME_PREFIX.length()).trim();
                    return token.isEmpty() ? null : token;
                }
            }
        }
        String fromHint = IdentityAnchor.parseLoginHint(loginHint);
        if (fromHint != null) {
            return fromHint;
        }
        if (config != null && config.headerEnabled()
                && operatorTokenHeader != null && !operatorTokenHeader.isBlank()) {
            return operatorTokenHeader.trim();
        }
        return null;
    }
}
