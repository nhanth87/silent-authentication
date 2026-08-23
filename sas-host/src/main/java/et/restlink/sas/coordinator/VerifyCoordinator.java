/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.coordinator;

import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.persistence.InMemoryVerifyResultStore;
import et.restlink.sas.persistence.VerifyResultStore;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the async SLEE event router to the synchronous HTTP (Quarkus) thread.
 *
 * <p>Idempotency (proposal ch.6 §6.7): a {@code reqId} is registered once per
 * request; duplicate completed requests return the cached result, duplicate
 * in-flight requests await the same future (no double-dialog).</p>
 *
 * <p>P1: completed results are persisted through a {@link VerifyResultStore}
 * with TTL-based eviction instead of an unbounded map.</p>
 */
@ApplicationScoped
public class VerifyCoordinator {

    /** Completed-result TTL (10 min) — long enough for idempotent retries. */
    private static final long RESULT_TTL_MS = 10L * 60L * 1000L;

    private final ConcurrentHashMap<String, CompletableFuture<VerifyResult>> inFlight =
            new ConcurrentHashMap<>();
    private final VerifyResultStore resultStore = new InMemoryVerifyResultStore(RESULT_TTL_MS);

    /** True when a request with this id is still being processed. */
    public boolean isInFlight(String reqId) {
        return inFlight.containsKey(reqId);
    }

    /** Cached terminal result, or {@code null}. */
    public VerifyResult cached(String reqId) {
        return resultStore.retrieve(reqId);
    }

    /** Register (or return the existing) future for a request id. */
    public CompletableFuture<VerifyResult> register(String reqId) {
        return inFlight.computeIfAbsent(reqId, k -> new CompletableFuture<>());
    }

    /** Complete a future and persist the terminal result. */
    public void complete(String reqId, VerifyResult result) {
        resultStore.store(reqId, result);
        CompletableFuture<VerifyResult> f = inFlight.remove(reqId);
        if (f != null) {
            f.complete(result);
        }
    }

    /** Drop in-flight state without completing (request aborted). */
    public void forget(String reqId) {
        inFlight.remove(reqId);
        resultStore.remove(reqId);
    }

    /** Periodic eviction of expired completed results. */
    public void evictExpired() {
        resultStore.evictExpired();
    }
}