/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persistence;

import et.restlink.sas.model.VerifyResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1 in-memory result store with TTL-based eviction.
 * Replaces the unbounded ConcurrentHashMap in VerifyCoordinator.
 */
public final class InMemoryVerifyResultStore implements VerifyResultStore {

    private record Entry(VerifyResult result, long storedAtMs) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMs;

    public InMemoryVerifyResultStore(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public void store(String reqId, VerifyResult result) {
        store.put(reqId, new Entry(result, System.currentTimeMillis()));
    }

    @Override
    public VerifyResult retrieve(String reqId) {
        Entry e = store.get(reqId);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.storedAtMs() > ttlMs) {
            store.remove(reqId);
            return null;
        }
        return e.result();
    }

    @Override
    public void remove(String reqId) {
        store.remove(reqId);
    }

    @Override
    public void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> (now - e.getValue().storedAtMs()) > ttlMs);
    }

    /** Visible for testing. */
    public int size() {
        return store.size();
    }
}
