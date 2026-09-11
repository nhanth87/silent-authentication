/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

public final class CibaException extends OAuthException {

    private CibaException(String error, int httpStatus, String description) {
        super(error, httpStatus, description);
    }

    public static CibaException invalidRequest(String description) {
        return new CibaException("invalid_request", 400, description);
    }

    public static CibaException invalidScope(String description) {
        return new CibaException("invalid_scope", 400, description);
    }

    public static CibaException accessDenied(String description) {
        return new CibaException("access_denied", 403, description);
    }

    public static CibaException expiredToken(String description) {
        return new CibaException("expired_token", 400, description);
    }
}
