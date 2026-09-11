/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

public class OAuthException extends RuntimeException {

    private final String error;
    private final int httpStatus;

    protected OAuthException(String error, int httpStatus, String description) {
        super(description);
        this.error = error;
        this.httpStatus = httpStatus;
    }

    public static OAuthException invalidRequest(String description) {
        return new OAuthException("invalid_request", 400, description);
    }

    public static OAuthException invalidClient(String description) {
        return new OAuthException("invalid_client", 401, description);
    }

    public static OAuthException invalidGrant(String description) {
        return new OAuthException("invalid_grant", 400, description);
    }

    public static OAuthException invalidScope(String description) {
        return new OAuthException("invalid_scope", 400, description);
    }

    public static OAuthException unauthorizedClient(String description) {
        return new OAuthException("unauthorized_client", 400, description);
    }

    public static OAuthException unsupportedGrantType(String description) {
        return new OAuthException("unsupported_grant_type", 400, description);
    }

    public static OAuthException accessDenied(String description) {
        return new OAuthException("access_denied", 403, description);
    }

    public static OAuthException serverError(String description) {
        return new OAuthException("server_error", 500, description);
    }

    public String error() {
        return error;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
