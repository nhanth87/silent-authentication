/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: wire contract of SessionTupleClient (method, path, headers, body
 * shape, status propagation, escaping).
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private final AtomicReference<String> accessTechHeader = new AtomicReference<>();

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

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private void record(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        method.set(exchange.getRequestMethod());
        apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        accessTechHeader.set(exchange.getRequestHeaders().getFirst("X-Sas-Access-Tech"));
        try (InputStream in = exchange.getRequestBody()) {
            requestBody.set(readAll(in));
        }
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @Test
    void postsExactBodyShapeAndHeaders() throws IOException {
        TupleSnapshot snapshot = new TupleSnapshot(
                null, null, 1724200000000L, "+251911111111", "621011234567890");

        int status = new SessionTupleClient().post(
                "http://127.0.0.1:" + port, snapshot, "secret-key");

        assertEquals(200, status);
        assertEquals("/session-tuple", path.get());
        assertEquals("POST", method.get());
        assertEquals("secret-key", apiKey.get());
        assertEquals("application/json", contentType.get());
        assertEquals("{\"ts\":1724200000000,\"msisdn\":\"+251911111111\","
                        + "\"imsi\":\"621011234567890\"}",
                requestBody.get());
    }

    @Test
    void deviceTupleCarriesOnlyTsAndOptionalMsisdn() {
        long before = System.currentTimeMillis();
        TupleSnapshot snapshot = TupleSnapshot.now("+251911111111");
        long after = System.currentTimeMillis();

        assertNull(snapshot.srcIp());
        assertNull(snapshot.srcPort());
        assertNull(snapshot.imsi());
        assertEquals("+251911111111", snapshot.claimedMsisdn());
        org.junit.jupiter.api.Assertions.assertTrue(
                snapshot.ts() >= before && snapshot.ts() <= after,
                "ts is the capture epoch-ms");
        assertEquals("{\"ts\":" + snapshot.ts() + ",\"msisdn\":\"+251911111111\"}",
                SessionTupleClient.body(snapshot));
    }

    @Test
    void omitsNullFieldsAndApiKeyHeaderWhenAbsent() throws IOException {
        TupleSnapshot snapshot = new TupleSnapshot(
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
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        int badPort = server.getAddress().getPort();

        TupleSnapshot snapshot = new TupleSnapshot(
                "10.0.0.9", null, 1724200000002L, null, null);

        assertEquals(401, new SessionTupleClient().post(
                "http://127.0.0.1:" + badPort, snapshot, "wrong-key"));
    }

    @Test
    void escapesStringsInBody() {
        TupleSnapshot snapshot = new TupleSnapshot(
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
    void cellularTupleDeclaresAccessTechOnBodyAndHeader() throws IOException {
        TupleSnapshot snapshot = new TupleSnapshot(
                null, null, 1724200000010L, null, null, AccessTech.LTE);

        int status = new SessionTupleClient().post(
                "http://127.0.0.1:" + port, snapshot, "k", Connector.DEFAULT);

        assertEquals(200, status);
        assertEquals("LTE", accessTechHeader.get(),
                "the SAS logs the declared tech from the header too");
        assertEquals("{\"ts\":1724200000010,\"accessTech\":\"LTE\"}", requestBody.get());
    }

    @Test
    void wifiBearerCannotSatisfyACellularRequirement() {
        TupleSnapshot wifi = new TupleSnapshot(
                null, null, 1724200000011L, null, null, AccessTech.WIFI);

        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> new SessionTupleClient().post("http://127.0.0.1:" + port,
                        wifi, null, Connector.DEFAULT, CellularRequirement.CELLULAR));
        assertEquals(AccessTech.WIFI, ex.observed());
    }

    @Test
    void cellularBearerIsTheConnectorUsedForTheRequest() throws IOException {
        final boolean[] used = new boolean[1];
        Connector pinning = url -> {
            used[0] = true;
            return url.openConnection();
        };
        TupleSnapshot snapshot = TupleSnapshot.cellularNow(null, AccessTech.NR);

        new SessionTupleClient().post("http://127.0.0.1:" + port, snapshot,
                null, pinning, CellularRequirement.CELLULAR_4G_PLUS);

        assertTrue(used[0], "the bearer must be the thing that opens the connection");
        assertEquals("NR", accessTechHeader.get());
    }
}

