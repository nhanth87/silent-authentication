/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import et.restlink.sas.entitlement.EntitlementConfig;
import et.restlink.sas.entitlement.EntitlementTokenService;
import et.restlink.sas.oauth.IdentityAnchor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;

/**
 * CIBA operator-token acceptance for the Wi-Fi silent-auth path.
 *
 * <p>An operator token is a signed SAS entitlement token (see
 * {@link EntitlementTokenService}) minted after a successful EAP-AKA over
 * Wi-Fi. It anchors the claimed identity without a cellular bearer:</p>
 *
 * <ul>
 *   <li>{@code Authorization: Bearer operatortoken:<tk>} — always honoured
 *       when the entitlement service is enabled.</li>
 *   <li>CIBA {@code login_hint=operatortoken:<tk>} — parsed via
 *       {@link #parseLoginHint} for the back-channel auth request.</li>
 *   <li>{@code X-Sas-Operator-Token: <tk>} — honoured only when
 *       {@code sas.entitlement.ciba-enabled=true}.</li>
 * </ul>
 *
 * <p>Fail-closed: any invalid/expired/replayed token resolves to null and the
 * caller must answer {@code 401 INVALID_TOKEN}. The token is consumed on
 * successful resolve (single-use).</p>
 */
@ApplicationScoped
public class OperatorTokenSupport {

    private static final Logger LOG = LogManager.getLogger(OperatorTokenSupport.class);

    /** URI scheme prefix marking an entitlement token inside a Bearer/login_hint value. */
    public static final String OPERATOR_SCHEME_PREFIX = "operatortoken:";

    /** B3: only EAP-AKA family tokens anchor a Wi-Fi silent-auth identity. */
    private static final Set<String> EAP_METHOD_WHITELIST =
            Set.of(EntitlementTokenService.EAP_AKA, EntitlementTokenService.EAP_AKA_PRIME);

    @Inject
    EntitlementTokenService entitlementTokens;

    @Inject
    EntitlementConfig entitlementConfig;

    /**
     * Parse a CIBA {@code login_hint} of the form
     * {@code operatortoken:<token>}. Returns the embedded token, or null when
     * the hint is absent or not operator-token shaped. Delegates to the
     * {@link IdentityAnchor} port (single implementation lives there).
     */
    public static String parseLoginHint(String loginHint) {
        return IdentityAnchor.parseLoginHint(loginHint);
    }

    /**
     * Extract the operator-token candidate for the current request, or null if
     * this is not an operator-token request (caller falls through to normal
     * validation). Precedence: Authorization scheme → login_hint → header.
     */
    public String extractCandidate(String authorizationHeader,
                                   String loginHint,
                                   String operatorTokenHeader) {
        String bearer = extractBearer(authorizationHeader);
        if (bearer != null && bearer.regionMatches(true, 0, OPERATOR_SCHEME_PREFIX, 0,
                OPERATOR_SCHEME_PREFIX.length())) {
            return bearer.substring(OPERATOR_SCHEME_PREFIX.length()).trim();
        }
        String fromHint = parseLoginHint(loginHint);
        if (fromHint != null) {
            return fromHint;
        }
        if (entitlementConfig.cibaEnabled()
                && operatorTokenHeader != null && !operatorTokenHeader.isBlank()) {
            return operatorTokenHeader.trim();
        }
        return null;
    }

    /**
     * Consume the candidate token and return its bound identity, or null on
     * ANY failure (invalid signature, expired, replayed, disabled service,
     * EAP method outside the whitelist).
     */
    public EntitlementTokenService.EntitlementRecord resolve(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (!entitlementConfig.enabled()) {
            LOG.warn("Operator token presented while entitlement service is disabled — rejecting");
            return null;
        }
        EntitlementTokenService.EntitlementRecord record = entitlementTokens.exchange(candidate);
        if (record == null) {
            LOG.warn("Operator token rejected (invalid, expired or already used)");
            return null;
        }
        // B3 — fail closed on any authentication scheme we did not run:
        // only EAP-AKA / EAP-AKA' prove SIM possession over the Wi-Fi path.
        if (record.eapMethod() == null || !EAP_METHOD_WHITELIST.contains(record.eapMethod())) {
            LOG.warn("Operator token rejected: unsupported eapMethod={} "
                    + "(whitelist: {}, {})",
                    record.eapMethod(),
                    EntitlementTokenService.EAP_AKA, EntitlementTokenService.EAP_AKA_PRIME);
            return null;
        }
        return record;
    }

    private static String extractBearer(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = h.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
