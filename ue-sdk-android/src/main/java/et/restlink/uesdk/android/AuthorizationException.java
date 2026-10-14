/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * CAMARA Number Verification v2 device side. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;

/**
 * The authorization server refused, or answered in a way the SDK cannot accept.
 *
 * <p>Every instance is a <strong>fallback signal</strong>: the app takes its
 * OTP / passkey path. Nothing here ever means "verified". The common values of
 * {@link #error()}:</p>
 * <ul>
 *   <li>{@code access_denied} — the network could not tie this connection (or the
 *       operator token) to a subscriber;</li>
 *   <li>{@code login_required} / {@code interaction_required} — the server would
 *       need a page, which {@code prompt=none} forbids;</li>
 *   <li>{@code invalid_request}, {@code unauthorized_client}, {@code invalid_scope}
 *       — the backend built a request the server rejects (a bug to fix, not to retry);</li>
 *   <li>{@code temporarily_unavailable}, {@code server_error} — try again later;</li>
 *   <li>SDK-side: {@code invalid_state} (the redirect is not for this request),
 *       {@code invalid_response}, {@code too_many_redirects}.</li>
 * </ul>
 *
 * <p>The message carries only the error code. The server's
 * {@link #description()} is kept separately so an app that logs exceptions does
 * not log free text it does not control.</p>
 */
public class AuthorizationException extends IOException {

    private final String error;
    private final String description;
    private final int httpStatus;

    AuthorizationException(String error, String description, int httpStatus) {
        super("authorization refused: " + (error == null ? "unknown_error" : error)
                + (httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "")
                + " - fall back to OTP, never proceed");
        this.error = error == null ? "unknown_error" : error;
        this.description = description;
        this.httpStatus = httpStatus;
    }

    /** The OAuth error code (RFC 6749 §4.1.2.1) or an SDK-side code. */
    public String error() {
        return error;
    }

    /** The server's {@code error_description}, may be null. Do not log it. */
    public String description() {
        return description;
    }

    /** Status of a direct (non-redirect) error answer; 0 for a redirected error. */
    public int httpStatus() {
        return httpStatus;
    }

    /** True for the errors a later attempt may cure. */
    public boolean retryLater() {
        return "temporarily_unavailable".equals(error) || "server_error".equals(error);
    }
}
