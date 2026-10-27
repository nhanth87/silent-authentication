/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The customer-backend half of CAMARA Number Verification v2: everything the bank's
 * server does, and nothing the device should.
 *
 * <pre>
 *  1  start(userSession)   → /authorize URL (PKCE S256, state, nonce, prompt=none)
 *                             the verifier/nonce/session stay here (PendingAuthorizations)
 *  2  device SDK            runs the URL over cellular → code (+ state)
 *  3  complete(code, state) → take(state) once ─► POST /token
 *                             authorization_code + code_verifier + private_key_jwt [+ DPoP]
 *  4  verify(token, number) → POST /number-verification/v2/verify   {devicePhoneNumberVerified}
 *     devicePhoneNumber(t)  → GET  /number-verification/v2/device-phone-number
 *  alt  ciba(loginHint)     → /bc-authorize + poll /token  (operatortoken:, ipport:, tel:)
 *  alt  jwtBearer(subject)  → /token grant_type=jwt-bearer  (operatortoken:, tel:)
 * </pre>
 *
 * <h2>Fail-closed rules</h2>
 * <ul>
 *   <li>A state is redeemed once: a replayed or unknown state never reaches /token.</li>
 *   <li>A DPoP-capable backend that asked for DPoP and received a Bearer token keeps
 *       it as Bearer (CAMARA: the provider may not support DPoP); one that is
 *       registered as DPoP-bound gets an error from the provider instead.</li>
 *   <li>{@code devicePhoneNumberVerified} is true only when the answer says exactly
 *       {@code true}; anything else is false or an exception.</li>
 *   <li>No token, code, verifier or phone number is ever logged (this class logs nothing).</li>
 * </ul>
 */
public final class NumberVerificationBackend {

    public static final String SCOPE_VERIFY = "number-verification:verify";
    public static final String SCOPE_DEVICE_PHONE_NUMBER = "number-verification:device-phone-number:read";
    static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    static final String CIBA_GRANT = "urn:openid:params:grant-type:ciba";

    /** Where the operator's endpoints are, and who the bank is. */
    public record Config(String authorizationEndpoint, String tokenEndpoint, String backchannelEndpoint,
                         String apiRoot, String clientId, String redirectUri, String purpose,
                         Jose clientKey, Jose dpopKey, Duration timeout) {
        public Config {
            Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
            Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
            Objects.requireNonNull(apiRoot, "apiRoot");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(redirectUri, "redirectUri");
            purpose = purpose == null ? "dpv:FraudPreventionAndDetection" : purpose;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }
    }

    /** What the backend hands the device: the URL to run. The state is for the bank's own bookkeeping. */
    public record Started(String authorizeUrl, String state) {}

    /** A redeemed authorization: the token and the bank user session that started it. */
    public record Completed(AccessToken token, String userSession) {}

    private final Config config;
    private final PendingAuthorizations pending;
    private final HttpClient http;

    public NumberVerificationBackend(Config config, PendingAuthorizations pending, HttpClient http) {
        this.config = Objects.requireNonNull(config);
        this.pending = Objects.requireNonNull(pending);
        this.http = http != null ? http : HttpClient.newBuilder().connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public NumberVerificationBackend(Config config) {
        this(config, new PendingAuthorizations(), null);
    }

    // -------------------------------------------------------------- 3-legged

    /**
     * Step 1. {@code loginHint} is optional ({@code operatortoken:…} when the app already
     * holds a TS.43 token). Scopes default to {@code openid} + purpose + verify.
     */
    public Started start(String userSession, String loginHint, String... scopes) {
        String verifier = Jose.random(32);
        String state = Jose.random(24);
        String nonce = Jose.random(16);
        pending.put(new PendingAuthorizations.Pending(state, verifier, nonce, userSession, pending.now()));
        String scope = "openid " + config.purpose() + " "
                + (scopes.length == 0 ? SCOPE_VERIFY : String.join(" ", scopes));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", config.clientId());
        q.put("redirect_uri", config.redirectUri());
        q.put("scope", scope);
        q.put("state", state);
        q.put("nonce", nonce);
        q.put("code_challenge", Jose.s256(verifier));
        q.put("code_challenge_method", "S256");
        q.put("prompt", "none");
        if (loginHint != null && !loginHint.isBlank()) {
            q.put("login_hint", loginHint);
        }
        return new Started(config.authorizationEndpoint() + "?" + form(q), state);
    }

    /**
     * Step 3: redeem the code the device returned. The state must be one this backend
     * issued and has not redeemed, which is the CSRF and replay check.
     *
     * @throws NvApiException {@code invalid_state} for an unknown / replayed / expired state,
     *                        or the token endpoint's OAuth error
     */
    public Completed complete(String code, String state) throws IOException, InterruptedException {
        PendingAuthorizations.Pending p = pending.take(state).orElseThrow(() ->
                new NvApiException(400, "invalid_state", "state is unknown, expired or already used"));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", config.redirectUri());
        form.put("code_verifier", p.codeVerifier());
        return new Completed(token(form), p.userSession());
    }

    // -------------------------------------------------------------- CIBA / JWT bearer

    /**
     * CIBA poll mode with a CAMARA login hint ({@code operatortoken:…}, {@code ipport:…},
     * {@code tel:+…}). Polls at the interval the provider asks for, up to its expiry.
     */
    public AccessToken ciba(String loginHint, String scope) throws IOException, InterruptedException {
        if (config.backchannelEndpoint() == null) {
            throw new IllegalStateException("no backchannel endpoint configured");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("login_hint", loginHint);
        form.put("scope", config.purpose() + " " + scope);
        clientAuth(form, config.backchannelEndpoint());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(config.backchannelEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(form))));
        Map<String, String> body = json(r);
        if (r.statusCode() != 200) {
            throw NvApiException.of(r.statusCode(), body);
        }
        String authReqId = body.get("auth_req_id");
        long interval = Math.max(1, parseLong(body.get("interval"), 2));
        long deadline = System.currentTimeMillis() + 1000L * parseLong(body.get("expires_in"), 120);
        while (true) {
            Map<String, String> poll = new LinkedHashMap<>();
            poll.put("grant_type", CIBA_GRANT);
            poll.put("auth_req_id", authReqId);
            try {
                return token(poll);
            } catch (NvApiException e) {
                boolean wait = "authorization_pending".equals(e.code()) || "slow_down".equals(e.code());
                if (!wait || System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(1000L * interval * ("slow_down".equals(e.code()) ? 2 : 1));
            }
        }
    }

    /** JWT bearer grant: the assertion names the user ({@code operatortoken:…} or {@code tel:+…}). */
    public AccessToken jwtBearer(String subject, String scope) throws IOException, InterruptedException {
        if (config.clientKey() == null) {
            throw new IllegalStateException("the JWT bearer grant needs a client key");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", JWT_BEARER_GRANT);
        form.put("assertion", config.clientKey().grantAssertion(config.clientId(), config.tokenEndpoint(),
                subject, config.purpose() + " " + scope));
        return token(form, false);
    }

    // -------------------------------------------------------------- the NV API

    /** {@code POST /verify} with the plain E.164 number. */
    public boolean verify(AccessToken token, String phoneNumber) throws IOException, InterruptedException {
        return verifyBody(token, "{\"phoneNumber\":\"" + FlatJson.escape(phoneNumber) + "\"}");
    }

    /** {@code POST /verify} with {@code hashedPhoneNumber} = SHA-256 hex of the E.164 number. */
    public boolean verifyHashed(AccessToken token, String phoneNumber) throws IOException, InterruptedException {
        return verifyBody(token, "{\"hashedPhoneNumber\":\"" + hashPhoneNumber(phoneNumber) + "\"}");
    }

    /** {@code GET /device-phone-number}: the number the token was issued for. */
    public String devicePhoneNumber(AccessToken token) throws IOException, InterruptedException {
        String url = config.apiRoot() + "/number-verification/v2/device-phone-number";
        HttpResponse<String> r = send(authorized(HttpRequest.newBuilder(URI.create(url)), token, "GET", url).GET());
        Map<String, String> body = json(r);
        if (r.statusCode() != 200 || body.get("devicePhoneNumber") == null) {
            throw NvApiException.of(r.statusCode(), body);
        }
        return body.get("devicePhoneNumber");
    }

    /** CAMARA {@code hashedPhoneNumber}: lower-case hex SHA-256 of the E.164 string ({@code +} included). */
    public static String hashPhoneNumber(String e164) {
        return HexFormat.of().formatHex(Jose.sha256(e164.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean verifyBody(AccessToken token, String body) throws IOException, InterruptedException {
        String url = config.apiRoot() + "/number-verification/v2/verify";
        HttpResponse<String> r = send(authorized(HttpRequest.newBuilder(URI.create(url)), token, "POST", url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        Map<String, String> answer = json(r);
        if (r.statusCode() != 200) {
            throw NvApiException.of(r.statusCode(), answer);
        }
        String verified = answer.get("devicePhoneNumberVerified");
        if (!"true".equals(verified) && !"false".equals(verified)) {
            throw new NvApiException(r.statusCode(), "invalid_response", "no devicePhoneNumberVerified boolean");
        }
        return "true".equals(verified);
    }

    // -------------------------------------------------------------- plumbing

    private HttpRequest.Builder authorized(HttpRequest.Builder b, AccessToken token, String method, String url) {
        b.header("x-correlator", UUID.randomUUID().toString());
        if (token.dpopBound()) {
            if (config.dpopKey() == null) {
                throw new IllegalStateException("a DPoP-bound token needs the DPoP key that bound it");
            }
            return b.header("Authorization", "DPoP " + token.value())
                    .header("DPoP", config.dpopKey().dpopProof(method, url, token.value()));
        }
        return b.header("Authorization", "Bearer " + token.value());
    }

    private AccessToken token(Map<String, String> form) throws IOException, InterruptedException {
        return token(form, true);
    }

    private AccessToken token(Map<String, String> form, boolean withClientAuth) throws IOException, InterruptedException {
        if (withClientAuth) {
            clientAuth(form, config.tokenEndpoint());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.tokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (config.dpopKey() != null) {
            b.header("DPoP", config.dpopKey().dpopProof("POST", config.tokenEndpoint(), null));
        }
        HttpResponse<String> r = send(b.POST(HttpRequest.BodyPublishers.ofString(form(form))));
        Map<String, String> body = json(r);
        if (r.statusCode() != 200 || body.get("access_token") == null) {
            throw NvApiException.of(r.statusCode(), body);
        }
        String type = body.get("token_type");
        if (!"Bearer".equalsIgnoreCase(type) && !"DPoP".equalsIgnoreCase(type)) {
            throw new NvApiException(r.statusCode(), "invalid_response", "unknown token_type");
        }
        return new AccessToken(body.get("access_token"), "DPoP".equalsIgnoreCase(type),
                parseLong(body.get("expires_in"), 0), body.get("scope"), body.get("id_token"));
    }

    /** private_key_jwt when a client key is configured; otherwise a public client (lab only). */
    private void clientAuth(Map<String, String> form, String audience) {
        form.put("client_id", config.clientId());
        if (config.clientKey() != null) {
            form.put("client_assertion_type", CLIENT_ASSERTION_TYPE);
            form.put("client_assertion", config.clientKey().clientAssertion(config.clientId(), audience));
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.timeout(config.timeout()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> json(HttpResponse<String> r) {
        try {
            return FlatJson.parse(r.body());
        } catch (IllegalArgumentException notJson) {
            return Map.of();
        }
    }

    static String form(Map<String, String> fields) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return b.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
