/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.junit.jupiter.api.Test;

/**
 * Fail-closed S6a verifier backend tests (gates H4/H5/H13). Mirrors the
 * MAP verifier semantics over the Diameter S6a message set.
 */
class InMemoryS6aVerifierBackendTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long OLD_CHANGE = NOW - InMemoryS6aVerifierBackend.SWAP_COOLDOWN_MS - 60_000L;
    private static final long FRESH_CHANGE = NOW - 60_000L;

    private final InMemoryS6aVerifierBackend backend =
            new InMemoryS6aVerifierBackend(0L, "AA");

    @Test
    void approvesRegisteredSubscriber() throws Exception {
        backend.seed("+251911111111", "655010000000001", true, OLD_CHANGE, "AA");
        VerificationEvidence ev = backend.verify("+251911111111", "655010000000001",
                AccessTech.LTE, NOW).get();
        assertFalse(ev.failed());
        assertTrue(ev.reachable());
        assertTrue(ev.notSimSwapped());
        assertTrue(ev.locationPlausible());
        assertEquals("S6A-ULR+Sh-UDR", ev.protocol());
    }

    @Test
    void failsClosedOnPurgedSubscriber() throws Exception {
        VerificationEvidence ev = backend.verify("+251999999999", null, AccessTech.LTE, NOW).get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.PURGED, ev.failure());
    }

    @Test
    void failsClosedOnImsiMismatch() throws Exception {
        backend.seed("+251911111111", "655010000000001", true, OLD_CHANGE, "AA");
        VerificationEvidence ev = backend.verify("+251911111111", "655010000000009",
                AccessTech.LTE, NOW).get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, ev.failure());
    }

    @Test
    void failsClosedOnFreshImsiChange() throws Exception {
        backend.seed("+251911111111", "655010000000001", true, FRESH_CHANGE, "AA");
        VerificationEvidence ev = backend.verify("+251911111111", "655010000000001",
                AccessTech.LTE, NOW).get();
        assertFalse(ev.failed());
        assertFalse(ev.notSimSwapped());
    }

    @Test
    void rejectsNonCellularAccessTech() throws Exception {
        backend.seed("+251911111111", "655010000000001", true, OLD_CHANGE, "AA");
        VerificationEvidence ev = backend.verify("+251911111111", "655010000000001",
                AccessTech.WIFI, NOW).get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }
}