/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.restlink.hlrsim.HlrSimulator;
import et.restlink.hlrsim.MessageLog;

/**
 * Control web UI on the JDK {@link HttpServer}: subscriber state view/update
 * ({@code /state}), health ({@code /health}) and the MAP message ring buffer
 * ({@code /messages}).
 */
public final class ControlWebServer {

    private static final Logger LOG = LogManager.getLogger(ControlWebServer.class);

    private final HlrSimulator hlr;
    private HttpServer server;

    public ControlWebServer(HlrSimulator hlr) {
        this.hlr = hlr;
    }

    public void start(String bindAddress, int webPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindAddress, webPort), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        LOG.info("Control UI listening on http://{}:{}/", bindAddress, webPort);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            switch (path) {
                case "/state" -> state(exchange, method);
                case "/health" -> health(exchange, method);
                case "/messages" -> messages(exchange, method);
                default -> respond(exchange, 404, "application/json",
                        "{\"error\":\"not found: " + Json.escape(path) + "\"}");
            }
        } catch (BadRequest e) {
            respond(exchange, 400, "application/json",
                    "{\"error\":\"" + Json.escape(e.getMessage() == null ? "bad request" : e.getMessage()) + "\"}");
        } catch (Exception e) {
            LOG.warn("control API failure {} {}", method, path, e);
            respond(exchange, 500, "application/json", "{\"error\":\"internal error\"}");
        } finally {
            exchange.close();
        }
    }

    private void state(HttpExchange exchange, String method) throws IOException {
        if ("GET".equals(method)) {
            respond(exchange, 200, "application/json", stateJson());
            return;
        }
        requireMethod(method, "POST");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> update = Json.parseFlatObject(body);
        if (update.containsKey("attached")) {
            Object value = update.get("attached");
            if (value instanceof Boolean b) {
                hlr.state().setAttached(b);
            } else if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                hlr.state().setAttached(Boolean.parseBoolean(s));
            } else {
                throw new BadRequest("attached must be a boolean");
            }
        }
        if (update.containsKey("vectors")) {
            Object value = update.get("vectors");
            if (!(value instanceof Number number) || number.intValue() < 0) {
                throw new BadRequest("vectors must be a non-negative number");
            }
            hlr.state().setVectors(number.intValue());
        }
        respond(exchange, 200, "application/json", stateJson());
    }

    private String stateJson() {
        return "{\"attached\":" + hlr.state().attached()
                + ",\"vectors\":" + hlr.state().vectors() + "}";
    }

    private void health(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "GET");
        respond(exchange, 200, "application/json",
                "{\"status\":\"up\",\"listening\":" + hlr.isStarted()
                        + ",\"associationConnected\":" + hlr.associationConnected() + "}");
    }

    private void messages(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "GET");
        List<String> items = new ArrayList<>();
        for (MessageLog.Entry entry : hlr.log().snapshot()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", entry.time().toString());
            row.put("direction", entry.direction());
            row.put("operation", entry.operation());
            row.put("dialogId", entry.dialogId());
            row.put("result", entry.result());
            row.put("details", entry.details());
            items.add(Json.objectJson(row));
        }
        respond(exchange, 200, "application/json",
                "{\"messages\":[" + String.join(",", items) + "]}");
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BadRequest(expected + " only");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}
