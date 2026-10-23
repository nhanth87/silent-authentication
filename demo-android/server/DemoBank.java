/*
 * Android emulator demo — Restlink (Ethiopia).
 * The bank backend half of CAMARA NV v2, on bank-backend-sdk. LAB ONLY.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import et.restlink.nvbackend.NumberVerificationBackend;
import et.restlink.nvbackend.NvApiException;
import et.restlink.nvbackend.PendingAuthorizations;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two endpoints for the demo app:
 *
 * <pre>
 * POST /nv/start                                    -> {"authorizeUrl":"…"}
 * POST /nv/complete {"code","state","phoneNumber"}  -> {"verified":true|false[,"error":"…"]}
 * </pre>
 *
 * The PKCE verifier and the state stay here; the app only carries the URL and
 * hands back the code. The authorize endpoint is the one the DEVICE reaches
 * (the emulator sees the host as 10.0.2.2, through edge-sim); /token and the
 * CAMARA API are called from the host.
 *
 * <pre>java -cp bank-backend-sdk.jar DemoBank.java [listenPort=8092]
 *     -Ddemo.authorize=http://10.0.2.2:8084/authorize -Ddemo.camara=http://127.0.0.1:8085</pre>
 */
public class DemoBank {

    static final String AUTHORIZE = System.getProperty("demo.authorize", "http://10.0.2.2:8084/authorize");
    static final String CAMARA = System.getProperty("demo.camara", "http://127.0.0.1:8085");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8092;
        // Public client bank-lab (token_endpoint_auth_method=none) and Bearer tokens: lab registration.
        // HTTP/1.1: no h2c upgrade (101) on the wire, so the pcap reads as plain HTTP.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        NumberVerificationBackend nv = new NumberVerificationBackend(new NumberVerificationBackend.Config(
                AUTHORIZE, CAMARA + "/token", CAMARA + "/bc-authorize", CAMARA + "/camara",
                "bank-lab", "https://bank.example/cb", null, null, null, Duration.ofSeconds(5)),
                new PendingAuthorizations(), http);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/nv/start", ex -> {
            if (!post(ex)) {
                return;
            }
            NumberVerificationBackend.Started s = nv.start(UUID.randomUUID().toString(), null);
            System.out.println("[demo-bank] start: authorize request issued");
            reply(ex, 200, "{\"authorizeUrl\":\"" + s.authorizeUrl() + "\"}");
        });
        server.createContext("/nv/complete", ex -> {
            if (!post(ex)) {
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String code = field(body, "code");
            String state = field(body, "state");
            String phone = field(body, "phoneNumber");
            if (code == null || state == null || phone == null) {
                reply(ex, 400, "{\"verified\":false,\"error\":\"invalid_request\"}");
                return;
            }
            try {
                boolean verified = nv.verify(nv.complete(code, state).token(), phone);
                System.out.println("[demo-bank] complete: devicePhoneNumberVerified=" + verified);
                reply(ex, 200, "{\"verified\":" + verified + "}");
            } catch (NvApiException e) {
                System.out.println("[demo-bank] complete: refused " + e.status() + " " + e.code());
                reply(ex, 200, "{\"verified\":false,\"error\":\"" + e.code() + "\"}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reply(ex, 503, "{\"verified\":false,\"error\":\"interrupted\"}");
            } catch (IOException e) {
                System.out.println("[demo-bank] complete: operator unreachable " + e.getClass().getSimpleName());
                reply(ex, 502, "{\"verified\":false,\"error\":\"operator_unreachable\"}");
            }
        });
        server.start();
        System.out.println("[demo-bank] :" + port + "  authorize(device)=" + AUTHORIZE + "  camara(host)=" + CAMARA);
    }

    static boolean post(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            return true;
        }
        reply(ex, 405, "{\"error\":\"method_not_allowed\"}");
        return false;
    }

    static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** Flat string field of a small JSON object; the app only ever sends three. */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
