/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: CAMARA NV v2 authorization over a bearer — redirect capture, state
 * check, the TS.43 fallback, retries and every refusal shape.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import android.net.Network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberVerificationTest {

    private static final String STATE = "st-4f1c9a7e";
    private static final String TOKEN = "eyJ.op.token";

    private HttpServer server;
    private String base;
    private final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<Map<String, String>>());

    /** Counts what goes through the "cellular" Network handle. */
    static final class FakeCellular extends Network {
        final AtomicInteger opened = new AtomicInteger();
        int failFirst;

        @Override
        public URLConnection openConnection(URL url) throws IOException {
            if (opened.incrementAndGet() <= failFirst) {
                throw new IOException("radio blip");
            }
            return url.openConnection();
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void route(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private Map<String, String> params(HttpExchange ex) {
        Map<String, String> p = AuthorizeUrl.query(ex.getRequestURI().getRawQuery());
        seen.add(p);
        return p;
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static void body(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private String authorizeUrl(String extra) {
        return base + "/authorize?response_type=code&client_id=bank-lab"
                + "&redirect_uri=" + AuthorizeUrl.encode("https://bank.example/cb")
                + "&scope=" + AuthorizeUrl.encode("openid number-verification:verify")
                + "&state=" + STATE + "&nonce=n-1&code_challenge=abcDEF123_-&code_challenge_method=S256"
                + "&prompt=none" + extra;
    }

    private NumberVerification.Builder sdk(final FakeCellular cellular) {
        return NumberVerification.builder(null)
                .allowInsecureHttpForLab(true)
                .bearerSource(new NumberVerification.BearerSource() {
                    @Override
                    public CellularBearer acquire(CellularRequirement need, long timeoutMs)
                            throws CellularUnavailableException {
                        if (cellular == null) {
                            throw new CellularUnavailableException(need, AccessTech.WIFI);
                        }
                        return CellularBearer.fromNetwork(cellular, AccessTech.LTE);
                    }
                });
    }

    /** /authorize answering like the SAS: a code for the network path, or for a valid token hint. */
    private void sasLike() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                String hint = p.get("login_hint");
                if (hint != null && !hint.equals("operatortoken:" + TOKEN)) {
                    redirect(ex, p.get("redirect_uri") + "?error=access_denied&state=" + p.get("state"));
                    return;
                }
                redirect(ex, p.get("redirect_uri") + "?code=c0de-" + (hint == null ? "net" : "tok")
                        + "&state=" + p.get("state"));
            }
        });
    }

    @Test
    void networkPathReturnsTheCodeOverTheCellularHandle() throws Exception {
        sasLike();
        FakeCellular cellular = new FakeCellular();

        AuthorizationResult r = sdk(cellular).build().authorize(authorizeUrl(""));

        assertEquals("c0de-net", r.code());
        assertEquals(STATE, r.state());
        assertEquals(AuthorizationResult.Method.NETWORK, r.method());
        assertEquals(AccessTech.LTE, r.accessTech());
        assertEquals(1, cellular.opened.get(), "the request must leave on the cellular Network");
        assertNull(seen.get(0).get("login_hint"), "network path sends no hint");
        assertFalse(r.toString().contains("c0de"), "toString must redact the code");
    }

    @Test
    void wifiOnlyUsesTheOperatorTokenAsLoginHint() throws Exception {
        sasLike();
        AuthorizationResult r = sdk(null).operatorTokens(new OperatorTokenSource() {
            @Override
            public String fetch() {
                return TOKEN;
            }
        }).build().authorize(authorizeUrl(""));

        assertEquals("c0de-tok", r.code());
        assertEquals(AuthorizationResult.Method.OPERATOR_TOKEN, r.method());
        assertEquals("operatortoken:" + TOKEN, seen.get(0).get("login_hint"));
    }

    @Test
    void wifiOnlyWithoutTokenSourceFailsClosedBeforeAnyRequest() {
        sasLike();
        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> sdk(null).build().authorize(authorizeUrl("")));
        assertEquals(AccessTech.WIFI, ex.observed());
        assertTrue(seen.isEmpty(), "nothing may leave the device over Wi-Fi without a token");
    }

    @Test
    void sourceWithNoTokenIsTheSameAsNoSource() {
        sasLike();
        assertThrows(CellularUnavailableException.class, () -> sdk(null)
                .operatorTokens(new OperatorTokenSource() {
                    @Override
                    public String fetch() throws IOException {
                        throw new IOException("entitlement server down");
                    }
                }).build().authorize(authorizeUrl("")));
        assertTrue(seen.isEmpty());
    }

    @Test
    void networkDenialFallsThroughToTheSimWhenATokenExists() throws Exception {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                if (p.get("login_hint") == null) {
                    redirect(ex, p.get("redirect_uri") + "?error=access_denied&state=" + p.get("state"));
                } else {
                    redirect(ex, p.get("redirect_uri") + "?code=sim&state=" + p.get("state"));
                }
            }
        });
        AuthorizationResult r = sdk(new FakeCellular()).operatorTokens(new OperatorTokenSource() {
            @Override
            public String fetch() {
                return TOKEN;
            }
        }).build().authorize(authorizeUrl(""));

        assertEquals("sim", r.code());
        assertEquals(AuthorizationResult.Method.OPERATOR_TOKEN, r.method());
        assertEquals(2, seen.size());
    }

    @Test
    void networkDenialWithoutTokenIsReturnedAsIs() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                redirect(ex, p.get("redirect_uri") + "?error=access_denied&error_description=no+binding&state="
                        + p.get("state"));
            }
        });
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).build().authorize(authorizeUrl("")));
        assertEquals("access_denied", ex.error());
        assertEquals("no binding", ex.description());
        assertFalse(ex.getMessage().contains("no binding"), "server free text stays out of the message");
    }

    @Test
    void otherRefusalsAreNotRetriedOverTheSim() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                redirect(ex, p.get("redirect_uri") + "?error=invalid_scope&state=" + p.get("state"));
            }
        });
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).operatorTokens(new OperatorTokenSource() {
                    @Override
                    public String fetch() {
                        return TOKEN;
                    }
                }).build().authorize(authorizeUrl("")));
        assertEquals("invalid_scope", ex.error());
        assertEquals(1, seen.size(), "a misconfigured request is a bug, not a bearer problem");
    }

    @Test
    void followsIntermediateHopsOnTheSameBearer() throws Exception {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                redirect(ex, "/operator/net-auth?state=" + p.get("state"));
            }
        });
        route("/operator/net-auth", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                redirect(ex, "https://bank.example/cb?code=hop2&state=" + p.get("state"));
            }
        });
        FakeCellular cellular = new FakeCellular();
        AuthorizationResult r = sdk(cellular).build().authorize(authorizeUrl(""));

        assertEquals("hop2", r.code());
        assertEquals(2, cellular.opened.get(), "every hop must use the cellular handle");
    }

    @Test
    void foreignStateIsRefused() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                redirect(ex, p.get("redirect_uri") + "?code=x&state=someone-else");
            }
        });
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).build().authorize(authorizeUrl("")));
        assertEquals("invalid_state", ex.error());
    }

    @Test
    void redirectWithoutCodeIsRefused() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Map<String, String> p = params(ex);
                redirect(ex, p.get("redirect_uri") + "?state=" + p.get("state"));
            }
        });
        assertEquals("invalid_response", assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).build().authorize(authorizeUrl(""))).error());
    }

    @Test
    void directJsonErrorKeepsStatusAndCode() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                params(ex);
                body(ex, 400, "{\"error\":\"invalid_request\",\"error_description\":\"unknown client_id\"}");
            }
        });
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).build().authorize(authorizeUrl("")));
        assertEquals("invalid_request", ex.error());
        assertEquals(400, ex.httpStatus());
    }

    @Test
    void aPageInsteadOfARedirectIsInteractionRequired() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                params(ex);
                body(ex, 200, "<html>log in</html>");
            }
        });
        assertEquals("interaction_required", assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).build().authorize(authorizeUrl(""))).error());
    }

    @Test
    void serverErrorIsRetryLater() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                params(ex);
                body(ex, 503, "{}");
            }
        });
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).build().authorize(authorizeUrl("")));
        assertEquals("server_error", ex.error());
        assertTrue(ex.retryLater());
        assertEquals(1, seen.size(), "an answer is never retried by the SDK");
    }

    @Test
    void redirectLoopStops() {
        route("/authorize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                params(ex);
                redirect(ex, "/authorize?again=1");
            }
        });
        assertEquals("too_many_redirects", assertThrows(AuthorizationException.class,
                () -> sdk(new FakeCellular()).maxRedirects(3).build().authorize(authorizeUrl(""))).error());
        assertEquals(4, seen.size());
    }

    @Test
    void oneTransportErrorIsRetried() throws Exception {
        sasLike();
        FakeCellular cellular = new FakeCellular();
        cellular.failFirst = 1;

        AuthorizationResult r = sdk(cellular).build().authorize(authorizeUrl(""));

        assertEquals("c0de-net", r.code());
        assertEquals(2, cellular.opened.get());
    }

    @Test
    void persistentTransportErrorSurfaces() {
        sasLike();
        FakeCellular cellular = new FakeCellular();
        cellular.failFirst = 99;
        IOException ex = assertThrows(IOException.class,
                () -> sdk(cellular).transportRetries(2).build().authorize(authorizeUrl("")));
        assertFalse(ex instanceof AuthorizationException);
        assertEquals(3, cellular.opened.get());
    }

    @Test
    void plainHttpIsRefusedOutsideTheLab() {
        sasLike();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sdk(new FakeCellular()).allowInsecureHttpForLab(false).build()
                        .authorize(authorizeUrl("")));
        assertTrue(ex.getMessage().contains("https"));
        assertTrue(seen.isEmpty());
    }

    @Test
    void backendHintIsNeverOverridden() {
        sasLike();
        assertThrows(CellularUnavailableException.class, () -> sdk(null)
                .operatorTokens(new OperatorTokenSource() {
                    @Override
                    public String fetch() {
                        return TOKEN;
                    }
                }).build().authorize(authorizeUrl("&login_hint=" + AuthorizeUrl.encode("tel:+251911111111"))));
        assertTrue(seen.isEmpty());
    }
}
