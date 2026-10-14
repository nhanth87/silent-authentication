/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * CAMARA Number Verification v2 device side. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OIDC authorization request the bank backend built, checked before a single
 * byte leaves the device.
 *
 * <p>The device never builds this URL. The backend is the OAuth client: it owns
 * the {@code client_id}, the PKCE {@code code_verifier}, the {@code state} and
 * the {@code nonce}, and it authenticates to the token endpoint with
 * {@code private_key_jwt}. The SDK only carries the request over the right
 * bearer and catches the redirect. What it does check is that the request is
 * one the device can make <em>silently</em> and <em>safely</em>:</p>
 * <ul>
 *   <li>{@code https} (plain {@code http} only with an explicit lab switch);</li>
 *   <li>{@code response_type=code}, PKCE {@code code_challenge_method=S256} with a
 *       challenge — the backend keeps the verifier;</li>
 *   <li>{@code prompt=none} — CAMARA NV v2 is silent; without it an
 *       authorization server may show a page the SDK would then load blindly;</li>
 *   <li>{@code state} and an absolute {@code redirect_uri}, so the result can be
 *       matched to this request;</li>
 *   <li>no parameter repeated — two {@code redirect_uri}s are a pollution attack,
 *       not an ambiguity to resolve.</li>
 * </ul>
 *
 * <p>Error messages name the failed rule and never echo the URL: the query can
 * carry a {@code login_hint} with an operator token.</p>
 */
public final class AuthorizeUrl {

    /** What an operator-token login hint is prefixed with (CAMARA / TS.43). */
    public static final String OPERATOR_TOKEN_HINT_PREFIX = "operatortoken:";

    private final URI uri;
    private final Map<String, String> params;
    private final URI redirectUri;

    private AuthorizeUrl(URI uri, Map<String, String> params, URI redirectUri) {
        this.uri = uri;
        this.params = params;
        this.redirectUri = redirectUri;
    }

    /**
     * @param raw              the URL as the backend handed it over
     * @param allowInsecureHttp accept {@code http://} — lab only, never in a release build
     * @throws IllegalArgumentException naming the first rule the request breaks
     */
    public static AuthorizeUrl parse(String raw, boolean allowInsecureHttp) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("authorization URL is empty");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("authorization URL is not a valid URI");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException("authorization URL must be absolute with a host");
        }
        requireTransport(uri, allowInsecureHttp, "authorization URL");
        Map<String, String> params = query(uri.getRawQuery());
        require("code".equals(params.get("response_type")), "response_type must be code");
        require(nonBlank(params.get("client_id")), "client_id is missing");
        require(nonBlank(params.get("scope")), "scope is missing");
        require(nonBlank(params.get("state")), "state is missing");
        require("S256".equals(params.get("code_challenge_method"))
                && nonBlank(params.get("code_challenge")),
                "PKCE code_challenge with code_challenge_method=S256 is required");
        require("none".equals(params.get("prompt")),
                "prompt=none is required: Number Verification must not show any page");
        String redirect = params.get("redirect_uri");
        require(nonBlank(redirect), "redirect_uri is missing");
        URI redirectUri;
        try {
            redirectUri = new URI(redirect);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("redirect_uri is not a valid URI");
        }
        require(redirectUri.isAbsolute(), "redirect_uri must be absolute");
        return new AuthorizeUrl(uri, Collections.unmodifiableMap(params), redirectUri);
    }

    /** The request URI to send. */
    public URI uri() {
        return uri;
    }

    /** The {@code state} the redirect must carry back. */
    public String state() {
        return params.get("state");
    }

    /** Where the authorization server will send the browser — never loaded by the SDK. */
    public URI redirectUri() {
        return redirectUri;
    }

    /** True when the backend already chose a login hint. */
    public boolean hasLoginHint() {
        return params.containsKey("login_hint");
    }

    /**
     * The same request with {@code login_hint=operatortoken:<token>} — the TS.43
     * path, used when no cellular bearer is available. Refused when the backend
     * already set a hint: the SDK does not override the bank's choice.
     */
    public URI withOperatorToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("operator token is empty");
        }
        if (hasLoginHint()) {
            throw new IllegalArgumentException("the request already carries a login_hint");
        }
        String hint = "login_hint=" + encode(OPERATOR_TOKEN_HINT_PREFIX + token.trim());
        String s = uri.toString();
        int fragment = s.indexOf('#');
        String head = fragment < 0 ? s : s.substring(0, fragment);
        String tail = fragment < 0 ? "" : s.substring(fragment);
        return URI.create(head + (uri.getRawQuery() == null ? "?" : "&") + hint + tail);
    }

    /**
     * True when {@code location} is the registered redirect: same scheme,
     * authority and path. The query is where the result lives, so it is not
     * compared.
     */
    boolean isRedirect(URI location) {
        return location != null
                && equalsIgnoreCase(redirectUri.getScheme(), location.getScheme())
                && equalsIgnoreCase(redirectUri.getRawAuthority(), location.getRawAuthority())
                && samePath(redirectUri.getRawPath(), location.getRawPath());
    }

    /**
     * Turns the redirect the authorization server sent into a result, checking
     * {@code state} in constant time.
     *
     * @throws AuthorizationException for an OAuth error, a missing code, or a
     *         state that does not match this request
     */
    AuthorizationResult complete(URI location, AuthorizationResult.Method method, AccessTech tech)
            throws AuthorizationException {
        Map<String, String> result;
        try {
            result = query(location.getRawQuery());
        } catch (IllegalArgumentException malformed) {
            throw new AuthorizationException("invalid_response",
                    "the redirect query is malformed", 0);
        }
        String error = result.get("error");
        if (error != null) {
            throw new AuthorizationException(error, result.get("error_description"), 0);
        }
        String code = result.get("code");
        if (!nonBlank(code)) {
            throw new AuthorizationException("invalid_response", "the redirect carries no code", 0);
        }
        if (!constantTimeEquals(state(), result.get("state"))) {
            throw new AuthorizationException("invalid_state",
                    "the redirect state does not match this request", 0);
        }
        return new AuthorizationResult(code, result.get("state"), method, tech);
    }

    @Override
    public String toString() {
        // No query: it may carry a login_hint.
        return "AuthorizeUrl[" + uri.getScheme() + "://" + uri.getRawAuthority()
                + uri.getRawPath() + "]";
    }

    // ------------------------------------------------------------------ helpers

    static void requireTransport(URI uri, boolean allowInsecureHttp, String what) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && allowInsecureHttp) {
            return;
        }
        throw new IllegalArgumentException(what + " must use https");
    }

    /** Decodes a query; a repeated parameter is refused, never resolved. */
    static Map<String, String> query(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (out.containsKey(key)) {
                throw new IllegalArgumentException("parameter repeated: " + key);
            }
            out.put(key, value);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean ok, String rule) {
        if (!ok) {
            throw new IllegalArgumentException(rule);
        }
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static boolean samePath(String a, String b) {
        String x = a == null || a.isEmpty() ? "/" : a;
        String y = b == null || b.isEmpty() ? "/" : b;
        return x.equals(y);
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(expected.getBytes("UTF-8"), actual.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
