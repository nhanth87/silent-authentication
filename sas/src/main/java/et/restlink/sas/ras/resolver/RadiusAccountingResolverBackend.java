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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RADIUS-accounting resolver backend (P2 missing item #1).
 *
 * <p>Consumes RADIUS Accounting-Request (Start/Interim/Stop) records from the
 * PGW/GGSN. Each record carries the UE's assigned IP, source port range, and
 * the subscriber identity (MSISDN from Calling-Station-Id or IMSI from
 * 3GPP-IMSI attribute).</p>
 *
 * <p>Integration: the operator's PGW sends RADIUS accounting to this service;
 * call {@link #accountStart} / {@link #accountStop} from the RADIUS listener.
 * CGNAT rule: multiple active bindings for the same IP:port ⇒
 * {@link FallbackReason#AMBIGUOUS_BINDING}.</p>
 */
public final class RadiusAccountingResolverBackend implements ResolverBackend {

    private static final Logger LOG = LogManager.getLogger(RadiusAccountingResolverBackend.class);

    private record Binding(String msisdn, String imsi, long bearerAgeMs, long startEpochMs) {}

    private final Map<String, List<Binding>> bindings = new ConcurrentHashMap<>();

    /** RADIUS Accounting-Start: a new bearer binding is active. */
    public void accountStart(String srcIp, int srcPort, String msisdn, String imsi, long startEpochMs) {
        long bearerAgeMs = System.currentTimeMillis() - startEpochMs;
        bindings.computeIfAbsent(key(srcIp, srcPort), k -> new CopyOnWriteArrayList<>())
                .add(new Binding(msisdn, imsi, bearerAgeMs, startEpochMs));
        LOG.debug("RADIUS start: {}:{} → {}", srcIp, srcPort, msisdn);
    }

    /** RADIUS Accounting-Stop: bearer released, remove binding. */
    public void accountStop(String srcIp, int srcPort, String msisdn) {
        List<Binding> list = bindings.get(key(srcIp, srcPort));
        if (list != null) {
            list.removeIf(b -> b.msisdn().equals(msisdn));
            if (list.isEmpty()) {
                bindings.remove(key(srcIp, srcPort));
            }
        }
        LOG.debug("RADIUS stop: {}:{} → {}", srcIp, srcPort, msisdn);
    }

    /** RADIUS Interim-Update: refresh bearer age. */
    public void accountInterim(String srcIp, int srcPort, String msisdn) {
        List<Binding> list = bindings.get(key(srcIp, srcPort));
        if (list != null) {
            long now = System.currentTimeMillis();
            list.replaceAll(b -> b.msisdn().equals(msisdn)
                    ? new Binding(b.msisdn(), b.imsi(), now - b.startEpochMs(), b.startEpochMs())
                    : b);
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
            // Point-in-time: the binding must have been active at tsEpochMs.
            if (b.startEpochMs() > tsEpochMs) {
                return ResolverResult.miss(FallbackReason.NO_BINDING);
            }
            long ageAtTs = tsEpochMs - b.startEpochMs();
            return ResolverResult.bound(b.msisdn(), b.imsi(), ageAtTs);
        });
    }

    private static String key(String srcIp, int srcPort) {
        return srcIp + ":" + srcPort;
    }
}
