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
                case "/subscribers" -> subscribers(exchange, method);
                case "/expected-res" -> expectedRes(exchange, method);
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

    /** The IMSI <-> MSISDN table, so a demo script can pick a subscriber. */
    private void subscribers(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "GET");
        StringBuilder out = new StringBuilder("{\"subscribers\":[");
        boolean first = true;
        for (et.restlink.hlrsim.SimState.Subscriber s : hlr.state().all()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"imsi\":\"").append(Json.escape(s.imsi()))
                    .append("\",\"msisdn\":\"").append(Json.escape(s.msisdn()))
                    .append("\",\"attached\":").append(s.attached())
                    .append(",\"vectors\":").append(s.vectors()).append('}');
        }
        out.append("],\"issuedVectors\":").append(hlr.state().issuedCount()).append('}');
        respond(exchange, 200, "application/json", out.toString());
    }

    /**
     * LAB-ONLY backdoor: the {@code RES} a demo device must present for a given
     * {@code RAND}. A real UE computes this inside the SIM from K and never exposes it;
     * this endpoint exists so the demo can drive the EAP exchange without a SIM, and it
     * is labelled as such in the response and in the README.
     */
    private void expectedRes(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "GET");
        Map<String, String> query = queryParams(exchange.getRequestURI().getRawQuery());
        String imsi = query.get("imsi");
        String randHex = query.get("rand");
        if (imsi == null || randHex == null) {
            throw new BadRequest("imsi and rand are required");
        }
        byte[] rand;
        try {
            rand = java.util.HexFormat.of().parseHex(randHex.trim().replace(" ", ""));
        } catch (IllegalArgumentException e) {
            throw new BadRequest("rand must be hex");
        }
        byte[] res = hlr.state().expectedRes(imsi, rand).orElse(null);
        if (res == null) {
            respond(exchange, 404, "application/json",
                    "{\"error\":\"unknown subscriber\",\"labOnly\":true}");
            return;
        }
        respond(exchange, 200, "application/json",
                "{\"labOnly\":true,\"note\":\"a real UE computes RES inside the SIM\""
                        + ",\"res\":\"" + java.util.HexFormat.of().formatHex(res) + "\"}");
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
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
