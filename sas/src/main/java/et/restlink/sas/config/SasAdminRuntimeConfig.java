/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.config;

import et.restlink.sas.persist.SasConfigEntity;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Admin runtime configuration backed by the {@code sas_config} table with an
 * in-memory cache. Keeps the legacy {@code read/write/contains} surface used by
 * {@code DiameterConfigService} working while adding typed get/put accessors.
 */
@ApplicationScoped
public class SasAdminRuntimeConfig {

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @ConfigProperty(name = "sas.admin.key", defaultValue = "change-me")
    String adminKey;

    @PostConstruct
    void load() {
        refresh();
    }

    public synchronized void refresh() {
        cache.clear();
        try {
            for (SasConfigEntity e : SasConfigEntity.<SasConfigEntity>listAll()) {
                if (e.configKey != null && e.configValue != null) {
                    cache.put(e.configKey, e.configValue);
                }
            }
        } catch (RuntimeException ignored) {
            // schema not ready yet — fall back to defaults
        }
    }

    /** Constant-time admin API-key check (fail-closed when no key is configured). */
    public boolean adminKeyOk(String candidate) {
        if (candidate == null) {
            return false;
        }
        String expected = getOr(Keys.ADMIN_KEY, adminKey);
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    public String read(String key) {
        return get(key).orElse(null);
    }

    @Transactional
    public void write(String key, String value) {
        if (value == null) {
            remove(key);
        } else {
            put(key, value);
        }
    }

    public boolean contains(String key) {
        return key != null && cache.containsKey(key);
    }

    public Optional<String> get(String key) {
        if (key == null) return Optional.empty();
        String v = cache.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    public String getOr(String key, String def) {
        return get(key).orElse(def);
    }

    public boolean getBool(String key, boolean def) {
        return get(key).map(s -> "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim())).orElse(def);
    }

    public int getInt(String key, int def) {
        return get(key).map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }).orElse(def);
    }

    @Transactional
    public void put(String key, String value) {
        if (key == null || key.isBlank()) return;
        String k = key.trim();
        String v = value == null ? "" : value;
        SasConfigEntity e = SasConfigEntity.findById(k);
        if (e == null) {
            e = new SasConfigEntity();
            e.configKey = k;
        }
        e.configValue = v;
        e.persist();
        cache.put(k, v);
    }

    @Transactional
    public void remove(String key) {
        if (key == null || key.isBlank()) return;
        String k = key.trim();
        SasConfigEntity.deleteById(k);
        cache.remove(k);
    }

    public static final class Keys {
        private Keys() {}
        public static final String DIAMETER_JSON = "diameter.json";
        public static final String SS7_JSON = "ss7.json";
        public static final String HTTP_RA_PORT = "http.ra.port";
        public static final String ADMIN_KEY = "sas.admin.key";
    }
}