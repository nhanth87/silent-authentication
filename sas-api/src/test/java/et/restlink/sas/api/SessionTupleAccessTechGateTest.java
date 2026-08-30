/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Device bearer declaration gate on {@code POST /session-tuple}: the table only
 * ever feeds the cellular IP→MSISDN Resolver, so a declared Wi-Fi/fixed tuple
 * must be refused and an unrecognised one must not silently become cellular.
 */
class SessionTupleAccessTechGateTest {

    @Test
    void cellularDeclarationsPassThroughNormalised() {
        assertEquals("LTE", SessionTupleResource.declaredAccessTech("lte"));
        assertEquals("NR", SessionTupleResource.declaredAccessTech("  NR "));
        assertEquals("GS_2G3G", SessionTupleResource.declaredAccessTech("GS_2G3G"));
    }

    @Test
    void absentDeclarationIsToleratedAsLegacyClient() {
        assertNull(SessionTupleResource.declaredAccessTech(null));
        assertNull(SessionTupleResource.declaredAccessTech(""));
        assertNull(SessionTupleResource.declaredAccessTech("   "));
    }

    @Test
    void nonCellularDeclarationsAreRefused() {
        for (String wifi : new String[] {"WIFI", "wlan", "IWLAN", "Fixed", "ETHERNET"}) {
            assertEquals(SessionTupleResource.NOT_CELLULAR,
                    SessionTupleResource.declaredAccessTech(wifi),
                    wifi + " must never seed a cellular binding");
        }
    }

    @Test
    void garbageIsRejectedNotDefaultedToCellular() {
        // The /verify header parser keeps its historical GS_2G3G default, but a
        // *device* claim must not be able to invent a bearer by typo-ing one.
        assertEquals(SessionTupleResource.INVALID,
                SessionTupleResource.declaredAccessTech("5G_ULTRA"));
        assertEquals(SessionTupleResource.INVALID,
                SessionTupleResource.declaredAccessTech("CELLULAR"));
    }

    @Test
    void sentinelsCannotCollideWithRealNames() {
        // The gate compares sentinels by identity of value; a client sending the
        // sentinel string itself must be classified INVALID, not "registered".
        assertEquals(SessionTupleResource.INVALID,
                SessionTupleResource.declaredAccessTech(SessionTupleResource.NOT_CELLULAR));
    }
}
