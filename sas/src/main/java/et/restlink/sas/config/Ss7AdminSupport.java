/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7ConfigException;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Admin-manageable jSS7 MAP stack configuration for the Silent Auth SAS.
 *
 * <p>Mirrors the GMLC {@code Ss7ConfigSupport} pattern but targets the SAS's
 * property surface ({@code sas.transport.jss7.*} via {@link SasTransportConfig})
 * and the in-memory {@link SasAdminRuntimeConfig} store (key {@code ss7.json}).</p>
 *
 * <p>Validation is fail-closed: a stack document (anything carrying
 * {@code sctp}/{@code m3ua}/{@code sccp}/{@code services}) is parsed by
 * {@link Ss7ConfigLoader}, anything else only gets a JSON syntax check. A
 * rejected document is never persisted, and {@code save} never enables a
 * signalling path by itself — the lead must also call {@link #apply()} /
 * {@link #start()} once the backend is wired.</p>
 */
@ApplicationScoped
public class Ss7AdminSupport {

    /** Runtime-config key the admin dashboard reads/writes for the stack JSON. */
    public static final String SS7_JSON_KEY = "ss7.json";

    private static final Logger LOG = LogManager.getLogger(Ss7AdminSupport.class);

    private static final int DEFAULT_LOCAL_PC = 1;
    private static final int DEFAULT_STP_PC = 2;

    private static final ObjectMapper JSON = buildMapper();

    @Inject
    SasAdminRuntimeConfig store;

    @Inject
    SasTransportConfig transport;

    private final List<Consumer<String>> applyConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> stopConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> startConsumers = new CopyOnWriteArrayList<>();

    private volatile Runnable reloadCallback;
    private volatile boolean applied;
    private volatile boolean m3uaReady;

    /** Validation / save outcome. */
    public record Result(boolean ok, List<String> errors) {
        public static Result success() {
            return new Result(true, List.of());
        }

        public static Result fail(String... messages) {
            return new Result(false, List.of(messages));
        }
    }

    /** Admin-visible stack status. */
    public record Status(boolean m3uaReady,
                         String activeFile,
                         String hlrGt,
                         String localGt,
                         boolean applied) {
    }

    /**
     * Validate a candidate stack JSON. Stack documents are parsed by
     * {@code Ss7ConfigLoader}; everything else is only checked for JSON syntax.
     */
    public Result validate(String json) {
        if (json == null || json.isBlank()) {
            return Result.fail("empty body");
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                return Result.fail("expected a JSON object");
            }
            if (looksLikeStackDocument(node)) {
                Ss7ConfigLoader.parse(json);
            }
            return Result.success();
        } catch (Ss7ConfigException ex) {
            return Result.fail(message(ex, "SS7 config rejected"));
        } catch (Exception ex) {
            return Result.fail("invalid JSON: " + message(ex, ex.getClass().getSimpleName()));
        }
    }

    /**
     * The effective stack JSON: persisted {@code ss7.json} first, then the
     * configured file, and finally a synthesised properties-based document.
     */
    public String activeJson() {
        String persisted = store.read(SS7_JSON_KEY);
        if (persisted != null && !persisted.isBlank()) {
            return persisted;
        }

        Path file = configFile(transport.jss7ConfigPath());
        if (file != null) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // fall through to the synthesised document
            }
        }

        return Ss7JsonBuilder.fromProperties(
                transport.jss7HlrGt(),
                transport.jss7LocalGt(),
                DEFAULT_LOCAL_PC,
                DEFAULT_STP_PC);
    }

    /**
     * Validate, persist (store + optional file) and return a JSON result.
     * Does <em>not</em> apply — the admin must call {@link #apply()} separately.
     */
    public String save(String json) {
        Result result = validate(json);
        if (!result.ok()) {
            return errJson(String.join("; ", result.errors()));
        }

        String configFile = transport.jss7ConfigPath();
        if (configFile != null && !configFile.isBlank()) {
            Path file = Path.of(configFile);
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, json, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return errJson("cannot write " + configFile + ": " + message(ex, "I/O error"));
            }
        }

        store.write(SS7_JSON_KEY, json);
        LOG.info("[ss7-admin] ss7.json saved ({} bytes)", json.length());
        return "{\"ok\":true}";
    }

    /** Push the active JSON into every registered apply consumer. */
    public void apply() {
        String json = activeJson();
        for (Consumer<String> consumer : applyConsumers) {
            try {
                consumer.accept(json);
            } catch (Exception ex) {
                LOG.warn("[ss7-admin] apply consumer failed", ex);
            }
        }
        applied = json != null && !json.isBlank();
    }

    /** Stop the live stack via every registered stop consumer (fail-closed). */
    public void stop() {
        m3uaReady = false;
        for (Consumer<String> consumer : stopConsumers) {
            try {
                consumer.accept(activeJson());
            } catch (Exception ex) {
                LOG.warn("[ss7-admin] stop consumer failed", ex);
            }
        }
    }

    /** Start the stack via every registered start consumer. */
    public void start() {
        m3uaReady = false;
        for (Consumer<String> consumer : startConsumers) {
            try {
                consumer.accept(activeJson());
                m3uaReady = true;
            } catch (Exception ex) {
                LOG.warn("[ss7-admin] start consumer failed", ex);
            }
        }
        applied = m3uaReady;
    }

    /**
     * Reload the live stack: run the registered reload callback if present,
     * otherwise stop-and-start the registered consumers.
     */
    public void reloadStacks() {
        Runnable callback = reloadCallback;
        if (callback != null) {
            try {
                callback.run();
                m3uaReady = true;
                applied = true;
            } catch (Exception ex) {
                LOG.warn("[ss7-admin] reload callback failed", ex);
                m3uaReady = false;
            }
            return;
        }
        stop();
        start();
    }

    /**
     * Register functional apply/stop/start hooks. The lead's bootstrap wires
     * the live {@code Jss7MapVerifierBackend} here (e.g. apply = rebuild with
     * the new file path, start/stop delegate directly).
     */
    public void registerApplyConsumers(Consumer<String> apply,
                                       Consumer<String> stop,
                                       Consumer<String> start) {
        if (apply != null) {
            applyConsumers.add(apply);
        }
        if (stop != null) {
            stopConsumers.add(stop);
        }
        if (start != null) {
            startConsumers.add(start);
        }
    }

    /** Simpler single-callback hook ({@code SasBootstrap} may set this). */
    public void setReloadCallback(Runnable callback) {
        this.reloadCallback = callback;
    }

    public Status status() {
        String file = transport.jss7ConfigPath();
        return new Status(
                m3uaReady,
                file == null ? "" : file,
                transport.jss7HlrGt(),
                transport.jss7LocalGt(),
                applied);
    }

    private static boolean looksLikeStackDocument(JsonNode node) {
        return node.has("sctp") || node.has("m3ua") || node.has("sccp") || node.has("services");
    }

    private static Path configFile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // SS7 config files carry `//` comments (see ss7-sas.json); tolerate them.
        mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        mapper.configure(JsonParser.Feature.ALLOW_YAML_COMMENTS, true);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static String message(Throwable ex, String fallback) {
        String m = ex.getMessage();
        return m == null || m.isBlank() ? fallback : m;
    }

    private static String errJson(String message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("ok", false);
        node.put("error", message == null ? "unknown error" : message);
        return node.toString();
    }
}