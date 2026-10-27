/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization requests the backend started and has not completed, keyed by
 * {@code state}. Each entry holds what only the backend may know — the PKCE
 * verifier, the nonce and the bank's user session — and is consumed exactly once.
 *
 * <p>In-memory and per instance. A bank running several backend instances needs a
 * shared store (or sticky sessions) with the same semantics: atomic take, TTL,
 * bounded size. That is what {@link #take} documents.</p>
 */
public final class PendingAuthorizations {

    /** What the backend keeps for one authorization request. */
    public record Pending(String state, String codeVerifier, String nonce, String userSession,
                          long createdEpochSec) {}

    private final Map<String, Pending> byState = new ConcurrentHashMap<>();
    private final long ttlSeconds;
    private final int maxEntries;
    private final Clock clock;

    public PendingAuthorizations(long ttlSeconds, int maxEntries, Clock clock) {
        if (ttlSeconds <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("ttl and size must be positive");
        }
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** 300 s, 100 000 entries, system clock. */
    public PendingAuthorizations() {
        this(300, 100_000, Clock.systemUTC());
    }

    void put(Pending pending) {
        long now = now();
        if (byState.size() >= maxEntries) {
            evict(now);
            if (byState.size() >= maxEntries) {
                throw new IllegalStateException("too many authorizations in flight");
            }
        }
        if (byState.putIfAbsent(pending.state(), pending) != null) {
            throw new IllegalStateException("state collision");
        }
    }

    /**
     * Removes and returns the entry for {@code state}: a second call with the same
     * state, an unknown state or an expired one all answer empty. The removal is
     * atomic, so two concurrent callbacks cannot both redeem one request.
     */
    public Optional<Pending> take(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Pending p = byState.remove(state);
        if (p == null || now() - p.createdEpochSec() > ttlSeconds) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    public int size() {
        return byState.size();
    }

    private void evict(long now) {
        for (Iterator<Pending> it = byState.values().iterator(); it.hasNext(); ) {
            if (now - it.next().createdEpochSec() > ttlSeconds) {
                it.remove();
            }
        }
    }

    long now() {
        return clock.millis() / 1000L;
    }
}
