/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

/**
 * An access token from the operator. Single use for Number Verification and
 * at most 300 s long. {@code dpopBound} tokens are useless without the backend's
 * DPoP key. {@link #toString()} redacts the token.
 */
public record AccessToken(String value, boolean dpopBound, long expiresInSeconds, String scope,
                          String idToken) {

    @Override
    public String toString() {
        return "AccessToken[" + (dpopBound ? "DPoP" : "Bearer") + ", expires_in=" + expiresInSeconds
                + ", scope=" + scope + ", value=<redacted>]";
    }
}
