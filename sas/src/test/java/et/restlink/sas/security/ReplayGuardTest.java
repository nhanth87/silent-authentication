/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 replay-window + reqId dedup + token-key replay/single-use tests.
 */
class ReplayGuardTest {

    private ReplayGuard guard;
    private SasSecurityConfig config;

    @BeforeEach
    void setUp() {
        config = new SasSecurityConfig();
        guard = new ReplayGuard();
        try {
            var field = ReplayGuard.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(guard, config);
            setField(config, "replayWindowSeconds", 300L);
            setField(config, "reqIdTtlSeconds", 600L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void freshTimestamp_passes() {
        assertNull(guard.checkTimestamp(System.currentTimeMillis()));
    }

    @Test
    void oldTimestamp_rejected() {
        long old = System.currentTimeMillis() - 400_000L; // 400s > 300s window
        assertNotNull(guard.checkTimestamp(old));
    }

    @Test
    void futureTimestamp_rejected() {
        long future = System.currentTimeMillis() + 400_000L;
        assertNotNull(guard.checkTimestamp(future));
    }

    @Test
    void newReqId_passes() {
        assertNull(guard.checkReqId("req-1"));
    }

    @Test
    void duplicateReqId_rejected() {
        assertNull(guard.checkReqId("req-dup"));
        assertNotNull(guard.checkReqId("req-dup"));
    }

    @Test
    void blankReqId_rejected() {
        assertNotNull(guard.checkReqId(null));
        assertNotNull(guard.checkReqId(""));
    }

    @Test
    void combinedCheck_passes() {
        assertNull(guard.check(System.currentTimeMillis(), "req-ok"));
    }

    @Test
    void combinedCheck_rejectsOldTs() {
        long old = System.currentTimeMillis() - 400_000L;
        assertNotNull(guard.check(old, "req-x"));
    }

    // ---- token-key replay (jti + correlator) ----

    @Test
    void firstTokenKeyCorrelator_passes() {
        assertNull(guard.checkReplay("jti-1", "corr-1"));
    }

    @Test
    void sameKeySameCorrelator_isIdempotentRetry_passes() {
        assertNull(guard.checkReplay("jti-2", "corr-a"));
        assertNull(guard.checkReplay("jti-2", "corr-a"));
        assertEquals(1, guard.keyCount());
    }

    @Test
    void sameKeyDifferentCorrelator_isReplay_rejected() {
        assertNull(guard.checkReplay("jti-3", "corr-a"));
        String err = guard.checkReplay("jti-3", "corr-b");
        assertNotNull(err);
        assertTrue(err.contains("different x-correlator"));
    }

    @Test
    void blankTokenKey_rejected() {
        assertNotNull(guard.checkReplay(null, "corr"));
        assertNotNull(guard.checkReplay("", "corr"));
        assertNotNull(guard.checkReplay("  ", "corr"));
    }

    @Test
    void nullCorrelator_normalisedToEmpty_andConsistent() {
        assertNull(guard.checkReplay("jti-4", null));
        assertNull(guard.checkReplay("jti-4", ""));
        // a later non-empty correlator is still a different pairing
        assertNotNull(guard.checkReplay("jti-4", "corr-x"));
    }

    @Test
    void differentKeys_independent() {
        assertNull(guard.checkReplay("jti-a", "shared-corr"));
        assertNull(guard.checkReplay("jti-b", "shared-corr"));
        assertEquals(2, guard.keyCount());
    }

    // ---- single-use consumed tokens ----

    @Test
    void unconsumedToken_notConsumed() {
        assertFalse(guard.isConsumed("fresh-jti"));
    }

    @Test
    void consume_marksTokenUsed() {
        guard.consume("used-jti");
        assertTrue(guard.isConsumed("used-jti"));
        assertEquals(1, guard.consumedCount());
    }

    @Test
    void consume_isIdempotent() {
        guard.consume("dup-jti");
        guard.consume("dup-jti");
        assertEquals(1, guard.consumedCount());
        assertTrue(guard.isConsumed("dup-jti"));
    }

    @Test
    void consume_blankKey_noOp() {
        guard.consume(null);
        guard.consume("");
        assertEquals(0, guard.consumedCount());
    }

    @Test
    void consumedToken_expiresAfterWindow() throws Exception {
        setField(config, "replayWindowSeconds", 0L); // TTL 0 → immediate expiry
        guard.consume("short-jti");
        Thread.sleep(20); // let the clock move past the zero window
        assertFalse(guard.isConsumed("short-jti"));
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == java.util.Optional.class) {
            field.set(obj, java.util.Optional.ofNullable((String) value));
        } else {
            field.set(obj, value);
        }
    }
}
