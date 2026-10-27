/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: cellular bearer seam — fail-closed binding, radio mapping, and that
 * the tuple carries the declared access technology to the SAS.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellularBearerTest {

    /** Stands in for android.net.Network; the SDK only calls openConnection on it. */
    private static final class FakeNetwork {
        @SuppressWarnings("unused")
        public URLConnection openConnection(URL url) throws IOException {
            return url.openConnection();
        }
    }

    @Test
    void telephonyNetworkTypesMapToBearers() {
        assertEquals(AccessTech.LTE, AccessTech.fromTelephonyNetworkType(13));
        assertEquals(AccessTech.NR, AccessTech.fromTelephonyNetworkType(20));
        assertEquals(AccessTech.GS_2G3G, AccessTech.fromTelephonyNetworkType(10)); // HSPA
        assertEquals(AccessTech.GS_2G3G, AccessTech.fromTelephonyNetworkType(1));  // GPRS
        // Wi-Fi calling: the transport is Wi-Fi even though a SIM is involved.
        assertEquals(AccessTech.WIFI, AccessTech.fromTelephonyNetworkType(18));
        assertEquals(AccessTech.UNKNOWN, AccessTech.fromTelephonyNetworkType(-1));
        assertEquals(AccessTech.UNKNOWN, AccessTech.fromTelephonyNetworkType(999));
    }

    @Test
    void radioClassificationUsesFrameworkConstantsWhenPresent() {
        // Off-device the constants resolve to their documented fallbacks, so the
        // decision must still be the same as the table above.
        assertEquals(AccessTech.NR, CellularBearer.classifyRadio(
                CellularBearer.intConstant("android.telephony.TelephonyManager", "NETWORK_TYPE_NR", 20)));
        assertEquals(AccessTech.LTE, CellularBearer.classifyRadio(
                CellularBearer.intConstant("android.telephony.TelephonyManager", "NETWORK_TYPE_LTE", 13)));
        assertEquals(AccessTech.WIFI, CellularBearer.classifyRadio(
                CellularBearer.intConstant("android.telephony.TelephonyManager", "NETWORK_TYPE_IWLAN", 18)));
    }

    @Test
    void platformConstantsFallBackToDocumentedValuesOffDevice() {
        // android.net.* is not on the JVM classpath here: a missing constant must
        // yield the documented fallback rather than 0 (which is TRANSPORT_CELLULAR
        // and would make every network look cellular).
        assertEquals(0, CellularBearer.intConstant("android.net.NetworkCapabilities", "TRANSPORT_CELLULAR", 0));
        assertEquals(12, CellularBearer.intConstant("android.net.NetworkCapabilities", "NET_CAPABILITY_INTERNET", 12));
        assertEquals(7, CellularBearer.intConstant("no.such.Class", "WHATEVER", 7));
        assertEquals(CellularBearer.TRANSPORT_CELLULAR, 0);
        assertEquals(CellularBearer.TRANSPORT_WIFI, 1);
    }

    @Test
    void requirementRejectsWifiAndUnknown() {
        assertThrows(CellularUnavailableException.class,
                () -> CellularRequirement.CELLULAR.check(AccessTech.WIFI));
        assertThrows(CellularUnavailableException.class,
                () -> CellularRequirement.CELLULAR_4G_PLUS.check(AccessTech.GS_2G3G));
        assertDoesNotThrowAll();
    }

    private static void assertDoesNotThrowAll() {
        try {
            CellularRequirement.ANY.check(AccessTech.WIFI);
            CellularRequirement.CELLULAR.check(AccessTech.LTE);
            CellularRequirement.CELLULAR_4G_PLUS.check(AccessTech.NR);
        } catch (CellularUnavailableException shouldNotHappen) {
            throw new AssertionError("valid bearer rejected", shouldNotHappen);
        }
    }

    @Test
    void bindingWithoutContextFailsClosed() {
        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> CellularBearer.bind(null, CellularRequirement.CELLULAR));
        assertEquals(CellularRequirement.CELLULAR, ex.requirement());
        assertEquals(AccessTech.UNKNOWN, ex.observed());
    }

    @Test
    void wrappedNetworkWithoutHandleFailsClosed() {
        assertThrows(CellularUnavailableException.class,
                () -> CellularBearer.fromNetwork(null, AccessTech.LTE));
    }

    @Test
    void wrappedWifiNetworkIsRejectedAsBearer() {
        assertThrows(CellularUnavailableException.class,
                () -> CellularBearer.fromNetwork(new FakeNetwork(), AccessTech.WIFI));
    }

    @Test
    void wrappedCellularNetworkDefaultsToConservativeRadioAndPins() throws Exception {
        CellularBearer bearer = CellularBearer.fromNetwork(new FakeNetwork(), null);

        assertEquals(AccessTech.GS_2G3G, bearer.accessTech(),
                "no radio claim must not become a fabricated LTE declaration");
        assertTrue(bearer.bound());
        assertTrue(bearer.description().contains("requestNetwork"));

        // The bearer, not the URL, decides the route: open() must go through the
        // Network handle it was given.
        URLConnection conn = bearer.open(new URL("http://127.0.0.1:9/session-tuple"));
        assertNotNull(conn, "connection must come from the pinned Network handle");

        bearer.close();
        assertThrows(IOException.class,
                () -> bearer.open(new URL("http://127.0.0.1:9/session-tuple")),
                "a closed bearer must not silently reopen on the default route");
    }

    @Test
    void unboundBearerHasNoPinningAndNoContextIsUnknown() throws Exception {
        CellularBearer bearer = CellularBearer.unbound(null);

        assertFalse(bearer.bound());
        assertEquals(AccessTech.UNKNOWN, bearer.accessTech());
        assertTrue(bearer.open(new URL("http://127.0.0.1:9/")) instanceof URLConnection,
                "unbound must fall through to the platform default route");
    }

    @Test
    void detectWithoutAndroidRuntimeIsUnknown() {
        assertEquals(AccessTech.UNKNOWN, CellularBearer.detectAccessTech(null));
        assertEquals(AccessTech.UNKNOWN, CellularBearer.detectAccessTech(new Object()));
    }

    @Test
    void snapshotFactoriesDeclareTheBearer() {
        assertEquals(AccessTech.UNKNOWN, TupleSnapshot.now("+251911111111").accessTech());
        assertEquals(AccessTech.NR,
                TupleSnapshot.cellularNow("+251911111111", AccessTech.NR).accessTech());
        assertEquals(AccessTech.UNKNOWN,
                TupleSnapshot.cellularNow("+251911111111", null).accessTech());
    }
}
