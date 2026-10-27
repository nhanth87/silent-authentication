/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * CAMARA Number Verification v2 device side. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device side of CAMARA Number Verification v2: carries the bank's OIDC
 * authorization request ({@code prompt=none}) over the operator's network and
 * returns the authorization code — silently, with no page, no SMS and no OTP.
 *
 * <h2>Who does what</h2>
 * <pre>
 *  app ──► bank backend      "start number verification"
 *          backend builds   /authorize?response_type=code&amp;client_id&amp;redirect_uri&amp;scope
 *                            &amp;state&amp;nonce&amp;code_challenge(S256)&amp;prompt=none
 *                            (keeps the code_verifier; authenticates with private_key_jwt)
 *  app ──► NumberVerification.authorize(url)
 *          ├─ cellular up?  requestNetwork(CELLULAR) ─► GET /authorize on that Network
 *          │                 operator sees the bearer's source address ─► subscriber
 *          └─ no cellular   OperatorTokenSource ─► GET /authorize&amp;login_hint=operatortoken:…
 *                            over the default route (Wi-Fi): SIM-based, GSMA TS.43
 *          ◄── 302 redirect_uri?code&amp;state   (caught, never loaded; state checked)
 *  app ──► bank backend      code
 *          backend ──► /token (authorization_code + code_verifier + private_key_jwt)
 *          backend ──► POST /number-verification/v2/verify {phoneNumber}
 *                      ◄── {"devicePhoneNumberVerified": true|false}
 * </pre>
 *
 * <h2>States and outcomes</h2>
 * <pre>
 *  VALIDATE ─bad request─► IllegalArgumentException            (backend bug)
 *     │
 *  CELLULAR ─granted──► AUTHORIZE(NETWORK) ─code─► AuthorizationResult
 *     │                     └─access_denied + token source ─► TOKEN
 *     └─not granted──► TOKEN ─token─► AUTHORIZE(OPERATOR_TOKEN) ─code─► AuthorizationResult
 *                        └─no source / no token ─► CellularUnavailableException
 *  any OAuth error ─► AuthorizationException      transport failure ─► IOException
 * </pre>
 * Every exception is a fallback signal: the app takes OTP / passkey. Nothing but
 * the backend's {@code /verify} answer ever means "verified".
 *
 * <h2>Timeouts and retries</h2>
 * <table>
 *   <caption>defaults</caption>
 *   <tr><td>bring cellular up (requestNetwork)</td><td>5 s</td><td>then the token path, else fall back</td></tr>
 *   <tr><td>connect / read, per hop</td><td>5 s / 5 s</td><td>one retry on a transport error</td></tr>
 *   <tr><td>redirect hops</td><td>10</td><td>{@code too_many_redirects}</td></tr>
 *   <tr><td>whole call</td><td>15 s</td><td>{@link SocketTimeoutException}</td></tr>
 * </table>
 * Only transport errors are retried. An OAuth error is an answer, not a fault, so
 * it is never retried here.
 *
 * <h2>Privacy</h2>
 * The SDK writes no log at all. Exception messages carry rule names and OAuth
 * error codes only — never the URL, the code, a token, the MSISDN or the IMSI.
 *
 * <h2>Threading</h2>
 * Blocking; call it off the main thread. The SDK starts no thread and keeps no
 * callback registered after it returns.
 */
public final class NumberVerification {

    /** Supplies the cellular bearer; replaceable for dual-SIM apps and tests. */
    public interface BearerSource {

        /**
         * @return a bearer that satisfies {@code need}; the SDK closes it
         * @throws CellularUnavailableException when there is none
         */
        CellularBearer acquire(CellularRequirement need, long timeoutMs)
                throws CellularUnavailableException;
    }

    private static final Pattern JSON_ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\"");
    private static final int MAX_BODY = 8 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Object context;
    private final CellularRequirement requirement;
    private final BearerSource bearers;
    private final OperatorTokenSource operatorTokens;
    private final long cellularTimeoutMs;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxRedirects;
    private final int transportRetries;
    private final long budgetMs;
    private final boolean allowInsecureHttp;

    private NumberVerification(Builder b) {
        this.context = b.context;
        this.requirement = b.requirement;
        this.operatorTokens = b.operatorTokens;
        this.cellularTimeoutMs = b.cellularTimeoutMs;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.maxRedirects = b.maxRedirects;
        this.transportRetries = b.transportRetries;
        this.budgetMs = b.budgetMs;
        this.allowInsecureHttp = b.allowInsecureHttp;
        final Object ctx = b.context;
        this.bearers = b.bearers != null ? b.bearers : new BearerSource() {
            @Override
            public CellularBearer acquire(CellularRequirement need, long timeoutMs)
                    throws CellularUnavailableException {
                return CellularNetworkRequest.request(ctx, need, timeoutMs);
            }
        };
    }

    /**
     * @param context an {@code android.content.Context}; typed {@code Object}
     *                because this module compiles without {@code android.jar}
     */
    public static Builder builder(Object context) {
        return new Builder(context);
    }

    /**
     * Runs the authorization request the backend built and returns the code.
     *
     * @param authorizeUrl the full {@code /authorize} URL from the bank backend
     * @return the authorization code, to send to the backend at once
     * @throws IllegalArgumentException     the request breaks a rule in {@link AuthorizeUrl}
     * @throws CellularUnavailableException no cellular bearer and no operator token
     * @throws AuthorizationException       the authorization server refused
     * @throws IOException                  transport failure after retries
     */
    public AuthorizationResult authorize(String authorizeUrl) throws IOException {
        long deadline = System.currentTimeMillis() + budgetMs;
        AuthorizeUrl request = AuthorizeUrl.parse(authorizeUrl, allowInsecureHttp);

        CellularUnavailableException noCellular;
        CellularBearer bearer;
        try {
            bearer = bearers.acquire(requirement, cellularTimeoutMs);
            noCellular = null;
        } catch (CellularUnavailableException e) {
            bearer = null;
            noCellular = e;
        }
        if (bearer != null) {
            try {
                return follow(request, request.uri(), bearer,
                        AuthorizationResult.Method.NETWORK, deadline);
            } catch (AuthorizationException denied) {
                // The network could not name the subscriber (CGNAT without a
                // binding, roaming, a tethered laptop). The SIM can still prove
                // it; any other refusal is final.
                if (!"access_denied".equals(denied.error()) || operatorTokens == null
                        || request.hasLoginHint()) {
                    throw denied;
                }
            } finally {
                bearer.close();
            }
        }
        if (operatorTokens == null || request.hasLoginHint()) {
            throw noCellular != null ? noCellular
                    : new CellularUnavailableException(requirement, AccessTech.UNKNOWN);
        }
        String token;
        try {
            token = operatorTokens.fetch();
        } catch (IOException sourceFailed) {
            token = null;
        }
        if (token == null || token.trim().isEmpty()) {
            throw noCellular != null ? noCellular
                    : new AuthorizationException("access_denied",
                            "network authentication failed and no operator token is available", 0);
        }
        CellularBearer route = CellularBearer.unbound(context);
        try {
            return follow(request, request.withOperatorToken(token), route,
                    AuthorizationResult.Method.OPERATOR_TOKEN, deadline);
        } finally {
            route.close();
        }
    }

    // ---------------------------------------------------------------- redirects

    private AuthorizationResult follow(AuthorizeUrl request, URI start, CellularBearer via,
                                       AuthorizationResult.Method method, long deadline)
            throws IOException {
        URI current = start;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            AuthorizeUrl.requireTransport(current, allowInsecureHttp, "authorization hop");
            Hop answer = get(via, current, deadline);
            if (isRedirect(answer.status)) {
                if (answer.location == null || answer.location.trim().isEmpty()) {
                    throw new AuthorizationException("invalid_response",
                            "redirect without a Location", answer.status);
                }
                URI next;
                try {
                    next = current.resolve(answer.location.trim());
                } catch (IllegalArgumentException malformed) {
                    throw new AuthorizationException("invalid_response",
                            "redirect Location is not a URI", answer.status);
                }
                if (request.isRedirect(next)) {
                    return request.complete(next, method, via.accessTech());
                }
                current = next;
                continue;
            }
            throw direct(answer);
        }
        throw new AuthorizationException("too_many_redirects",
                "more than " + maxRedirects + " redirects", 0);
    }

    /** A non-redirect answer: an error document, or a page prompt=none forbids. */
    private static AuthorizationException direct(Hop answer) {
        if (answer.status >= 200 && answer.status < 300) {
            return new AuthorizationException("interaction_required",
                    "the authorization server answered with a page instead of a redirect",
                    answer.status);
        }
        Matcher m = JSON_ERROR.matcher(answer.body == null ? "" : answer.body);
        String error = m.find() ? m.group(1) : (answer.status >= 500 ? "server_error" : "invalid_request");
        return new AuthorizationException(error, null, answer.status);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    // ---------------------------------------------------------------- transport

    private static final class Hop {
        final int status;
        final String location;
        final String body;

        Hop(int status, String location, String body) {
            this.status = status;
            this.location = location;
            this.body = body;
        }
    }

    private Hop get(Connector via, URI target, long deadline) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= transportRetries; attempt++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new SocketTimeoutException("number verification budget exhausted");
            }
            if (attempt > 0) {
                pause(Math.min(250L * attempt, left));
            }
            try {
                return once(via, target, deadline);
            } catch (AuthorizationException answer) {
                throw answer;
            } catch (IOException transport) {
                last = transport;
            }
        }
        throw last;
    }

    private Hop once(Connector via, URI target, long deadline) throws IOException {
        URLConnection raw = via.open(target.toURL());
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("connector did not return an HTTP connection");
        }
        HttpURLConnection conn = (HttpURLConnection) raw;
        int left = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, deadline - System.currentTimeMillis()));
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setConnectTimeout(Math.min(connectTimeoutMs, left));
            conn.setReadTimeout(Math.min(readTimeoutMs, left));
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            String body = read(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Hop(status, location, body);
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            byte[] buf = new byte[MAX_BODY];
            int total = 0;
            int n;
            while (total < MAX_BODY && (n = in.read(buf, total, MAX_BODY - total)) > 0) {
                total += n;
            }
            return new String(buf, 0, total, UTF8);
        } catch (IOException unreadable) {
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // drained as far as it goes
            }
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ builder

    /** Configuration; every default is the production one. */
    public static final class Builder {

        private final Object context;
        private CellularRequirement requirement = CellularRequirement.CELLULAR;
        private BearerSource bearers;
        private OperatorTokenSource operatorTokens;
        private long cellularTimeoutMs = 5000L;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 5000;
        private int maxRedirects = 10;
        private int transportRetries = 1;
        private long budgetMs = 15000L;
        private boolean allowInsecureHttp;

        private Builder(Object context) {
            this.context = context;
        }

        /** Which cellular bearer is acceptable; default {@link CellularRequirement#CELLULAR}. */
        public Builder requirement(CellularRequirement value) {
            this.requirement = value == null ? CellularRequirement.CELLULAR : value;
            return this;
        }

        /** Source of the SIM-based token for the Wi-Fi path; null disables that path. */
        public Builder operatorTokens(OperatorTokenSource value) {
            this.operatorTokens = value;
            return this;
        }

        /**
         * Replaces {@code requestNetwork()} — for a dual-SIM app that requests the
         * network of a chosen subscription itself and wraps it with
         * {@link CellularBearer#fromNetwork(Object, AccessTech)}.
         */
        public Builder bearerSource(BearerSource value) {
            this.bearers = value;
            return this;
        }

        public Builder cellularTimeoutMs(long value) {
            this.cellularTimeoutMs = positive(value, "cellularTimeoutMs");
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            this.connectTimeoutMs = (int) positive(value, "connectTimeoutMs");
            return this;
        }

        public Builder readTimeoutMs(int value) {
            this.readTimeoutMs = (int) positive(value, "readTimeoutMs");
            return this;
        }

        public Builder maxRedirects(int value) {
            if (value < 0 || value > 20) {
                throw new IllegalArgumentException("maxRedirects must be 0..20");
            }
            this.maxRedirects = value;
            return this;
        }

        /** Retries per hop on a transport error; 0..3, default 1. */
        public Builder transportRetries(int value) {
            if (value < 0 || value > 3) {
                throw new IllegalArgumentException("transportRetries must be 0..3");
            }
            this.transportRetries = value;
            return this;
        }

        public Builder budgetMs(long value) {
            this.budgetMs = positive(value, "budgetMs");
            return this;
        }

        /** Accepts {@code http://} hops. Lab only — never in a release build. */
        public Builder allowInsecureHttpForLab(boolean value) {
            this.allowInsecureHttp = value;
            return this;
        }

        public NumberVerification build() {
            return new NumberVerification(this);
        }

        private static long positive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
