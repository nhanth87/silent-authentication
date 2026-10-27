/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * CAMARA Number Verification v2 device side. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

/**
 * The authorization code the operator issued, to be handed to the bank backend
 * and nothing else.
 *
 * <p>The code is not a verdict. The backend redeems it at the token endpoint
 * (with {@code private_key_jwt} and the PKCE verifier only it holds) and calls
 * {@code POST /number-verification/v2/verify}; that answer is the only thing that
 * authenticates anyone. The code is single use and short-lived, so the app should
 * send it on at once and not store it. {@link #toString()} redacts it so a stray
 * log line cannot leak it.</p>
 */
public final class AuthorizationResult {

    /** How the subscriber was authenticated at the authorization endpoint. */
    public enum Method {
        /** Network-based: the request left over the cellular bearer. */
        NETWORK,
        /** SIM-based: a GSMA TS.43 operator token was presented as the login hint. */
        OPERATOR_TOKEN
    }

    private final String code;
    private final String state;
    private final Method method;
    private final AccessTech accessTech;

    AuthorizationResult(String code, String state, Method method, AccessTech accessTech) {
        this.code = code;
        this.state = state;
        this.method = method;
        this.accessTech = accessTech == null ? AccessTech.UNKNOWN : accessTech;
    }

    /** The one-time authorization code. Send it to the backend; never log it. */
    public String code() {
        return code;
    }

    /** The {@code state} echoed back (already checked against the request). */
    public String state() {
        return state;
    }

    public Method method() {
        return method;
    }

    /** The bearer the request left on. Advisory, never an assurance input. */
    public AccessTech accessTech() {
        return accessTech;
    }

    @Override
    public String toString() {
        return "AuthorizationResult[method=" + method + ", accessTech=" + accessTech
                + ", code=<redacted>]";
    }
}
