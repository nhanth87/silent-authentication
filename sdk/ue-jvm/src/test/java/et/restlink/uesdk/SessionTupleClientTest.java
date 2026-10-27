/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTupleClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session-tuple", this::record);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void record(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        method.set(exchange.getRequestMethod());
        apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        try (InputStream in = exchange.getRequestBody()) {
            requestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @Test
    void postsExactBodyShapeAndHeaders() throws IOException {
        var snapshot = new SessionTupleCollector.TupleSnapshot(
                "10.20.30.40", null, 1724200000000L, "+251911111111", "621011234567890");

        int status = new SessionTupleClient().post(
                "http://127.0.0.1:" + port, snapshot, "secret-key");

        assertEquals(200, status);
        assertEquals("/session-tuple", path.get());
        assertEquals("POST", method.get());
        assertEquals("secret-key", apiKey.get());
        assertEquals("application/json", contentType.get());
        assertEquals("{\"srcIp\":\"10.20.30.40\",\"ts\":1724200000000,"
                        + "\"msisdn\":\"+251911111111\",\"imsi\":\"621011234567890\"}",
                requestBody.get());
    }

    @Test
    void omitsNullFieldsAndApiKeyHeaderWhenAbsent() throws IOException {
        var snapshot = new SessionTupleCollector.TupleSnapshot(
                "100.64.12.34", null, 1724200000001L, null, null);

        int status = new SessionTupleClient().post(
                "http://127.0.0.1:" + port + "/", snapshot, null);

        assertEquals(200, status);
        assertEquals("{\"srcIp\":\"100.64.12.34\",\"ts\":1724200000001}", requestBody.get());
        assertNull(apiKey.get());
    }

    @Test
    void propagatesNon2xxStatusFromServer() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session-tuple", exchange -> {
            byte[] payload = "{\"code\":\"UNAUTHENTICATED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        int badPort = server.getAddress().getPort();

        var snapshot = new SessionTupleCollector.TupleSnapshot(
                "10.0.0.9", null, 1724200000002L, null, null);

        assertEquals(401, new SessionTupleClient().post(
                "http://127.0.0.1:" + badPort, snapshot, "wrong-key"));
    }

    @Test
    void escapesStringsInBody() {
        var snapshot = new SessionTupleCollector.TupleSnapshot(
                "10.0.0.9", null, 1724200000003L, "+25191\"x\"\n", null);

        assertEquals("{\"srcIp\":\"10.0.0.9\",\"ts\":1724200000003,"
                        + "\"msisdn\":\"+25191\\\"x\\\"\\n\"}",
                SessionTupleClient.body(snapshot));
    }

    @Test
    void trimsTrailingSlashOnBaseUrl() {
        assertEquals("http://h", SessionTupleClient.trimTrailingSlash("http://h/"));
        assertEquals("http://h", SessionTupleClient.trimTrailingSlash("http://h"));
    }

    @Test
    void bodyCarriesDeclaredAccessTech() {
        var cellular = new SessionTupleCollector.TupleSnapshot(
                "10.64.12.34", null, 1724200000004L, null, null, AccessTech.LTE);

        assertEquals("{\"srcIp\":\"10.64.12.34\",\"ts\":1724200000004,\"accessTech\":\"LTE\"}",
                SessionTupleClient.body(cellular));
    }

    @Test
    void bodyOmitsUnknownAccessTech() {
        var legacy = new SessionTupleCollector.TupleSnapshot(
                null, null, 1724200000005L, null, null);

        assertEquals("{\"ts\":1724200000005}", SessionTupleClient.body(legacy));
        assertEquals(AccessTech.UNKNOWN, legacy.accessTech());
    }

    @Test
    void postsThroughTheInjectedConnectorSoTheBearerIsPinned() {
        var opened = new java.util.concurrent.atomic.AtomicBoolean();
        Connector pinning = url -> {
            opened.set(true);
            throw new java.io.IOException("stop before the socket");
        };
        var snapshot = new SessionTupleCollector.TupleSnapshot(
                null, null, 1724200000006L, null, null, AccessTech.NR);

        assertThrows(java.io.IOException.class, () -> new SessionTupleClient()
                .post("http://127.0.0.1:9", snapshot, null, pinning));
        assertTrue(opened.get(),
                "the SDK must ask the bearer for the connection, not the default route");
    }
}

