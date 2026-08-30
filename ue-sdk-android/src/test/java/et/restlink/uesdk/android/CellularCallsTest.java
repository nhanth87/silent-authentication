/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: CellularCalls facade — 4G/5G silent login fails closed off-device,
 * never fabricates a bearer or retries over Wi-Fi.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CellularCallsTest {

    @Test
    void fourGFiveGWithoutAndroidRuntimeFailsClosed() {
        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> CellularCalls.silentLogin4g5g(
                        null, "https://sas.example.et", "+251911111111", null));
        assertEquals(CellularRequirement.CELLULAR_4G_PLUS, ex.requirement());
        assertEquals(AccessTech.UNKNOWN, ex.observed());
    }

    @Test
    void plainLoginDefaultsToCellularAndFailsClosed() {
        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> CellularCalls.silentLogin(
                        null, "https://sas.example.et", "+251911111111", null,
                        CellularRequirement.CELLULAR));
        assertEquals(CellularRequirement.CELLULAR, ex.requirement());
    }

    @Test
    void nullRequirementDefaultsToCellular() {
        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> CellularCalls.silentLogin(
                        null, "https://sas.example.et", null, null, null));
        assertEquals(CellularRequirement.CELLULAR, ex.requirement());
        assertEquals(AccessTech.UNKNOWN, ex.observed());
    }

    @Test
    void facadeSignatureIsTransportOnly() throws IOException {
        // The facade must not invent reachability: with no android runtime there
        // is no bearer, and `bind` throws before any socket is opened. If this
        // ever returns a status it means a fake/fallback route was used.
        assertThrows(CellularUnavailableException.class,
                () -> CellularCalls.silentLogin4g5g(
                        new Object(), "https://sas.example.et", "+251911111111", "k"));
    }
}