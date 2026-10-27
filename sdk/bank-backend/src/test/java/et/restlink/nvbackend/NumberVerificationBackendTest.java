/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Tests: authorize URL, single-use state, private_key_jwt, DPoP, NV calls.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberVerificationBackendTest {

    private HttpServer server;
    private String base;
    private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    private String tokenType = "Bearer";
    private String verifyAnswer = "{\"devicePhoneNumberVerified\":true}";
    private int verifyStatus = 200;
    private String simSwapAnswer = "{\"swapped\":false}";
    private int simSwapStatus = 200;
    private String dateAnswer = "{\"latestSimChange\":\"2026-10-20T08:15:00Z\",\"monitoredPeriod\":120}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            record(ex);
            reply(ex, 200, "{\"access_token\":\"at-1\",\"token_type\":\"" + tokenType
                    + "\",\"expires_in\":300,\"scope\":\"number-verification:verify\"}");
        });
        server.createContext("/bc-authorize", ex -> {
            record(ex);
            reply(ex, 200, "{\"auth_req_id\":\"r1\",\"expires_in\":120,\"interval\":1}");
        });
        server.createContext("/camara/number-verification/v2/verify", ex -> {
            record(ex);
            reply(ex, verifyStatus, verifyAnswer);
        });
        server.createContext("/camara/sim-swap/v2/check", ex -> {
            record(ex);
            reply(ex, simSwapStatus, simSwapAnswer);
        });
        server.createContext("/camara/sim-swap/v2/retrieve-date", ex -> {
            record(ex);
            reply(ex, 200, dateAnswer);
        });
        server.createContext("/camara/number-verification/v2/device-phone-number", ex -> {
            record(ex);
            reply(ex, 200, "{\"devicePhoneNumber\":\"+251911111111\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void record(HttpExchange ex) throws IOException {
        Map<String, String> h = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        h.put(":method", ex.getRequestMethod());
        headers.add(h);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> f = new HashMap<>();
        if (h.getOrDefault("content-type", "").startsWith("application/x-www-form-urlencoded")) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                f.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        } else {
            f.put(":body", body);
        }
        forms.add(f);
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private NumberVerificationBackend backend(Jose clientKey, Jose dpopKey, PendingAuthorizations pending) {
        return new NumberVerificationBackend(new NumberVerificationBackend.Config(
                "https://sas.example.et/authorize", base + "/token", base + "/bc-authorize",
                base + "/camara", "bank-a", "https://bank.example/cb", null, clientKey, dpopKey, null),
                pending, null);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            q.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static Map<String, String> claims(String jwt) {
        return FlatJson.parse(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private static Map<String, String> header(String jwt) {
        return FlatJson.parse(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ authorize

    @Test
    void authorizeUrlIsSilentPkceAndUnique() {
        NumberVerificationBackend nv = backend(null, null, new PendingAuthorizations());
        var a = nv.start("session-1", null);
        var b = nv.start("session-2", "operatortoken:tk");
        Map<String, String> q = query(a.authorizeUrl());
        assertEquals("code", q.get("response_type"));
        assertEquals("none", q.get("prompt"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(43, q.get("code_challenge").length());
        assertEquals("openid dpv:FraudPreventionAndDetection number-verification:verify", q.get("scope"));
        assertEquals(a.state(), q.get("state"));
        assertNotNull(q.get("nonce"));
        assertNull(q.get("login_hint"));
        assertEquals("operatortoken:tk", query(b.authorizeUrl()).get("login_hint"));
        assertFalse(a.state().equals(b.state()));
        assertFalse(a.authorizeUrl().contains("code_verifier"), "the verifier never leaves the backend");
    }

    @Test
    void codeIsRedeemedWithVerifierAndPrivateKeyJwtOnce() throws Exception {
        Jose client = Jose.generate("k1");
        NumberVerificationBackend nv = backend(client, null, new PendingAuthorizations());
        var started = nv.start("session-1", null);

        NumberVerificationBackend.Completed done = nv.complete("code-1", started.state());

        assertEquals("session-1", done.userSession());
        assertEquals("at-1", done.token().value());
        assertFalse(done.token().dpopBound());
        Map<String, String> form = forms.get(0);
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("code-1", form.get("code"));
        assertEquals(Jose.s256(form.get("code_verifier")), query(started.authorizeUrl()).get("code_challenge"));
        assertEquals(NumberVerificationBackend.CLIENT_ASSERTION_TYPE, form.get("client_assertion_type"));
        Map<String, String> assertion = claims(form.get("client_assertion"));
        assertEquals("bank-a", assertion.get("iss"));
        assertEquals("bank-a", assertion.get("sub"));
        assertEquals(base + "/token", assertion.get("aud"));
        assertTrue(Long.parseLong(assertion.get("exp")) - Long.parseLong(assertion.get("iat")) <= 60);
        assertTrue(verifies(client, form.get("client_assertion")), "ES256 signature with the registered key");

        NvApiException replay = assertThrows(NvApiException.class, () -> nv.complete("code-1", started.state()));
        assertEquals("invalid_state", replay.code());
        assertEquals(1, forms.size(), "a replayed state never reaches /token");
        assertThrows(NvApiException.class, () -> nv.complete("code-x", "forged-state"));
    }

    @Test
    void expiredStateIsRefused() {
        Clock t0 = Clock.fixed(Instant.ofEpochSecond(1_000_000), ZoneOffset.UTC);
        PendingAuthorizations p = new PendingAuthorizations(300, 10, t0);
        p.put(new PendingAuthorizations.Pending("s", "v", "n", "u", 1_000_000 - 301));
        assertTrue(p.take("s").isEmpty());
    }

    @Test
    void dpopProofAccompaniesTokenAndApiCalls() throws Exception {
        tokenType = "DPoP";
        Jose dpop = Jose.generate(null);
        NumberVerificationBackend nv = backend(Jose.generate("k1"), dpop, new PendingAuthorizations());
        var t = nv.complete("c", nv.start("s", null).state()).token();
        assertTrue(t.dpopBound());

        Map<String, String> tokenProof = header(headers.get(0).get("dpop"));
        assertEquals("dpop+jwt", tokenProof.get("typ"));
        Map<String, String> tc = claims(headers.get(0).get("dpop"));
        assertEquals("POST", tc.get("htm"));
        assertEquals(base + "/token", tc.get("htu"));
        assertNull(tc.get("ath"));

        assertTrue(nv.verify(t, "+251911111111"));
        assertEquals("DPoP at-1", headers.get(1).get("authorization"));
        Map<String, String> vc = claims(headers.get(1).get("dpop"));
        assertEquals(base + "/camara/number-verification/v2/verify", vc.get("htu"));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(Jose.sha256("at-1".getBytes())),
                vc.get("ath"));
        assertNotNull(headers.get(1).get("x-correlator"));
    }

    @Test
    void verifyIsTrueOnlyForAnExplicitTrue() throws Exception {
        NumberVerificationBackend nv = backend(null, null, new PendingAuthorizations());
        AccessToken t = new AccessToken("at", false, 300, null, null);
        assertTrue(nv.verify(t, "+251911111111"));
        assertEquals("{\"phoneNumber\":\"+251911111111\"}", forms.get(0).get(":body"));
        verifyAnswer = "{\"devicePhoneNumberVerified\":false}";
        assertFalse(nv.verifyHashed(t, "+251911111111"));
        assertEquals("{\"hashedPhoneNumber\":\"" + NumberVerificationBackend.hashPhoneNumber("+251911111111") + "\"}",
                forms.get(1).get(":body"));
        verifyAnswer = "{\"devicePhoneNumberVerified\":\"yes\"}";
        assertThrows(NvApiException.class, () -> nv.verify(t, "+251911111111"));
        verifyStatus = 403;
        verifyAnswer = "{\"status\":403,\"code\":\"NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK\",\"message\":\"x\"}";
        NvApiException e = assertThrows(NvApiException.class, () -> nv.verify(t, "+251911111111"));
        assertEquals("NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK", e.code());
        assertEquals(403, e.status());
        assertEquals("+251911111111", nv.devicePhoneNumber(t));
        assertEquals("GET", headers.get(4).get(":method"));
    }

    @Test
    void simSwapCheckIsTrueOnlyForAnExplicitTrue() throws Exception {
        NumberVerificationBackend nv = backend(null, null, new PendingAuthorizations());
        AccessToken t = new AccessToken("at", false, 300, null, null);
        assertFalse(nv.simSwapCheck(t, 24));
        assertEquals("{\"maxAge\":24}", forms.get(0).get(":body"), "3-legged: the token names the line");
        assertEquals("Bearer at", headers.get(0).get("authorization"));
        assertNotNull(headers.get(0).get("x-correlator"));
        simSwapAnswer = "{\"swapped\":true}";
        assertTrue(nv.simSwapCheck(t, "+251911111111", 240));
        assertEquals("{\"phoneNumber\":\"+251911111111\",\"maxAge\":240}", forms.get(1).get(":body"));
        simSwapAnswer = "{}";
        assertThrows(NvApiException.class, () -> nv.simSwapCheck(t, 24), "no answer is not 'not swapped'");
        simSwapStatus = 404;
        simSwapAnswer = "{\"status\":404,\"code\":\"IDENTIFIER_NOT_FOUND\",\"message\":\"x\"}";
        NvApiException e = assertThrows(NvApiException.class, () -> nv.simSwapCheck(t, 24));
        assertEquals("IDENTIFIER_NOT_FOUND", e.code());
        assertEquals(404, e.status());
        assertThrows(IllegalArgumentException.class, () -> nv.simSwapCheck(t, 0));
        assertThrows(IllegalArgumentException.class, () -> nv.simSwapCheck(t, 2401));
        assertEquals(4, forms.size(), "an out-of-range maxAge never reaches the provider");
    }

    @Test
    void simSwapRetrieveDateParsesRfc3339() throws Exception {
        NumberVerificationBackend nv = backend(null, null, new PendingAuthorizations());
        AccessToken t = new AccessToken("at", false, 300, null, null);
        var d = nv.simSwapRetrieveDate(t, null);
        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), d.latestSimChange());
        assertEquals(120, d.monitoredPeriod());
        assertEquals("{}", forms.get(0).get(":body"));
        dateAnswer = "{\"latestSimChange\":\"2026-10-20T11:15:00+03:00\"}";
        d = nv.simSwapRetrieveDate(t, null);
        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), d.latestSimChange());
        assertNull(d.monitoredPeriod());
        dateAnswer = "{\"latestSimChange\":null,\"monitoredPeriod\":120}";
        assertNull(nv.simSwapRetrieveDate(t, null).latestSimChange());
        dateAnswer = "{\"latestSimChange\":\"yesterday\"}";
        assertThrows(NvApiException.class, () -> nv.simSwapRetrieveDate(t, null));
    }

    @Test
    void hashedNumberIsLowercaseHexSha256OfE164() {
        // echo -n '+251911111111' | sha256sum
        assertEquals(64, NumberVerificationBackend.hashPhoneNumber("+251911111111").length());
        assertEquals(NumberVerificationBackend.hashPhoneNumber("+251911111111"),
                NumberVerificationBackend.hashPhoneNumber("+251911111111").toLowerCase());
    }

    @Test
    void cibaAndJwtBearerCarryTheRightAssertions() throws Exception {
        Jose client = Jose.generate("k1");
        NumberVerificationBackend nv = backend(client, null, new PendingAuthorizations());
        assertEquals("at-1", nv.ciba("operatortoken:tk", "sim-swap:check").value());
        assertEquals("operatortoken:tk", forms.get(0).get("login_hint"));
        assertEquals(base + "/bc-authorize", claims(forms.get(0).get("client_assertion")).get("aud"));
        assertEquals(NumberVerificationBackend.CIBA_GRANT, forms.get(1).get("grant_type"));
        assertEquals("r1", forms.get(1).get("auth_req_id"));

        nv.jwtBearer("operatortoken:tk2", "number-verification:verify");
        Map<String, String> a = claims(forms.get(2).get("assertion"));
        assertEquals("operatortoken:tk2", a.get("sub"));
        assertEquals("dpv:FraudPreventionAndDetection number-verification:verify", a.get("scope"));
        assertNull(forms.get(2).get("client_assertion"), "the grant assertion is the client auth");
    }

    @Test
    void privateJwkRoundTripsAndRedactsInToString() {
        Jose k = Jose.generate("vault-1");
        Jose back = Jose.fromPrivateJwk(k.privateJwk());
        assertEquals(k.thumbprint(), back.thumbprint());
        assertTrue(k.publicJwks().contains("\"kid\":\"vault-1\""));
        assertFalse(k.publicJwks().contains("\"d\""));
        assertFalse(new AccessToken("secret-token", false, 1, null, null).toString().contains("secret-token"));
    }

    private static boolean verifies(Jose key, String jwt) throws Exception {
        Map<String, String> jwk = FlatJson.parse(key.publicJwk());
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var params = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        var pub = KeyFactory.getInstance("EC").generatePublic(new java.security.spec.ECPublicKeySpec(
                new java.security.spec.ECPoint(
                        new java.math.BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("x"))),
                        new java.math.BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("y")))), params));
        String[] p = jwt.split("\\.");
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(pub);
        s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        return s.verify(Base64.getUrlDecoder().decode(p[2]));
    }
}
