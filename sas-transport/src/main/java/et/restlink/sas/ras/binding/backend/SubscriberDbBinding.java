/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.ras.binding.SubscriberBinding;
import et.restlink.sas.ras.binding.SubscriberBindingBackend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Last-resort binding source: a read-only IMSI → MSISDN export
 * ({@code sas.binding.subscriber-db-path}).
 *
 * <p>Loaded once at boot and never refreshed — it is a handover artefact for the
 * operator's own provisioning feed, not a live store. That is exactly why it is the
 * <em>last</em> source in {@code sas.binding.source-order}: a stale export can only ever
 * agree or disagree with a live answer, and the live ones are asked first.</p>
 *
 * <p>Also the only source with no transport, so it is what the RA falls back to when
 * neither SWx nor Sh nor MAP is reachable — and, in the lab, the only one that exists.</p>
 */
public final class SubscriberDbBinding implements SubscriberBindingBackend {

    private static final Logger LOG = LogManager.getLogger(SubscriberDbBinding.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** IMSI (digits, no leading '+') → E.164 MSISDN. */
    private final Map<String, String> byImsi;

    public SubscriberDbBinding(Path export) {
        this.byImsi = load(export);
    }

    /** An explicitly empty source — every lookup resolves to "unresolved". */
    public SubscriberDbBinding() {
        this.byImsi = Map.of();
    }

    @Override
    public CompletableFuture<SubscriberBinding> lookup(String imsi) {
        if (imsi == null || imsi.isBlank()) {
            return CompletableFuture.completedFuture(
                    SubscriberBinding.unresolved(imsi, name()));
        }
        String msisdn = byImsi.get(normalise(imsi));
        return CompletableFuture.completedFuture(msisdn == null
                ? SubscriberBinding.unresolved(imsi, name())
                : SubscriberBinding.resolved(imsi, msisdn, name()));
    }

    @Override
    public void stop() {
        // nothing to release
    }

    @Override
    public String name() {
        return "subscriber-db";
    }

    /** Entries loaded — surfaced in the admin view so an empty export is visible. */
    public int size() {
        return byImsi.size();
    }

    /**
     * Strip the NAI decoration a device or the AAA may have attached, so
     * {@code 655010000000001@restlink.et} and {@code 655010000000001} are one identity.
     */
    static String normalise(String identity) {
        String value = identity.trim();
        int at = value.indexOf('@');
        if (at > 0) {
            value = value.substring(0, at);
        }
        return value.startsWith("+") ? value.substring(1) : value;
    }

    private static Map<String, String> load(Path export) {
        if (export == null) {
            LOG.info("Subscriber binding export not configured — this source answers 'unresolved'");
            return Map.of();
        }
        Path path = export.toAbsolutePath();
        if (!Files.isReadable(path)) {
            LOG.warn("Subscriber binding export {} is not readable — this source stays empty",
                    path);
            return Map.of();
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            JsonNode root = MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
            Map<String, String> out = new LinkedHashMap<>();
            if (root != null && root.isArray()) {
                for (JsonNode row : root) {
                    String imsi = text(row, "imsi");
                    String msisdn = text(row, "msisdn");
                    if (imsi != null && msisdn != null) {
                        out.put(normalise(imsi), msisdn);
                    }
                }
            } else if (root != null && root.isObject()) {
                // Also accept a plain {"imsi": "msisdn"} map.
                root.fields().forEachRemaining(e -> {
                    JsonNode value = e.getValue();
                    if (value != null && value.isTextual()) {
                        out.put(normalise(e.getKey()), value.asText().trim());
                    }
                });
            }
            LOG.info("Subscriber binding export loaded: {} entries from {}", out.size(), path);
            return Map.copyOf(out);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Subscriber binding export {} could not be parsed — this source stays empty",
                    path, e);
            return Map.of();
        }
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row == null ? null : row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** Never print the export contents. */
    @Override
    public String toString() {
        return "SubscriberDbBinding[entries=" + byImsi.size() + "]";
    }

    /** Test seam: the loaded map, for assertions only. */
    List<String> loadedIdentities() {
        return List.copyOf(byImsi.keySet());
    }
}
