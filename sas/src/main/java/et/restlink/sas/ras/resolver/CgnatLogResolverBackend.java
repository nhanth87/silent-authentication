/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CGNAT-log resolver backend (P2 missing item #1).
 *
 * <p>Parses CGNAT translation log lines of the form:
 * <pre>
 * ts_epoch_ms,src_ip,src_port,msisdn,imsi,bearer_age_ms
 * </pre>
 * and maintains an in-memory binding table. Multiple bindings for the same
 * IP:port trigger {@link FallbackReason#AMBIGUOUS_BINDING} (fail-closed).</p>
 *
 * <p>Operator integration: point {@code sas.resolver.cgnat-log-path} at the
 * CGNAT syslog export. The file is re-read on each {@link #reload()} call
 * (triggered by a scheduler or admin endpoint).</p>
 */
public final class CgnatLogResolverBackend implements ResolverBackend {

    private static final Logger LOG = LogManager.getLogger(CgnatLogResolverBackend.class);

    private record Binding(String msisdn, String imsi, long bearerAgeMs) {}

    private final Map<String, List<Binding>> bindings = new ConcurrentHashMap<>();
    private final Path logPath;

    public CgnatLogResolverBackend(Path logPath) {
        this.logPath = logPath;
    }

    /** Parse the CGNAT log file into the binding table. */
    public void reload() {
        bindings.clear();
        if (logPath == null || !Files.isReadable(logPath)) {
            LOG.warn("CGNAT log not readable: {}", logPath);
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(logPath)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                parseLine(line);
            }
            LOG.info("CGNAT log loaded: {} keys from {}", bindings.size(), logPath);
        } catch (IOException e) {
            LOG.error("Failed to read CGNAT log: {}", logPath, e);
        }
    }

    private void parseLine(String line) {
        // Format: ts_epoch_ms,src_ip,src_port,msisdn,imsi,bearer_age_ms
        String[] parts = line.split(",");
        if (parts.length < 6) return;
        try {
            String srcIp = parts[1].trim();
            int srcPort = Integer.parseInt(parts[2].trim());
            String msisdn = parts[3].trim();
            String imsi = parts[4].trim();
            long bearerAgeMs = Long.parseLong(parts[5].trim());
            bindings.computeIfAbsent(key(srcIp, srcPort), k -> new CopyOnWriteArrayList<>())
                    .add(new Binding(msisdn, imsi, bearerAgeMs));
        } catch (NumberFormatException e) {
            LOG.debug("Skipping malformed CGNAT line: {}", line);
        }
    }

    @Override
    public CompletableFuture<ResolverResult> resolve(String srcIp, int srcPort, long tsEpochMs) {
        return CompletableFuture.supplyAsync(() -> {
            List<Binding> list = bindings.getOrDefault(key(srcIp, srcPort), List.of());
            if (list.isEmpty()) {
                return ResolverResult.miss(FallbackReason.NO_BINDING);
            }
            if (list.size() > 1) {
                return ResolverResult.miss(FallbackReason.AMBIGUOUS_BINDING);
            }
            Binding b = list.get(0);
            return ResolverResult.bound(b.msisdn(), b.imsi(), b.bearerAgeMs());
        });
    }

    private static String key(String srcIp, int srcPort) {
        return srcIp + ":" + srcPort;
    }
}
