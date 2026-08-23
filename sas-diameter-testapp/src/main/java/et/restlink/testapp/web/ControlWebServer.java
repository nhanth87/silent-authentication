/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import et.restlink.testapp.BindingRegistry;
import et.restlink.testapp.HssSimulator;
import et.restlink.testapp.MessageLog;
import et.restlink.testapp.SubscriberState;
import et.restlink.testapp.diameter.HssDiameterServer;

/**
 * Control web UI on the JDK {@link HttpServer}: live Diameter message table,
 * subscriber state view/update, reset and health endpoints.
 */
public final class ControlWebServer {

    private static final Logger LOG = LogManager.getLogger(ControlWebServer.class);

    private final HssSimulator hss;
    private final HssDiameterServer diameter;
    private HttpServer server;

    public ControlWebServer(HssSimulator hss, HssDiameterServer diameter) {
        this.hss = hss;
        this.diameter = diameter;
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
            if (path.startsWith("/api/binding/")) {
                bindingDelete(exchange, method, path.substring("/api/binding/".length()));
                return;
            }
            switch (path) {
                case "/", "/index.html" -> respond(exchange, 200, "text/html; charset=utf-8", Pages.index());
                case "/api/messages" -> messages(exchange, method);
                case "/api/subscriber" -> subscriber(exchange, method);
                case "/api/binding" -> binding(exchange, method);
                case "/api/reset" -> reset(exchange, method);
                case "/api/health" -> health(exchange, method);
                default -> respond(exchange, 404, "application/json", Json.objectOf(
                        Map.of("error", "not found: " + path)));
            }
        } catch (BadRequest e) {
            respond(exchange, 400, "application/json",
                    Json.objectOf(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage())));
        } catch (Exception e) {
            LOG.warn("control API failure {} {}", method, path, e);
            respond(exchange, 500, "application/json",
                    Json.objectOf(Map.of("error", "internal error")));
        } finally {
            exchange.close();
        }
    }

    private void messages(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "GET");
        List<Map<String, Object>> items = new ArrayList<>();
        for (MessageLog.Entry entry : hss.log().snapshot()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", entry.time().toString());
            row.put("direction", entry.direction());
            row.put("command", entry.command());
            row.put("session", entry.sessionId());
            row.put("result", entry.result());
            row.put("details", entry.details());
            items.add(row);
        }
        respond(exchange, 200, "application/json", Json.arrayOf(items));
    }

    private void subscriber(HttpExchange exchange, String method) throws IOException {
        if ("GET".equals(method)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (SubscriberState state : hss.subscribers()) {
                items.add(subscriberFields(state));
            }
            respond(exchange, 200, "application/json",
                    "{\"subscribers\":" + Json.arrayOf(items) + "}");
            return;
        }
        requireMethod(method, "POST");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> update = Json.parseFlatObject(body);
        Object identity = update.get("identity");
        if (identity == null || identity.toString().isBlank()) {
            throw new BadRequest("identity (IMSI or MSISDN) is required");
        }
        SubscriberState state = hss.find(identity.toString())
                .orElseThrow(() -> new BadRequest("unknown identity " + identity));

        if (update.containsKey("attached")) {
            state.setAttached(bool(update.get("attached"), "attached"));
        }
        if (update.containsKey("barred")) {
            state.setBarred(bool(update.get("barred"), "barred"));
        }
        if (update.containsKey("authVectorsAvailable")) {
            Object value = update.get("authVectorsAvailable");
            if (!(value instanceof Number number) || number.intValue() < 0) {
                throw new BadRequest("authVectorsAvailable must be a non-negative number");
            }
            state.setAuthVectorsAvailable(number.intValue());
        }
        if (update.containsKey("subscribedRat")) {
            Object value = update.get("subscribedRat");
            if (value == null || value.toString().isBlank()) {
                throw new BadRequest("subscribedRat must not be blank");
            }
            state.setSubscribedRat(value.toString());
        }
        respond(exchange, 200, "application/json", Json.objectOf(subscriberFields(state)));
    }

    private void binding(HttpExchange exchange, String method) throws IOException {
        if ("GET".equals(method)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (BindingRegistry.Binding b : hss.bindings().list()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ip", b.ip());
                row.put("msisdn", b.msisdn());
                row.put("imsi", b.imsi());
                items.add(row);
            }
            respond(exchange, 200, "application/json",
                    "{\"bindings\":" + Json.arrayOf(items) + "}");
            return;
        }
        requireMethod(method, "POST");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> update = Json.parseFlatObject(body);
        Object ip = update.get("ip");
        if (ip == null || ip.toString().isBlank()) {
            throw new BadRequest("ip is required");
        }
        if (boolOrFalse(update.get("clear"))) {
            BindingRegistry.Binding removed = hss.bindings().remove(ip.toString());
            respond(exchange, 200, "application/json", Json.objectOf(Map.of(
                    "removed", removed != null,
                    "ip", ip.toString())));
            return;
        }
        Object msisdn = update.get("msisdn");
        Object imsi = update.get("imsi");
        if (msisdn == null || msisdn.toString().isBlank()) {
            throw new BadRequest("msisdn is required (or clear=true to remove)");
        }
        BindingRegistry.Binding bound = hss.bindings().upsert(ip.toString(), msisdn.toString(),
                imsi == null ? null : imsi.toString());
        respond(exchange, 200, "application/json", Json.objectOf(Map.of(
                "ip", bound.ip(),
                "msisdn", bound.msisdn(),
                "imsi", bound.imsi() == null ? "" : bound.imsi())));
    }

    private void bindingDelete(HttpExchange exchange, String method, String ip) throws IOException {
        requireMethod(method, "DELETE");
        BindingRegistry.Binding removed = hss.bindings().remove(ip);
        respond(exchange, 200, "application/json", Json.objectOf(Map.of(
                "removed", removed != null,
                "ip", ip)));
    }

    private static boolean boolOrFalse(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && s.equalsIgnoreCase("true");
    }

    private void reset(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "POST");
        hss.reset();
        respond(exchange, 200, "application/json",
                Json.objectOf(Map.of("reset", true)));
    }

    private void health(HttpExchange exchange, String method) throws IOException {
        requireMethod(method, "GET");
        respond(exchange, 200, "application/json", Json.objectOf(Map.of(
                "status", "up",
                "diameterListening", diameter.isListening())));
    }

    private static Map<String, Object> subscriberFields(SubscriberState state) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("imsi", state.imsi());
        fields.put("msisdn", state.msisdn());
        fields.put("attached", state.attached());
        fields.put("barred", state.barred());
        fields.put("authVectorsAvailable", state.authVectorsAvailable());
        fields.put("subscribedRat", state.subscribedRat());
        long lastEap = state.lastEapAuthSuccess();
        fields.put("lastEapAuthSuccess", lastEap <= 0 ? null : Instant.ofEpochMilli(lastEap).toString());
        return fields;
    }

    private static boolean bool(Object value, String field) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        throw new BadRequest(field + " must be a boolean");
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
