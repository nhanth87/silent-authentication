/*
 * Android emulator demo — Restlink (Ethiopia).
 * The bank backend half of CAMARA NV v2, on bank-backend-sdk. LAB ONLY.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import et.restlink.nvbackend.AccessToken;
import et.restlink.nvbackend.NumberVerificationBackend;
import et.restlink.nvbackend.NvApiException;
import et.restlink.nvbackend.PendingAuthorizations;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Endpoints for the demo app, and one for the test harness:
 *
 * <pre>
 * POST /nv/start                                    -> {"authorizeUrl":"…"}
 * POST /nv/complete {"code","state","phoneNumber"}  -> {"verified":true|false[,"error":"…"],"operation":"…"}
 * POST /nv/mode {"mode":"phone|hashed|device|simswap|simswap-date"[,"maxAge":h]}
 *                                                   -> which CAMARA operation the next logins use
 * </pre>
 *
 * Each login redeems its own access token and makes ONE CAMARA Number Verification v2
 * call with it (sas-camara pairs a token with a single x-correlator, so a token is not
 * reused): {@code POST /verify} with {@code phoneNumber} (mode phone, the default) or
 * {@code hashedPhoneNumber} (mode hashed), or {@code GET /device-phone-number} (mode
 * device, where the bank compares the answer with the number the user typed). Modes simswap and
 * simswap-date use CAMARA SIM Swap v2 instead: {@code POST /check {maxAge}} (a recent swap
 * blocks the login: error sim_swapped_recently) and {@code POST /retrieve-date}.
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
    /** Listen address; the screencast also binds a namespace-only address for its own probes. */
    static final String BIND = System.getProperty("demo.bind", "127.0.0.1");

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

        AtomicReference<String> mode = new AtomicReference<>("phone");
        AtomicReference<Integer> maxAge = new AtomicReference<>(240);
        HttpServer server = HttpServer.create(new InetSocketAddress(BIND, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/nv/start", ex -> {
            if (!post(ex)) {
                return;
            }
            String[] scopes = switch (mode.get()) {
                case "simswap" -> new String[] {"sim-swap:check"};
                case "simswap-date" -> new String[] {"sim-swap:retrieve-date"};
                default -> new String[] {NumberVerificationBackend.SCOPE_VERIFY,
                        NumberVerificationBackend.SCOPE_DEVICE_PHONE_NUMBER};
            };
            NumberVerificationBackend.Started s = nv.start(UUID.randomUUID().toString(), null, scopes);
            System.out.println("[demo-bank] start: authorize request issued");
            reply(ex, 200, "{\"authorizeUrl\":\"" + s.authorizeUrl() + "\"}");
        });
        server.createContext("/nv/mode", ex -> {
            if (!post(ex)) {
                return;
            }
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String m = field(req, "mode");
            if (m == null || !m.matches("phone|hashed|device|simswap|simswap-date")) {
                reply(ex, 400, "{\"error\":\"mode must be phone, hashed, device, simswap or simswap-date\"}");
                return;
            }
            mode.set(m);
            Matcher age = Pattern.compile("\"maxAge\"\\s*:\\s*(\\d+)").matcher(req);
            if (age.find()) {
                maxAge.set(Integer.parseInt(age.group(1)));
            }
            System.out.println("[demo-bank] mode: " + m + (m.equals("simswap") ? " maxAge=" + maxAge.get() : ""));
            reply(ex, 200, "{\"mode\":\"" + m + "\"}");
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
                AccessToken token = nv.complete(code, state).token();
                String op = mode.get();
                if (op.startsWith("simswap")) {
                    simSwap(ex, http, token, op, maxAge.get());
                    return;
                }
                boolean verified = switch (op) {
                    case "hashed" -> nv.verifyHashed(token, phone);
                    case "device" -> phone.equals(nv.devicePhoneNumber(token));
                    default -> nv.verify(token, phone);
                };
                System.out.println("[demo-bank] complete: " + op + " -> verified=" + verified);
                reply(ex, 200, "{\"verified\":" + verified + ",\"operation\":\"" + op + "\"}");
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

    /** CAMARA SIM Swap v2 with the login's token; bank-backend-sdk has no SIM Swap call. */
    static void simSwap(HttpExchange ex, HttpClient http, AccessToken token, String op, int maxAge)
            throws IOException, InterruptedException {
        boolean check = op.equals("simswap");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(CAMARA + "/camara/sim-swap/v2/"
                        + (check ? "check" : "retrieve-date")))
                .header("Authorization", "Bearer " + token.value())
                .header("x-correlator", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(check ? "{\"maxAge\":" + maxAge + "}" : "{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        String body = r.body();
        System.out.println("[demo-bank] complete: " + op + " -> " + r.statusCode() + " " + body);
        if (r.statusCode() != 200) {
            reply(ex, 200, "{\"verified\":false,\"error\":\"sim_swap_" + r.statusCode() + "\"}");
        } else if (check && body.matches("(?s).*\"swapped\"\\s*:\\s*true.*")) {
            // A SIM changed within maxAge hours: the bank does not trust the line, OTP instead.
            reply(ex, 200, "{\"verified\":false,\"error\":\"sim_swapped_recently\",\"operation\":\"" + op + "\"}");
        } else {
            reply(ex, 200, "{\"verified\":true,\"operation\":\"" + op + "\"}");
        }
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
