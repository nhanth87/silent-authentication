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
 * P1 replay-window + reqId dedup tests.
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

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
