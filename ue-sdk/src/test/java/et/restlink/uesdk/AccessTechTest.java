/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Tests: AccessTech classification + cellular requirement fail-closed rules.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTechTest {

    @Test
    void onlyRadioBearersCountAsCellular() {
        assertTrue(AccessTech.GS_2G3G.cellular());
        assertTrue(AccessTech.LTE.cellular());
        assertTrue(AccessTech.NR.cellular());
        assertFalse(AccessTech.WIFI.cellular());
        assertFalse(AccessTech.FIXED.cellular());
        assertFalse(AccessTech.UNKNOWN.cellular());
    }

    @Test
    void interfaceNamesMapToBearers() {
        assertEquals(AccessTech.GS_2G3G, AccessTech.fromInterfaceName("rmnet_data3"));
        assertEquals(AccessTech.GS_2G3G, AccessTech.fromInterfaceName("ccmapi0"));
        assertEquals(AccessTech.GS_2G3G, AccessTech.fromInterfaceName("pdp_ip0"));
        assertEquals(AccessTech.WIFI, AccessTech.fromInterfaceName("wlan0"));
        assertEquals(AccessTech.WIFI, AccessTech.fromInterfaceName("en0"));
        assertEquals(AccessTech.FIXED, AccessTech.fromInterfaceName("eth0"));
        // VPN/tunnel interfaces say nothing about the underlying radio.
        assertEquals(AccessTech.UNKNOWN, AccessTech.fromInterfaceName("tun0"));
        assertEquals(AccessTech.UNKNOWN, AccessTech.fromInterfaceName(null));
    }

    @Test
    void parseIsFailSafe() {
        assertEquals(AccessTech.LTE, AccessTech.parse(" lte "));
        assertEquals(AccessTech.UNKNOWN, AccessTech.parse("nonsense"));
        assertEquals(AccessTech.UNKNOWN, AccessTech.parse(null));
        assertEquals(AccessTech.UNKNOWN, AccessTech.parse("  "));
    }

    @Test
    void requirementAcceptsOnlyMatchingBearers() throws Exception {
        CellularRequirement.ANY.check(AccessTech.WIFI);
        CellularRequirement.CELLULAR.check(AccessTech.NR);
        CellularRequirement.CELLULAR_4G_PLUS.check(AccessTech.LTE);

        assertThrows(CellularRequirement.CellularBearerException.class,
                () -> CellularRequirement.CELLULAR.check(AccessTech.WIFI));
        // 2G/3G is cellular but is not an EPS/5GS bearer.
        assertThrows(CellularRequirement.CellularBearerException.class,
                () -> CellularRequirement.CELLULAR_4G_PLUS.check(AccessTech.GS_2G3G));
        // Unknown must never satisfy a cellular demand.
        assertThrows(CellularRequirement.CellularBearerException.class,
                () -> CellularRequirement.CELLULAR.check(AccessTech.UNKNOWN));
    }

    @Test
    void bearerExceptionCarriesTheDecisionContext() {
        var ex = assertThrows(CellularRequirement.CellularBearerException.class,
                () -> CellularRequirement.CELLULAR_4G_PLUS.check(AccessTech.WIFI));
        assertEquals(CellularRequirement.CELLULAR_4G_PLUS, ex.requirement());
        assertEquals(AccessTech.WIFI, ex.observed());
        assertTrue(ex.getMessage().contains("fall back"),
                "message must tell the integrator this is a fallback signal");
    }

    @Test
    void wireNamesMatchTheSasEnum() {
        for (AccessTech tech : new AccessTech[] {AccessTech.GS_2G3G, AccessTech.LTE,
                AccessTech.NR, AccessTech.WIFI}) {
            assertEquals(tech.name(), tech.wireName().toUpperCase(java.util.Locale.ROOT));
        }
    }
}
