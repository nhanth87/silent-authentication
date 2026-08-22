/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.diameter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.config.SasAdminRuntimeConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads, validates, persists and hot-reloads the multi-realm Diameter config.
 * Fail-closed: an invalid JSON or invalid config keeps the last-good config.
 */
@ApplicationScoped
public class DiameterConfigService {

    public static final String KEY = "diameter.json";

    private static final Logger LOG = LogManager.getLogger(DiameterConfigService.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    private volatile DiameterConfig lastGood;
    private volatile long lastReloadMs;

    @Inject
    SasAdminRuntimeConfig runtimeConfig;

    /** Currently stored JSON, or the {@link DiameterConfig#defaults()} JSON when unset. */
    public String activeJson() {
        String stored = runtimeConfig.read(KEY);
        if (stored == null || stored.isBlank()) {
            stored = writeJson(defaults());
        }
        return stored;
    }

    /** Parse + validate the active JSON (fails closed on the last-good value). */
    public DiameterConfig current() {
        DiameterConfig current = lastGood;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (lastGood == null) {
                return reload();
            }
            return lastGood;
        }
    }

    public record Result(boolean ok, List<String> errors) {
        public static Result success() {
            return new Result(true, List.of());
        }

        public static Result failure(List<String> errors) {
            return new Result(false, List.copyOf(errors));
        }
    }

    public Result validate(String json) {
        try {
            parse(json);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.failure(List.of(e.getMessage()));
        } catch (JsonProcessingException e) {
            return Result.failure(List.of("invalid JSON: " + e.getOriginalMessage()));
        } catch (RuntimeException e) {
            return Result.failure(List.of(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Persist (when valid) and hot-reload. Returns {@code {"ok":true}} or {@code {"ok":false,"error":...}}. */
    public synchronized String save(String json) {
        Result validation = validate(json);
        if (!validation.ok()) {
            return writeJson(Map.of("ok", false, "error", String.join("; ", validation.errors())));
        }
        try {
            runtimeConfig.write(KEY, json);
            reload();
            return writeJson(Map.of("ok", true));
        } catch (RuntimeException e) {
            runtimeConfig.write(KEY, null);
            return writeJson(Map.of("ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    public synchronized DiameterConfig reload() {
        String json = activeJson();
        try {
            DiameterConfig cfg = parse(json);
            this.lastGood = cfg;
            this.lastReloadMs = System.currentTimeMillis();
            notifyListeners();
            return cfg;
        } catch (Exception e) {
            LOG.warn("diameter.json reload rejected — keeping last-good config", e);
            if (lastGood == null) {
                throw new IllegalArgumentException("diameter.json is invalid and no last-good config exists: "
                        + e.getMessage(), e);
            }
            return lastGood;
        }
    }

    public void register(Runnable reloadCallback) {
        if (reloadCallback != null) {
            reloadListeners.add(reloadCallback);
        }
    }

    public record StatusRecord(String realm, List<String> apps, boolean applied, long lastReload) {}

    public StatusRecord status() {
        DiameterConfig cfg = lastGood;
        if (cfg == null) {
            return new StatusRecord(null, List.of(), false, lastReloadMs);
        }
        List<String> apps = new ArrayList<>();
        if (cfg.applications != null) {
            for (DiameterConfig.Application app : cfg.applications) {
                apps.add(app.name + ":" + app.type + ":" + app.id);
            }
        }
        return new StatusRecord(cfg.localRealm, apps, true, lastReloadMs);
    }

    private DiameterConfig parse(String json) throws JsonProcessingException {
        DiameterConfig cfg = mapper.readValue(json, DiameterConfig.class);
        cfg.validate();
        return cfg;
    }

    private void notifyListeners() {
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("diameter reload listener failed", e);
            }
        }
    }

    private static DiameterConfig defaults() {
        return DiameterConfig.defaults();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error\":\"serialization failed\"}";
        }
    }
}