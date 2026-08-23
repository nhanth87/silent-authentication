/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

/**
 * Port for the operator-token identity anchor (TS.43 / Wi-Fi track): validates
 * and consumes a {@code operatortoken:<tk>} candidate. Adapted in production by
 * {@link OperatorTokenAnchor}; faked with lambdas in tests.
 */
public interface IdentityAnchor {

    /**
     * Consume the candidate token and return its bound subscriber, or null on
     * ANY failure (invalid signature, expired, replayed, disabled service).
     */
    OperatorBinding resolveOperatorToken(String candidate);

    /** Subscriber identity proven by the anchor (MSISDN normalized upstream). */
    record OperatorBinding(String msisdn, String imsi) {}
}
