/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAP verifier backend tests (PSI + SAI, never ATI) — the 2G/3G identity-plane
 * stage of the Silent Auth SAS. Mirrors {@code InMemoryS6aVerifierBackendTest}
 * and {@code InMemorySwxVerifierBackendTest}.
 *
 * <p>Covers harness gates H1 (one PSI probe), H2 (no interconnect ATI) and H3
 * (SIM-swap freshness via SAI) at the unit level.</p>
 */
class InMemoryMapVerifierBackendTest {

    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";

    private InMemoryMapVerifierBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryMapVerifierBackend(0L, "AA");
    }

    @Test
    void attachedSubscriberIsReachable() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.GS_2G3G, now()).get();
        assertFalse(ev.failed());
        assertTrue(ev.reachable());
        assertTrue(ev.notSimSwapped());
        assertTrue(ev.locationPlausible());
        assertEquals("MAP-PSI+SAI", ev.protocol());
    }

    @Test
    void usesPsiAndSaiNeverAti() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.GS_2G3G, now()).get();
        // H2 — the evidence must come from PSI+SAI; ATI is FS.11 Category 1.
        assertFalse(ev.protocol().toUpperCase().contains("ATI"));
        assertTrue(MapVerifierBackend.NO_INTERCONNECT_ATI);
    }

    @Test
    void missingSubscriberIsVerifyError() throws Exception {
        VerificationEvidence ev = backend.verify("+251900000000", IMSI, AccessTech.GS_2G3G, now())
                .get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
        assertEquals("MAP-PSI", ev.protocol());
    }

    @Test
    void imsiMismatchIsSimSwapSuspect() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, "999999999999999",
                AccessTech.GS_2G3G, now()).get();
        assertTrue(ev.failed());
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, ev.failure());
    }

    @Test
    void freshImsiChangeIsNotSimSwappedFalse() throws Exception {
        backend.seed(MSISDN, IMSI, true, now(), "AA"); // SIM swapped just now
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.GS_2G3G, now()).get();
        assertFalse(ev.failed());
        assertFalse(ev.notSimSwapped());
    }

    @Test
    void detachedSubscriberIsNotReachable() throws Exception {
        backend.seed(MSISDN, IMSI, false, daysAgo(10), "AA");
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.GS_2G3G, now()).get();
        assertFalse(ev.failed());
        assertFalse(ev.reachable());
    }

    @Test
    void regionMismatchIsLocationImplausible() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "BB"); // VLR region differs from "AA"
        VerificationEvidence ev = backend.verify(MSISDN, IMSI, AccessTech.GS_2G3G, now()).get();
        assertFalse(ev.failed());
        assertFalse(ev.locationPlausible());
    }

    @Test
    void non2g3gAccessTechIsRejected() throws Exception {
        backend.seed(MSISDN, IMSI, true, daysAgo(10), "AA");

        VerificationEvidence lte = backend.verify(MSISDN, IMSI, AccessTech.LTE, now()).get();
        assertTrue(lte.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, lte.failure());

        VerificationEvidence wifi = backend.verify(MSISDN, IMSI, AccessTech.WIFI, now()).get();
        assertTrue(wifi.failed());
        assertEquals(FallbackReason.WIFI_NOT_READY, wifi.failure());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }
}