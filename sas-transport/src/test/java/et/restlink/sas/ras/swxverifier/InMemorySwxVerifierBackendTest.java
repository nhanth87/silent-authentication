/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 SWx (EAP-AKA, TS 29.273) verifier backend tests.
 */
class InMemorySwxVerifierBackendTest {

    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";

    private InMemorySwxVerifierBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemorySwxVerifierBackend(0L, "AA");
    }

    @Test
    void registeredWifiSubscriber_approves() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.WIFI, now())
                .get();
        assertFalse(ev.failed());
        assertTrue(ev.reachable());
        assertTrue(ev.notSimSwapped());
        assertTrue(ev.locationPlausible());
        assertEquals("SWX-EAP-AKA", ev.protocol());
    }

    @Test
    void missingSubscriber_purged() throws Exception {
        VerificationEvidence ev = backend.verify("+251900000000", IMSI, AccessTech.WIFI, now())
                .get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.PURGED, ev.failure());
    }

    @Test
    void imsiMismatch_simSwapSuspect() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, "999999999999999", AccessTech.WIFI, now())
                .get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, ev.failure());
    }

    @Test
    void claimedImsiMatchingRecord_approves() throws Exception {
        // B2 end-to-end contract: the entitlement-token IMSI rides through the
        // command; when it matches the HSS record the evidence stays clean.
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.WIFI, now())
                .get();
        assertFalse(ev.failed(), "claimed IMSI equal to the HSS record must approve");
    }

    @Test
    void blankClaimedImsi_treatedAsAbsent() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, "  ", AccessTech.WIFI, now())
                .get();
        assertFalse(ev.failed(), "blank claimed IMSI is not a mismatch");
    }

    @Test
    void freshImsiChange_simSwapSuspect() throws Exception {
        backend.seed(MSISDN, IMSI, true, now(), "AA"); // changed just now
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.WIFI, now())
                .get();
        assertFalse(ev.failed());
        assertFalse(ev.notSimSwapped());
    }

    @Test
    void nonWifiAccessTech_rejected() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.LTE, now())
                .get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void notEapAkaRegistered_notReachable() throws Exception {
        backend.seed(MSISDN, IMSI, false, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.WIFI, now())
                .get();
        assertFalse(ev.failed());
        assertFalse(ev.reachable());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }
}
