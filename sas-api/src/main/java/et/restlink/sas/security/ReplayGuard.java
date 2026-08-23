/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1 replay-window, token-key idempotency and single-use enforcement.
 *
 * <p>Checks (fail-closed):</p>
 * <ol>
 *   <li><strong>Timestamp window:</strong> the request {@code ts} must be
 *       within {@code sas.security.replay-window-seconds} of now.</li>
 *   <li><strong>reqId dedup:</strong> a {@code reqId} may only be used once
 *       within {@code sas.security.reqid-ttl-seconds}. Duplicate reqIds are
 *       rejected (replay attack).</li>
 *   <li><strong>Token-key replay:</strong> a validated token key ({@code jti},
 *       or its SHA-256) may only ever be paired with ONE {@code x-correlator}.
 *       The same key with a different correlator is a replayed transaction;
 *       the same key with the same correlator is an idempotent retry and is
 *       served through the shared reqId path.</li>
 *   <li><strong>Single-use tokens:</strong> once a token key has driven one
 *       completed call it is consumed; any further use is rejected for the
 *       consume-TTL window (CAMARA NV: one API call per token).</li>
 * </ol>
 */
@ApplicationScoped
public class ReplayGuard {

    private static final Logger LOG = LogManager.getLogger(ReplayGuard.class);

    private record KeyRecord(String correlator, long firstSeenMs) {}

    @Inject
    SasSecurityConfig config;

    /** reqId → first-seen epoch ms. Entries expire after reqIdTtlSeconds. */
    private final Map<String, Long> seenReqIds = new ConcurrentHashMap<>();

    /** Token key → correlator + first-seen epoch ms (replay-window TTL). */
    private final Map<String, KeyRecord> seenKeys = new ConcurrentHashMap<>();

    /** Consumed token keys → consumed-at epoch ms (replay-window TTL). */
    private final Map<String, Long> consumedKeys = new ConcurrentHashMap<>();

    /**
     * Check the timestamp window. Returns null if valid, or a rejection reason.
     */
    public String checkTimestamp(long tsEpochMs) {
        long nowMs = System.currentTimeMillis();
        long windowMs = config.replayWindowSeconds() * 1000L;
        long age = nowMs - tsEpochMs;
        if (age < -windowMs) {
            return "request timestamp is too far in the future";
        }
        if (age > windowMs) {
            return "request timestamp is too old (replay window exceeded)";
        }
        return null;
    }

    /**
     * Check reqId uniqueness. Returns null if this is a new reqId, or a
     * rejection reason if it has been seen before (replay).
     */
    public String checkReqId(String reqId) {
        if (reqId == null || reqId.isBlank()) {
            return "missing reqId";
        }
        long nowMs = System.currentTimeMillis();
        // Evict expired entries lazily
        evictExpired(nowMs);
        Long firstSeen = seenReqIds.putIfAbsent(reqId, nowMs);
        if (firstSeen != null) {
            long ageMs = nowMs - firstSeen;
            if (ageMs < config.reqIdTtlSeconds() * 1000L) {
                return "duplicate reqId (replay detected)";
            }
            // Expired — allow reuse
            seenReqIds.put(reqId, nowMs);
        }
        return null;
    }

    /** Combined check: timestamp + reqId. Returns null if both pass. */
    public String check(long tsEpochMs, String reqId) {
        String tsResult = checkTimestamp(tsEpochMs);
        if (tsResult != null) {
            return tsResult;
        }
        return checkReqId(reqId);
    }

    /**
     * Token-key replay gate. First (key, correlator) pair wins:
     * <ul>
     *   <li>new key → registered, proceed;</li>
     *   <li>same key + same correlator → proceed (idempotent retry);</li>
     *   <li>same key + different correlator → rejected (replayed transaction).</li>
     * </ul>
     * Returns null when the call may proceed, else a rejection reason.
     */
    public String checkReplay(String tokenKey, String correlator) {
        if (tokenKey == null || tokenKey.isBlank()) {
            return "missing token identity";
        }
        String corr = correlator == null ? "" : correlator;
        long nowMs = System.currentTimeMillis();
        evictExpiredKeys(nowMs);
        KeyRecord record = seenKeys.get(tokenKey);
        if (record == null) {
            seenKeys.putIfAbsent(tokenKey, new KeyRecord(corr, nowMs));
            return null;
        }
        if (!record.correlator().equals(corr)) {
            LOG.warn("Token key replayed with a different x-correlator");
            return "token replayed with a different x-correlator";
        }
        return null;
    }

    /** True when the token key has already driven one completed call. */
    public boolean isConsumed(String tokenKey) {
        if (tokenKey == null || tokenKey.isBlank()) {
            return false;
        }
        Long consumedAt = consumedKeys.get(tokenKey);
        if (consumedAt == null) {
            return false;
        }
        long ttlMs = config.replayWindowSeconds() * 1000L;
        if ((System.currentTimeMillis() - consumedAt) > ttlMs) {
            consumedKeys.remove(tokenKey);
            return false;
        }
        return true;
    }

    /** Mark the token key as used (single-use enforcement). Idempotent. */
    public void consume(String tokenKey) {
        if (tokenKey == null || tokenKey.isBlank()) {
            return;
        }
        consumedKeys.putIfAbsent(tokenKey, System.currentTimeMillis());
    }

    private void evictExpired(long nowMs) {
        long ttlMs = config.reqIdTtlSeconds() * 1000L;
        seenReqIds.entrySet().removeIf(e -> (nowMs - e.getValue()) > ttlMs);
    }

    /** Evict stale token-key records and consumed markers (lazy, per check). */
    private void evictExpiredKeys(long nowMs) {
        long ttlMs = config.replayWindowSeconds() * 1000L;
        seenKeys.entrySet().removeIf(e -> (nowMs - e.getValue().firstSeenMs()) > ttlMs);
        consumedKeys.entrySet().removeIf(e -> (nowMs - e.getValue()) > ttlMs);
    }

    /** Visible for testing. */
    int seenCount() {
        return seenReqIds.size();
    }

    /** Visible for testing. */
    int keyCount() {
        return seenKeys.size();
    }

    /** Visible for testing. */
    int consumedCount() {
        return consumedKeys.size();
    }
}
