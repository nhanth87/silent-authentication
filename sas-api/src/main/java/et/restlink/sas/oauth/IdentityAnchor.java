/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

/**
 * Port for the operator-token identity anchor (TS.43 / Wi-Fi track): extracts
 * {@code operatortoken:<tk>} candidates and validates/consumes them. Adapted
 * in production by {@link OperatorTokenAnchor}; faked with lambdas in tests.
 */
public interface IdentityAnchor {

    /** URI scheme prefix marking an entitlement token inside a Bearer/login_hint value. */
    String OPERATOR_SCHEME_PREFIX = "operatortoken:";

    /**
     * Consume the candidate token and return its bound subscriber, or null on
     * ANY failure (invalid signature, expired, replayed, disabled service).
     */
    OperatorBinding resolveOperatorToken(String candidate);

    /**
     * Extract the operator-token candidate for the current request, or null if
     * this is not an operator-token request (caller falls through to normal
     * validation). Precedence: Authorization scheme → login_hint → header.
     * Default: no operator-token source (cellular-only ports stay inert).
     */
    default String extractCandidate(String authorizationHeader,
                                    String loginHint,
                                    String operatorTokenHeader) {
        return null;
    }

    /**
     * Parse a CIBA {@code login_hint} of the form
     * {@code operatortoken:<token>}. Returns the embedded token, or null when
     * the hint is absent or not operator-token shaped.
     */
    static String parseLoginHint(String loginHint) {
        if (loginHint == null) {
            return null;
        }
        String hint = loginHint.trim();
        if (hint.length() <= OPERATOR_SCHEME_PREFIX.length()
                || !hint.regionMatches(true, 0, OPERATOR_SCHEME_PREFIX, 0,
                        OPERATOR_SCHEME_PREFIX.length())) {
            return null;
        }
        String token = hint.substring(OPERATOR_SCHEME_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Subscriber identity proven by the anchor (MSISDN normalized upstream). */
    record OperatorBinding(String msisdn, String imsi, String eapMethod) {

        /** Legacy shape without EAP-method evidence (cellular-resolved anchors). */
        public OperatorBinding(String msisdn, String imsi) {
            this(msisdn, imsi, null);
        }
    }
}
