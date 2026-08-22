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
 * P1 replay-window and reqId dedup enforcement.
 *
 * <p>Two checks (fail-closed):</p>
 * <ol>
 *   <li><strong>Timestamp window:</strong> the request {@code ts} must be
 *       within {@code sas.security.replay-window-seconds} of now.</li>
 *   <li><strong>reqId dedup:</strong> a {@code reqId} may only be used once
 *       within {@code sas.security.reqid-ttl-seconds}. Duplicate reqIds are
 *       rejected (replay attack).</li>
 * </ol>
 */
@ApplicationScoped
public class ReplayGuard {

    private static final Logger LOG = LogManager.getLogger(ReplayGuard.class);

    @Inject
    SasSecurityConfig config;

    /** reqId → first-seen epoch ms. Entries expire after reqIdTtlSeconds. */
    private final Map<String, Long> seenReqIds = new ConcurrentHashMap<>();

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

    private void evictExpired(long nowMs) {
        long ttlMs = config.reqIdTtlSeconds() * 1000L;
        seenReqIds.entrySet().removeIf(e -> (nowMs - e.getValue()) > ttlMs);
    }

    /** Visible for testing. */
    int seenCount() {
        return seenReqIds.size();
    }
}
