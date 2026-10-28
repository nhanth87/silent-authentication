/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: interface-based tuple collection, cellular preference, fail-closed.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTupleCollectorTest {

    private final SessionTupleCollector collector = new SessionTupleCollector();

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static List<SessionTupleCollector.Candidate> on(String ifName, InetAddress address) {
        return Collections.singletonList(new SessionTupleCollector.Candidate(address, ifName));
    }

    @Test
    void picksFirstUsableGlobalIpv4SkippingLoopbackAndLinkLocal() throws Exception {
        TupleSnapshot snapshot = collector.collect(Arrays.asList(
                ip(127, 0, 0, 1), ip(169, 254, 1, 2), ip(192, 168, 10, 7), ip(100, 64, 12, 34)));

        assertEquals("192.168.10.7", snapshot.srcIp());
        assertNull(snapshot.srcPort());
    }

    @Test
    void skipsMulticastAndAnyLocal() throws Exception {
        TupleSnapshot snapshot = collector.collect(Arrays.asList(
                ip(224, 0, 0, 1), ip(0, 0, 0, 0), ip(10, 20, 30, 40)));

        assertEquals("10.20.30.40", snapshot.srcIp());
    }

    @Test
    void bestEffortNullIpWhenNothingUsable() throws Exception {
        TupleSnapshot snapshot = collector.collect(Arrays.asList(ip(127, 0, 0, 1), ip(169, 254, 9, 9)));

        assertNull(snapshot.srcIp());
        assertTrue(snapshot.ts() > 0, "ts must be epoch-ms");
        assertNull(snapshot.claimedMsisdn());
        assertNull(snapshot.imsi());
        assertEquals(AccessTech.UNKNOWN, snapshot.accessTech());
    }

    @Test
    void cellularBearerWinsOverWifiOnDualAttachedDevice() throws Exception {
        List<SessionTupleCollector.Candidate> candidates = Arrays.asList(
                new SessionTupleCollector.Candidate(ip(192, 168, 10, 7), "wlan0"),
                new SessionTupleCollector.Candidate(ip(10, 64, 12, 34), "rmnet_data7"));

        TupleSnapshot snapshot = SessionTupleCollector.collectFrom(candidates, CellularRequirement.CELLULAR);

        assertEquals("10.64.12.34", snapshot.srcIp(), "cellular address must win");
        assertEquals(AccessTech.GS_2G3G, snapshot.accessTech());
    }

    @Test
    void wifiOnlyDeviceFailsClosedInsteadOfPostingAWifiTuple() throws Exception {
        List<SessionTupleCollector.Candidate> wifi = on("wlan0", ip(192, 168, 10, 7));
        assertThrows(CellularUnavailableException.class,
                () -> SessionTupleCollector.collectFrom(wifi, CellularRequirement.CELLULAR));
        assertThrows(CellularUnavailableException.class,
                () -> SessionTupleCollector.collectFrom(wifi, null), "no requirement means CELLULAR");
    }

    @Test
    void anyRequirementKeepsWifiTupleWithHonestDeclaration() throws Exception {
        TupleSnapshot snapshot = SessionTupleCollector.collectFrom(
                on("wlan0", ip(192, 168, 10, 7)), CellularRequirement.ANY);

        assertEquals("192.168.10.7", snapshot.srcIp());
        assertEquals(AccessTech.WIFI, snapshot.accessTech());
    }

    @Test
    void tunnelInterfaceNeverClaimsCellular() throws Exception {
        List<SessionTupleCollector.Candidate> vpn = on("tun0", ip(10, 8, 0, 2));
        assertThrows(CellularUnavailableException.class,
                () -> SessionTupleCollector.collectFrom(vpn, CellularRequirement.CELLULAR));
    }

    @Test
    void realInterfacesNeverFailWithAny() {
        assertTrue(collector.collect().ts() > 0);
    }
}
