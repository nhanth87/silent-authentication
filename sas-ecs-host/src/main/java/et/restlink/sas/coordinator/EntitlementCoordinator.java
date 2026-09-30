/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.coordinator;

import et.restlink.sas.events.Ts43RequestEvent;
import et.restlink.sas.model.Ts43Result;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the async SLEE event router to the synchronous HTTP (Quarkus) thread for
 * the TS.43 surface, and owns the per-activity EAP state
 * ({@link EntitlementSessions}).
 *
 * <p>Keyed by {@code reqId + phase}, so the two hops of one TS.43 exchange are two
 * separate answers while sharing one activity. A repeated hop replays the recorded
 * answer instead of driving a second EAP session: {@code CHALLENGE} is idempotent by
 * nature (a fresh vector may be fetched, but the caller's observable answer is the
 * same), and a repeated {@code RESPOND} must never mint a second token.</p>
 */
@ApplicationScoped
public class EntitlementCoordinator {

    /** Completed-answer TTL — the same window the verify path keeps. */
    private static final long ANSWER_TTL_MS = 60_000L;

    /** Per-activity EAP state, keyed by reqId. */
    @Inject
    EntitlementSessions sessions;

    private final Map<String, CompletableFuture<Ts43Result>> inFlight =
            new ConcurrentHashMap<>();

    /** Completed answers, so a replayed hop is answered from cache. */
    private final Map<String, Entry> completed = new ConcurrentHashMap<>();

    private record Entry(Ts43RequestEvent.Phase phase, Ts43Result result, long atMs) {
        boolean stale(long nowMs) {
            return nowMs - atMs > ANSWER_TTL_MS;
        }
    }

    private static String key(String reqId, Ts43RequestEvent.Phase phase) {
        return reqId + '|' + phase.name();
    }

    /** Cached answer for this exact hop, or {@code null}. */
    public Ts43Result cached(String reqId, Ts43RequestEvent.Phase phase, long nowMs) {
        Entry entry = completed.get(key(reqId, phase));
        if (entry == null) {
            return null;
        }
        if (entry.stale(nowMs)) {
            completed.remove(key(reqId, phase));
            return null;
        }
        return entry.result();
    }

    /** True when this hop is already being driven. */
    public boolean isInFlight(String reqId, Ts43RequestEvent.Phase phase) {
        return inFlight.containsKey(key(reqId, phase));
    }

    /** Register (or return the existing) future for one hop. */
    public CompletableFuture<Ts43Result> register(String reqId,
                                                  Ts43RequestEvent.Phase phase) {
        return inFlight.computeIfAbsent(key(reqId, phase), k -> new CompletableFuture<>());
    }

    /** Complete a hop's future and record its answer. */
    public void complete(String reqId, Ts43RequestEvent.Phase phase, Ts43Result result) {
        completed.put(key(reqId, phase), new Entry(phase, result, System.currentTimeMillis()));
        CompletableFuture<Ts43Result> future = inFlight.remove(key(reqId, phase));
        if (future != null) {
            future.complete(result);
        }
    }

    /**
     * Drop a hop without completing it — the HTTP caller gave up. The EAP state goes
     * with it: an abandoned Challenge must not stay usable.
     */
    public void forget(String reqId, Ts43RequestEvent.Phase phase) {
        inFlight.remove(key(reqId, phase));
        completed.remove(key(reqId, phase));
        sessions.discard(reqId);
    }

    /** Whole-activity release, both hops and the EAP state. */
    public void forgetActivity(String reqId) {
        inFlight.remove(key(reqId, Ts43RequestEvent.Phase.CHALLENGE));
        inFlight.remove(key(reqId, Ts43RequestEvent.Phase.RESPOND));
        completed.remove(key(reqId, Ts43RequestEvent.Phase.CHALLENGE));
        completed.remove(key(reqId, Ts43RequestEvent.Phase.RESPOND));
        sessions.discard(reqId);
    }

    /** Periodic housekeeping: expired answers and expired EAP sessions. */
    public int evictExpired() {
        long now = System.currentTimeMillis();
        int before = completed.size();
        completed.entrySet().removeIf(e -> e.getValue().stale(now));
        return (before - completed.size()) + sessions.sweep(now);
    }

    public int activeSessions() {
        return sessions.size();
    }
}