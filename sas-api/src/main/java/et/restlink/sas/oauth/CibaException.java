/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

/**
 * OAuth/CIBA error carried from the services to the northbound resources and
 * mapped 1:1 onto the RFC 6749 §5.2 error response body.
 */
public final class CibaException extends RuntimeException {

    private final String error;
    private final int httpStatus;

    private CibaException(String error, int httpStatus, String description) {
        super(description);
        this.error = error;
        this.httpStatus = httpStatus;
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

    public String error() {
        return error;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
