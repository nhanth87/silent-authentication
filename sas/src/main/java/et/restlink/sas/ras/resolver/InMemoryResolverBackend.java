/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pilot resolver: an in-memory IP:port → MSISDN/IMSI binding table.
 *
 * <p>CGNAT rule (fail-closed): when more than one MSISDN maps to the same
 * IP:port, the resolver returns {@link FallbackReason#AMBIGUOUS_BINDING} —
 * never a soft "latest" match.</p>
 */
public final class InMemoryResolverBackend implements ResolverBackend {

    private record Binding(String msisdn, String imsi, long bearerAgeMs) {
    }

    private final Map<String, List<Binding>> bindings = new ConcurrentHashMap<>();
    private final long simulatedLatencyMs;

    public InMemoryResolverBackend() {
        this(0L);
    }

    public InMemoryResolverBackend(long simulatedLatencyMs) {
        this.simulatedLatencyMs = simulatedLatencyMs;
    }

    /** Seed one binding. Multiple seeds for the same key simulate CGNAT sharing. */
    public void seed(String srcIp, int srcPort, String msisdn, String imsi, long bearerAgeMs) {
        bindings.computeIfAbsent(key(srcIp, srcPort), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new Binding(msisdn, imsi, bearerAgeMs));
    }

    @Override
    public CompletableFuture<ResolverResult> resolve(String srcIp, int srcPort, long tsEpochMs) {
        return CompletableFuture.supplyAsync(() -> {
            if (simulatedLatencyMs > 0L) {
                try {
                    Thread.sleep(simulatedLatencyMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
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