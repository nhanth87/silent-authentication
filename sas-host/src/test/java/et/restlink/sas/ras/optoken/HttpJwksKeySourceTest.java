/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.optoken;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.sun.net.httpserver.HttpServer;

import et.restlink.sas.token.Es256Keys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJwksKeySourceTest {

    private HttpServer server;
    private final ECKey key = Es256Keys.generate(KeyUse.SIGNATURE);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks.json", ex -> {
            byte[] body = Es256Keys.publicJwks(List.of(key)).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/huge", ex -> {
            byte[] body = new byte[HttpJwksKeySource.MAX_BYTES + 10];
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/500", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void fetchesKeysWhenPlainHttpAllowed() {
        HttpJwksKeySource src = new HttpJwksKeySource();
        src.allowHttp = true;
        List<ECKey> keys = src.fetch(uri("/jwks.json"));
        assertEquals(1, keys.size());
        assertEquals(key.getKeyID(), keys.get(0).getKeyID());
    }

    @Test
    void plainHttpRefusedByDefault() {
        assertTrue(new HttpJwksKeySource().fetch(uri("/jwks.json")).isEmpty());
    }

    @Test
    void oversizedAndErrorResponsesYieldNothing() {
        HttpJwksKeySource src = new HttpJwksKeySource();
        src.allowHttp = true;
        assertTrue(src.fetch(uri("/huge")).isEmpty());
        assertTrue(src.fetch(uri("/500")).isEmpty());
        assertTrue(src.fetch(URI.create("http://127.0.0.1:1/nothing")).isEmpty());
    }
}
