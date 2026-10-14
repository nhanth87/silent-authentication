/*
 * Silent Auth — Restlink (Ethiopia).
 * E2E: the Android SDK's CAMARA NV v2 flow against the packaged sas-camara +
 * sas-ecs. Runs on the JVM (single-file source launch) inside the private
 * network namespace set up by scripts/sdk-nv2-e2e.sh.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import et.restlink.uesdk.android.AccessTech;
import et.restlink.uesdk.android.AuthorizationException;
import et.restlink.uesdk.android.AuthorizationResult;
import et.restlink.uesdk.android.CellularBearer;
import et.restlink.uesdk.android.CellularRequirement;
import et.restlink.uesdk.android.CellularUnavailableException;
import et.restlink.uesdk.android.NumberVerification;
import et.restlink.uesdk.android.OperatorTokenSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays both halves the SDK does not: the bank backend (PKCE, the authorize URL,
 * /token, /verify) and the radio (a socket whose source address is the one the
 * lab resolver binds to +251911111111). The SDK in between is the shipped code.
 */
public class Nv2AndroidE2E {

    static final String CAM = "http://127.0.0.1:8085";
    static final String CAM_AS_SEEN_FROM_CELL = "http://10.20.30.40:8085";
    static final String ECS = "http://127.0.0.1:8095";
    static final String MSISDN = "+251911111111";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static int failures;
    /** SIM-based (operator-token) codes carry amr [hwk, mno] since 2026-10-14: strictly true. */
    static void verdictOrGap(String label, String v) {
        check(label, v.startsWith("200 ") && v.contains("\"devicePhoneNumberVerified\":true"), v);
    }

    /** The "cellular bearer": every connection leaves from 10.20.30.40:55555. */
    static final class CellRadio extends android.net.Network {
        @Override
        public URLConnection openConnection(URL url) {
            return new BoundHttpConnection(url, new InetSocketAddress("10.20.30.40", 55555));
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
                s.connect(new InetSocketAddress(url.getHost(), port), getConnectTimeout());
                s.setSoTimeout(getReadTimeout());
                String target = url.getFile().isEmpty() ? "/" : url.getFile();
                OutputStream out = s.getOutputStream();
                out.write(("GET " + target + " HTTP/1.1\r\nHost: " + url.getHost() + ":" + port
                        + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                byte[] all = s.getInputStream().readAllBytes();
                String text = new String(all, StandardCharsets.ISO_8859_1);
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

    // ------------------------------------------------------------ bank backend

    record Pkce(String verifier, String challenge) {
        static Pkce fresh() throws Exception {
            byte[] r = new byte[32];
            new SecureRandom().nextBytes(r);
            String v = Base64.getUrlEncoder().withoutPadding().encodeToString(r);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(v, Base64.getUrlEncoder().withoutPadding().encodeToString(d));
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String authorizeUrl(String base, Pkce pkce, String state) {
        return base + "/authorize?response_type=code&client_id=bank-lab"
                + "&redirect_uri=" + enc("https://bank.example/cb")
                + "&scope=" + enc("openid dpv:FraudPreventionAndDetection number-verification:verify")
                + "&state=" + state + "&nonce=n-" + state
                + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256&prompt=none";
    }

    static String post(String url, String contentType, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer).header("x-correlator", "sdk-e2e-" + System.nanoTime());
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Backend half: redeem the code, call /verify and /device-phone-number. */
    static String backendVerdict(AuthorizationResult r, Pkce pkce) throws Exception {
        String token = post(CAM + "/token", "application/x-www-form-urlencoded",
                "grant_type=authorization_code&code=" + enc(r.code())
                        + "&redirect_uri=" + enc("https://bank.example/cb")
                        + "&code_verifier=" + pkce.verifier() + "&client_id=bank-lab", null);
        String at = field(token, "access_token");
        if (at == null) {
            return "token exchange failed: " + token;
        }
        return post(CAM + "/camara/number-verification/v2/verify", "application/json",
                "{\"phoneNumber\":\"" + MSISDN + "\"}", at);
    }

    static final OperatorTokenSource LAB_ENTITLEMENT = () -> {
        try {
            String r = post(ECS + "/entitlement/issue", "application/json",
                    "{\"msisdn\":\"" + MSISDN + "\",\"imsi\":\"636010000000001\",\"eapMethod\":\"EAP-AKA\"}", null);
            return field(r, "token");
        } catch (Exception e) {
            throw new IOException("lab entitlement server unreachable");
        }
    };

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + label + " — " + detail);
        if (!ok) {
            failures++;
        }
    }

    static NumberVerification.Builder sdk() {
        return NumberVerification.builder(null).allowInsecureHttpForLab(true);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== A. network-based: request leaves on the cell bearer 10.20.30.40:55555");
        Pkce pa = Pkce.fresh();
        AuthorizationResult a = sdk()
                .bearerSource((need, t) -> CellularBearer.fromNetwork(new CellRadio(), AccessTech.LTE))
                .build().authorize(authorizeUrl(CAM_AS_SEEN_FROM_CELL, pa, "state-a"));
        check("authorize", a.method() == AuthorizationResult.Method.NETWORK, a.toString());
        String va = backendVerdict(a, pa);
        check("backend /verify", va.contains("\"devicePhoneNumberVerified\":true"), va);

        System.out.println("== B. network says no (127.0.0.1 is no subscriber) -> SIM / TS.43 token");
        Pkce pb = Pkce.fresh();
        AuthorizationResult b = sdk()
                .bearerSource((need, t) -> CellularBearer.fromNetwork(new android.net.Network() {
                    @Override
                    public URLConnection openConnection(URL url) throws IOException {
                        return url.openConnection();
                    }
                }, AccessTech.LTE))
                .operatorTokens(LAB_ENTITLEMENT)
                .build().authorize(authorizeUrl(CAM, pb, "state-b"));
        check("authorize", b.method() == AuthorizationResult.Method.OPERATOR_TOKEN, b.toString());
        verdictOrGap("backend /verify", backendVerdict(b, pb));

        System.out.println("== C. Wi-Fi only, no token source -> fail closed, nothing sent");
        try {
            sdk().bearerSource((need, t) -> {
                throw new CellularUnavailableExceptionProbe().make(need);
            }).build().authorize(authorizeUrl(CAM, Pkce.fresh(), "state-c"));
            check("fail closed", false, "returned a code");
        } catch (CellularUnavailableException e) {
            check("fail closed", true, e.getClass().getSimpleName());
        }

        System.out.println("== D. a spent operator token is refused by the server");
        String spent = LAB_ENTITLEMENT.fetch();
        OperatorTokenSource same = () -> spent;
        sdk().bearerSource((need, t) -> {
            throw new CellularUnavailableExceptionProbe().make(need);
        }).operatorTokens(same).build().authorize(authorizeUrl(CAM, Pkce.fresh(), "state-d1"));
        try {
            sdk().bearerSource((need, t) -> {
                throw new CellularUnavailableExceptionProbe().make(need);
            }).operatorTokens(same).build().authorize(authorizeUrl(CAM, Pkce.fresh(), "state-d2"));
            check("replay", false, "second use returned a code");
        } catch (AuthorizationException e) {
            check("replay", "access_denied".equals(e.error()), e.getMessage());
        }

        System.out.println("== E. a request without prompt=none never leaves the device");
        try {
            sdk().build().authorize(authorizeUrl(CAM, Pkce.fresh(), "state-e").replace("&prompt=none", ""));
            check("prompt=none", false, "accepted");
        } catch (IllegalArgumentException e) {
            check("prompt=none", true, e.getMessage());
        }

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Builds the SDK's exception the way the platform would: no cellular network granted. */
    static final class CellularUnavailableExceptionProbe {
        CellularUnavailableException make(CellularRequirement need) {
            try {
                CellularBearer.fromNetwork(null, AccessTech.WIFI);
            } catch (CellularUnavailableException e) {
                return e;
            }
            throw new IllegalStateException();
        }
    }
}
