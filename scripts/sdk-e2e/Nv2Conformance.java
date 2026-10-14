/*
 * Silent Auth — Restlink (Ethiopia).
 * CAMARA Number Verification v2 conformance (request Phase 2) against the
 * PACKAGED sas-camara + sas-ecs, driven by the shipped bank-backend-sdk and
 * ue-sdk-android. Run by scripts/camara-nv2-conformance.sh inside a private
 * network namespace.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import et.restlink.nvbackend.AccessToken;
import et.restlink.nvbackend.Jose;
import et.restlink.nvbackend.NumberVerificationBackend;
import et.restlink.nvbackend.NvApiException;
import et.restlink.uesdk.android.AccessTech;
import et.restlink.uesdk.android.AuthorizationResult;
import et.restlink.uesdk.android.CellularBearer;
import et.restlink.uesdk.android.NumberVerification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two modes:
 * <pre>
 *   --emit-clients &lt;dir&gt;   generate the bank's client keys, write their private JWKs to
 *                          &lt;dir&gt;, print the clients JSON the gateway is started with
 *   --run &lt;dir&gt;            run every check; exit 0 only when all pass
 * </pre>
 */
public class Nv2Conformance {

    static final String CAM = "http://127.0.0.1:8085";
    static final String CAM_FROM_CELL = "http://10.20.30.40:8085";
    static final String ECS = "http://127.0.0.1:8095";
    static final String MSISDN = "+251911111111";
    static final String OTHER = "+251922222222";
    static final String PURPOSE = "dpv:FraudPreventionAndDetection";
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    static final List<String> results = new ArrayList<>();
    static int failures;
    static String section = "";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[1]);
        if ("--emit-clients".equals(args[0])) {
            Files.createDirectories(dir);
            Jose a = Jose.generate("bank-a-1");
            Jose d = Jose.generate("bank-d-1");
            Files.writeString(dir.resolve("bank-a.jwk"), a.privateJwk());
            Files.writeString(dir.resolve("bank-d.jwk"), d.privateJwk());
            System.out.println("{\"clients\":[" + client("bank-a", a, false) + "," + client("bank-d", d, true) + "]}");
            return;
        }
        Jose bankA = Jose.fromPrivateJwk(Files.readString(dir.resolve("bank-a.jwk")));
        Jose bankD = Jose.fromPrivateJwk(Files.readString(dir.resolve("bank-d.jwk")));
        long ttl = Long.parseLong(args.length > 2 ? args[2] : "20");
        run(bankA, bankD, ttl);
        System.out.println();
        System.out.println(failures == 0 ? "ALL " + results.size() + " CHECKS PASS" : failures + " of " + results.size() + " CHECKS FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String client(String id, Jose key, boolean dpopBound) {
        return "{\"client_id\":\"" + id + "\",\"token_endpoint_auth_method\":\"private_key_jwt\",\"jwks\":"
                + key.publicJwks() + ",\"grant_types\":[\"authorization_code\",\"urn:openid:params:grant-type:ciba\","
                + "\"urn:ietf:params:oauth:grant-type:jwt-bearer\",\"client_credentials\"],"
                + "\"redirect_uris\":[\"https://bank.example/cb\"],\"scopes\":[\"number-verification:verify\","
                + "\"number-verification:device-phone-number:read\",\"sim-swap:check\"],\"purposes\":[\"" + PURPOSE + "\"]"
                + (dpopBound ? ",\"dpop_bound_access_tokens\":true" : "") + "}";
    }

    // ================================================================== checks

    static void run(Jose bankA, Jose bankD, long ttl) throws Exception {
        NumberVerificationBackend nv = backend("bank-a", bankA, null);
        NumberVerificationBackend nvDpop = backend("bank-a", bankA, Jose.generate(null));
        NumberVerificationBackend nvBound = backend("bank-d", bankD, null);
        NumberVerificationBackend nvBoundWithKey = backend("bank-d", bankD, Jose.generate(null));

        // ---------------------------------------------------------- 1.2 / 2.2 discovery
        heading("1.2 Authorization server metadata (OIDC discovery)");
        String meta = get(CAM + "/.well-known/openid-configuration").body();
        check("prompt=none advertised", meta.contains("\"prompt_values_supported\":[\"none\"]"), "");
        check("PKCE S256 only", meta.contains("\"code_challenge_methods_supported\":[\"S256\"]"), "");
        check("DPoP algs advertised", meta.contains("\"dpop_signing_alg_values_supported\":[\"ES256\",\"RS256\"]"), "");
        check("private_key_jwt offered", meta.contains("private_key_jwt"), "");
        check("no client_secret_* method", !meta.contains("client_secret"), "CAMARA prohibits shared secrets");
        check("CIBA poll mode", meta.contains("\"backchannel_token_delivery_modes_supported\":[\"poll\"]"), "");

        // ---------------------------------------------------------- 2.4 network-based, Android SDK
        heading("2.4 Network-based NV: Android SDK over the cell bearer 10.20.30.40:55555");
        AccessToken net = authCode(nv, MSISDN, true, NumberVerificationBackend.SCOPE_VERIFY,
                NumberVerificationBackend.SCOPE_DEVICE_PHONE_NUMBER);
        Map<String, String> claims = claims(net.value());
        check("amr = [mno]", "[\"mno\"]".equals(claims.get("amr")), claims.get("amr"));
        long life = Long.parseLong(claims.get("exp")) - Long.parseLong(claims.get("iat"));
        check("token lifetime <= 300 s", life <= 300 && life == ttl, life + " s");

        // ---------------------------------------------------------- 2.1 contract
        heading("2.1 API contract: /verify and /device-phone-number");
        HttpResponse<String> r = nvCall("POST", "/number-verification/v2/verify", net, "{\"phoneNumber\":\"" + MSISDN + "\"}", "c-1");
        check("correct number → 200 {devicePhoneNumberVerified:true}",
                r.statusCode() == 200 && r.body().replace(" ", "").equals("{\"devicePhoneNumberVerified\":true}"), r.body());
        check("x-correlator echoed", "c-1".equals(r.headers().firstValue("x-correlator").orElse(null)), "");
        check("token is single use", nvCall("POST", "/number-verification/v2/verify", net,
                "{\"phoneNumber\":\"" + MSISDN + "\"}", null).statusCode() != 200, "replay refused");

        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true), "{\"phoneNumber\":\"" + OTHER + "\"}", null);
        check("wrong number → 200 false", r.statusCode() == 200 && r.body().contains("\"devicePhoneNumberVerified\":false"), r.body());
        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true),
                "{\"hashedPhoneNumber\":\"" + NumberVerificationBackend.hashPhoneNumber(MSISDN) + "\"}", null);
        check("hashed correct number → true", r.statusCode() == 200 && r.body().contains("true"), r.body());
        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true),
                "{\"hashedPhoneNumber\":\"" + NumberVerificationBackend.hashPhoneNumber(OTHER) + "\"}", null);
        check("hashed wrong number → false", r.statusCode() == 200 && r.body().contains("false"), r.body());

        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true),
                "{\"phoneNumber\":\"" + MSISDN + "\",\"hashedPhoneNumber\":\"" + NumberVerificationBackend.hashPhoneNumber(MSISDN) + "\"}", null);
        check("both identifiers → 400 INVALID_ARGUMENT", r.statusCode() == 400 && camaraError(r.body(), 400), r.body());
        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true), "{\"phoneNumber\":\"0911111111\"}", null);
        check("non-E.164 number → 400", r.statusCode() == 400 && camaraError(r.body(), 400), r.body());
        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true),
                "{\"phoneNumber\":\"" + MSISDN + "\",\"extra\":1}", null);
        check("unknown property → 400", r.statusCode() == 400, r.body());
        r = nvCall("POST", "/number-verification/v2/verify", authCode(nv, MSISDN, true),
                "{\"phoneNumber\":\"" + MSISDN + "\"}", "bad correlator with spaces");
        check("invalid x-correlator → 400", r.statusCode() == 400, r.body());

        AccessToken dpn = authCode(nv, MSISDN, true, NumberVerificationBackend.SCOPE_DEVICE_PHONE_NUMBER);
        r = nvCall("GET", "/number-verification/v2/device-phone-number", dpn, null, null);
        check("device-phone-number → the token's number", r.statusCode() == 200
                && r.body().replace(" ", "").equals("{\"devicePhoneNumber\":\"" + MSISDN + "\"}"), r.body());

        // ---------------------------------------------------------- 2.2 token checks
        heading("2.2 Token validation: scope, context, integrity, expiry");
        AccessToken onlyVerify = authCode(nv, MSISDN, true);
        r = nvCall("GET", "/number-verification/v2/device-phone-number", onlyVerify, null, null);
        check("wrong scope → 403", r.statusCode() == 403, r.body());

        AccessToken simSwapOnly = nv.ciba("tel:" + MSISDN, "sim-swap:check");
        r = nvCall("POST", "/number-verification/v2/verify", simSwapOnly, "{\"phoneNumber\":\"" + MSISDN + "\"}", null);
        check("sim-swap token at /verify → 403", r.statusCode() == 403, r.body());

        AccessToken telVerify = nv.ciba("tel:" + MSISDN, NumberVerificationBackend.SCOPE_VERIFY);
        check("CIBA tel: token carries no amr", claims(telVerify.value()).get("amr") == null, "asserted, not authenticated");
        r = nvCall("POST", "/number-verification/v2/verify", telVerify, "{\"phoneNumber\":\"" + MSISDN + "\"}", null);
        check("asserted tel: → 403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK",
                r.statusCode() == 403 && r.body().contains("USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK"), r.body());
        AccessToken telVerify2 = nv.ciba("tel:" + MSISDN, NumberVerificationBackend.SCOPE_VERIFY);
        r = HTTP.send(HttpRequest.newBuilder(URI.create(CAM + "/camara/number-verification/v2/verify"))
                .header("Authorization", "Bearer " + telVerify2.value()).header("X-Sas-Amr", "mno")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"phoneNumber\":\"" + MSISDN + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        check("X-Sas-Amr: mno header is not evidence", r.statusCode() == 403, r.body());

        AccessToken twoLegged = clientCredentials("bank-a", bankA, "sim-swap:check");
        r = nvCall("POST", "/number-verification/v2/verify", twoLegged, "{\"phoneNumber\":\"" + MSISDN + "\"}", null);
        check("2-legged token at /verify → 401/403", r.statusCode() == 401 || r.statusCode() == 403, r.body());

        AccessToken good = authCode(nv, MSISDN, true);
        String[] p = good.value().split("\\.");
        String tampered = p[0] + "." + b64(claimsJson(good.value()).replace(MSISDN, OTHER)) + "." + p[2];
        r = raw("Bearer " + tampered, "{\"phoneNumber\":\"" + OTHER + "\"}");
        check("tampered payload → 401", r.statusCode() == 401, r.body());
        String none = b64("{\"alg\":\"none\",\"typ\":\"at+jwt\"}") + "." + p[1] + ".";
        r = raw("Bearer " + none, "{\"phoneNumber\":\"" + MSISDN + "\"}");
        check("alg=none → 401", r.statusCode() == 401, r.body());

        AccessToken expiring = authCode(nv, MSISDN, true);
        System.out.println("   … waiting " + (ttl + 2) + " s for the token to expire");
        Thread.sleep((ttl + 2) * 1000L);
        r = nvCall("POST", "/number-verification/v2/verify", expiring, "{\"phoneNumber\":\"" + MSISDN + "\"}", null);
        check("expired token → 401", r.statusCode() == 401, r.body());

        // ---------------------------------------------------------- 1.2 OAuth flow checks
        heading("1.2 Authorization flows: PKCE, state, client auth, prompt");
        var started = nv.start("u-1", null);
        AuthorizationResult code = device(started.authorizeUrl().replace(CAM, CAM_FROM_CELL), true);
        nv.complete(code.code(), started.state());
        try {
            nv.complete(code.code(), started.state());
            check("state replay refused before /token", false, "");
        } catch (NvApiException e) {
            check("state replay refused before /token", "invalid_state".equals(e.code()), e.code());
        }
        HttpResponse<String> replayCode = postForm(CAM + "/token", Map.of("grant_type", "authorization_code",
                "code", code.code(), "redirect_uri", "https://bank.example/cb", "code_verifier", "x".repeat(43),
                "client_id", "bank-a", "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion", bankA.clientAssertion("bank-a", CAM + "/token")), null);
        check("code replay → invalid_grant", replayCode.statusCode() == 400 && replayCode.body().contains("invalid_grant"), replayCode.body());

        var s2 = nv.start("u-2", null);
        AuthorizationResult c2 = device(s2.authorizeUrl().replace(CAM, CAM_FROM_CELL), true);
        HttpResponse<String> badPkce = postForm(CAM + "/token", Map.of("grant_type", "authorization_code",
                "code", c2.code(), "redirect_uri", "https://bank.example/cb", "code_verifier", "y".repeat(43),
                "client_id", "bank-a", "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion", bankA.clientAssertion("bank-a", CAM + "/token")), null);
        check("wrong PKCE verifier → invalid_grant", badPkce.body().contains("invalid_grant"), badPkce.body());

        HttpResponse<String> wrongKey = postForm(CAM + "/token", Map.of("grant_type", "client_credentials",
                "scope", PURPOSE + " sim-swap:check", "client_id", "bank-a",
                "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion", Jose.generate("evil").clientAssertion("bank-a", CAM + "/token")), null);
        check("assertion signed by another key → 401 invalid_client", wrongKey.statusCode() == 401
                && wrongKey.body().contains("invalid_client"), wrongKey.body());
        HttpResponse<String> noAuth = postForm(CAM + "/token", Map.of("grant_type", "client_credentials",
                "scope", PURPOSE + " sim-swap:check", "client_id", "bank-a"), null);
        check("no client authentication → 401", noAuth.statusCode() == 401, noAuth.body());

        String loginUrl = nv.start("u-3", null).authorizeUrl().replace("prompt=none", "prompt=login");
        HttpResponse<String> login = get(loginUrl);
        check("prompt=login → error=login_required", login.statusCode() == 302
                && login.headers().firstValue("Location").orElse("").contains("error=login_required"),
                login.headers().firstValue("Location").orElse(""));
        HttpResponse<String> acr = get(nv.start("u-4", null).authorizeUrl().replace(CAM, CAM_FROM_CELL) + "&acr_values=2");
        check("acr_values ignored (CAMARA)", acr.statusCode() == 302 || acr.statusCode() == 200, "status " + acr.statusCode());

        // ---------------------------------------------------------- DPoP
        heading("1.2 DPoP (RFC 9449, CAMARA profile)");
        AccessToken bound = authCode(nvDpop, MSISDN, true);
        check("proof at /token → token_type DPoP", bound.dpopBound(), bound.toString());
        check("token carries cnf.jkt", claimsJson(bound.value()).contains("\"cnf\""), "");
        check("NV with DPoP proof → true", nvDpop.verify(bound, MSISDN), "");
        AccessToken stolen = authCode(nvDpop, MSISDN, true);
        r = raw("Bearer " + stolen.value(), "{\"phoneNumber\":\"" + MSISDN + "\"}");
        check("bound token as Bearer → 401", r.statusCode() == 401 && r.headers().firstValue("WWW-Authenticate")
                .orElse("").contains("invalid_dpop_proof"), r.body());
        AccessToken replayed = authCode(nvDpop, MSISDN, true);
        String url = CAM + "/camara/number-verification/v2/verify";
        Jose thief = Jose.generate(null);
        r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "DPoP " + replayed.value())
                .header("DPoP", thief.dpopProof("POST", url, replayed.value())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"phoneNumber\":\"" + MSISDN + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        check("proof from another key → 401", r.statusCode() == 401, r.body());
        try {
            authCode(nvBound, MSISDN, true);
            check("DPoP-bound client without proof → 400 invalid_dpop_proof", false, "got a token");
        } catch (NvApiException e) {
            check("DPoP-bound client without proof → 400 invalid_dpop_proof", "invalid_dpop_proof".equals(e.code()), e.getMessage());
        }
        check("DPoP-bound client with proof → DPoP token", authCode(nvBoundWithKey, MSISDN, true).dpopBound(), "");

        // ---------------------------------------------------------- 2.4 SIM-based
        heading("2.4 SIM-based NV (GSMA TS.43 operator token)");
        String tk = operatorToken();
        AccessToken sim = authCodeWithHint(nv, "operatortoken:" + tk);
        check("auth code via operator token: amr = [hwk, mno]",
                "[\"hwk\",\"mno\"]".equals(claims(sim.value()).get("amr")), claims(sim.value()).get("amr"));
        check("SIM-based /verify → true", nv.verify(sim, MSISDN), "");
        AccessToken cibaSim = nv.ciba("operatortoken:" + operatorToken(), NumberVerificationBackend.SCOPE_VERIFY);
        check("CIBA operatortoken → /verify true", nv.verify(cibaSim, MSISDN), "");
        AccessToken bearerSim = nv.jwtBearer("operatortoken:" + operatorToken(), NumberVerificationBackend.SCOPE_VERIFY);
        check("JWT bearer operatortoken → /verify true", nv.verify(bearerSim, MSISDN), "");
        try {
            nv.ciba("operatortoken:" + tk, NumberVerificationBackend.SCOPE_VERIFY);
            check("spent operator token refused", false, "accepted twice");
        } catch (NvApiException e) {
            check("spent operator token refused", "access_denied".equals(e.code()), e.code());
        }
        heading("2.4 CIBA network-based login hint");
        AccessToken ipport = nv.ciba("ipport:10.20.30.40:55555", NumberVerificationBackend.SCOPE_VERIFY);
        check("CIBA ipport: → amr [mno] → /verify true", nv.verify(ipport, MSISDN), "");
        try {
            nv.ciba("ipport:10.20.30.40", NumberVerificationBackend.SCOPE_VERIFY);
            check("ipport without port refused (CGNAT rule)", false, "accepted");
        } catch (NvApiException e) {
            check("ipport without port refused (CGNAT rule)", "invalid_request".equals(e.code()), e.code());
        }
        heading("2.4 Fail-closed: a connection no PGW assigned");
        try {
            device(nv.start("u-5", null).authorizeUrl(), false);
            check("127.0.0.1 → access_denied", false, "got a code");
        } catch (et.restlink.uesdk.android.AuthorizationException e) {
            check("127.0.0.1 → access_denied", "access_denied".equals(e.error()), e.error());
        }
    }

    // ================================================================== helpers

    static NumberVerificationBackend backend(String clientId, Jose key, Jose dpop) {
        return new NumberVerificationBackend(new NumberVerificationBackend.Config(
                CAM + "/authorize", CAM + "/token", CAM + "/bc-authorize", CAM + "/camara",
                clientId, "https://bank.example/cb", PURPOSE, key, dpop, null));
    }

    /** Backend start → Android SDK on the cell bearer → backend complete. */
    static AccessToken authCode(NumberVerificationBackend nv, String msisdn, boolean fromCell, String... scopes)
            throws Exception {
        var started = nv.start("user", null, scopes);
        String url = fromCell ? started.authorizeUrl().replace(CAM, CAM_FROM_CELL) : started.authorizeUrl();
        AuthorizationResult r = device(url, fromCell);
        return nv.complete(r.code(), r.state()).token();
    }

    static AccessToken authCodeWithHint(NumberVerificationBackend nv, String hint) throws Exception {
        var started = nv.start("user", hint);
        AuthorizationResult r = device(started.authorizeUrl(), false);
        return nv.complete(r.code(), r.state()).token();
    }

    static AuthorizationResult device(String url, boolean fromCell) throws Exception {
        return NumberVerification.builder(null).allowInsecureHttpForLab(true)
                .bearerSource((need, t) -> CellularBearer.fromNetwork(fromCell ? new CellRadio() : new Loopback(),
                        AccessTech.LTE))
                .build().authorize(url);
    }

    static AccessToken clientCredentials(String clientId, Jose key, String scope) throws Exception {
        HttpResponse<String> r = postForm(CAM + "/token", Map.of("grant_type", "client_credentials",
                "scope", PURPOSE + " " + scope, "client_id", clientId,
                "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                "client_assertion", key.clientAssertion(clientId, CAM + "/token")), null);
        return new AccessToken(field(r.body(), "access_token"), false, 0, scope, null);
    }

    static String operatorToken() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(ECS + "/entitlement/issue"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"msisdn\":\"" + MSISDN
                        + "\",\"imsi\":\"636010000000001\",\"eapMethod\":\"EAP-AKA\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        return field(r.body(), "token");
    }

    static HttpResponse<String> nvCall(String method, String path, AccessToken t, String body, String correlator)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(CAM + "/camara" + path))
                .header("Authorization", "Bearer " + t.value());
        if (correlator != null) {
            b.header("x-correlator", correlator);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> raw(String authorization, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(CAM + "/camara/number-verification/v2/verify"))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> postForm(String url, Map<String, String> form, String dpop) throws Exception {
        StringBuilder b = new StringBuilder();
        for (var e : new LinkedHashMap<>(form).entrySet()) {
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString()));
        if (dpop != null) {
            r.header("DPoP", dpop);
        }
        return HTTP.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    static boolean camaraError(String body, int status) {
        return body.contains("\"status\":" + status) && body.contains("\"code\":\"") && body.contains("\"message\":\"");
    }

    static String claimsJson(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    static Map<String, String> claims(String jwt) {
        String json = claimsJson(jwt);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*\\]|\\{[^}]*\\}|[0-9]+)").matcher(json);
        while (m.find()) {
            String v = m.group(2);
            out.put(m.group(1), v.startsWith("\"") ? v.substring(1, v.length() - 1) : v);
        }
        return out;
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static void heading(String h) {
        section = h;
        System.out.println("== " + h);
    }

    static void check(String label, boolean ok, String detail) {
        results.add(label);
        String d = detail == null ? "" : detail.length() > 160 ? detail.substring(0, 160) + "…" : detail;
        System.out.println((ok ? "  OK   " : "  FAIL ") + label + (d.isEmpty() ? "" : " — " + d));
        if (!ok) {
            failures++;
        }
    }

    // ================================================================== radios

    /** The "cellular bearer": every connection leaves from 10.20.30.40:55555. */
    static final class CellRadio extends android.net.Network {
        @Override
        public URLConnection openConnection(URL url) {
            return new BoundHttpConnection(url, new InetSocketAddress("10.20.30.40", 55555));
        }
    }

    /** A source address no PGW assigned. */
    static final class Loopback extends android.net.Network {
        @Override
        public URLConnection openConnection(URL url) throws IOException {
            return url.openConnection();
        }
    }

    /** One HTTP/1.1 GET over a socket bound to a chosen local address. */
    static final class BoundHttpConnection extends HttpURLConnection {
        private final InetSocketAddress local;
        private int status = -1;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];

        BoundHttpConnection(URL url, InetSocketAddress local) {
            super(url);
            this.local = local;
        }

        @Override
        public void connect() throws IOException {
            if (status >= 0) {
                return;
            }
            try (Socket s = new Socket()) {
                s.setReuseAddress(true);
                s.bind(local);
                int port = url.getPort() < 0 ? 80 : url.getPort();
                s.connect(new InetSocketAddress(url.getHost(), port), 5000);
                s.setSoTimeout(5000);
                OutputStream out = s.getOutputStream();
                out.write(("GET " + url.getFile() + " HTTP/1.1\r\nHost: " + url.getHost() + ":" + port
                        + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String text = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                int end = text.indexOf("\r\n\r\n");
                String[] lines = text.substring(0, end).split("\r\n");
                status = Integer.parseInt(lines[0].split(" ")[1]);
                for (int i = 1; i < lines.length; i++) {
                    int c = lines[i].indexOf(':');
                    headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
                }
                body = text.substring(end + 4).getBytes(StandardCharsets.ISO_8859_1);
            }
        }

        @Override
        public int getResponseCode() throws IOException {
            connect();
            return status;
        }

        @Override
        public String getHeaderField(String name) {
            return name == null ? null : headers.get(name.toLowerCase());
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            return new ByteArrayInputStream(body);
        }

        @Override
        public InputStream getErrorStream() {
            return status >= 400 ? new ByteArrayInputStream(body) : null;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }
}
