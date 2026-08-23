/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gx IP → subscriber binding registry of the simulated PCRF side: answers
 * CCR Framed-IP-Address lookups with the provisioned Subscription-Id
 * (MSISDN/IMSI). One binding per IP; upserts replace.
 */
public final class BindingRegistry {

    /** Default lab binding seeded at startup (matches the SAS demo resolver). */
    public static final String DEMO_IP = "10.20.30.40";

    public record Binding(String ip, String msisdn, String imsi) {}

    private final ConcurrentHashMap<String, Binding> byIp = new ConcurrentHashMap<>();

    public BindingRegistry() {
        upsert(DEMO_IP, HssSimulator.DEMO_MSISDN, HssSimulator.DEMO_IMSI);
    }

    /** Insert or replace the binding for {@code ip}. */
    public synchronized Binding upsert(String ip, String msisdn, String imsi) {
        Binding fresh = new Binding(ip, msisdn, imsi);
        byIp.put(ip, fresh);
        return fresh;
    }

    public Binding find(String ip) {
        return ip == null ? null : byIp.get(ip);
    }

    public Binding remove(String ip) {
        return ip == null ? null : byIp.remove(ip);
    }

    /** Snapshot ordered by insertion for stable UI/API output. */
    public Collection<Binding> list() {
        Map<String, Binding> ordered = new LinkedHashMap<>();
        byIp.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) -> a.ip().compareTo(b.ip())))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered.values();
    }

    public void clear() {
        byIp.clear();
    }
}
