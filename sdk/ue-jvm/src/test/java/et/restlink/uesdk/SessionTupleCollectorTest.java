/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTupleCollectorTest {

    private final SessionTupleCollector collector = new SessionTupleCollector();

    @Test
    void picksFirstUsableGlobalIpv4SkippingLoopbackAndLinkLocal() throws Exception {
        var snapshot = collector.collect(java.util.List.of(
                java.net.InetAddress.getByName("127.0.0.1"),
                java.net.InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 1, 2}),
                java.net.InetAddress.getByName("192.168.10.7"),
                java.net.InetAddress.getByAddress(new byte[]{100, 64, 12, 34})));

        assertEquals("192.168.10.7", snapshot.srcIp());
        assertNull(snapshot.srcPort());
    }

    @Test
    void skipsMulticastAndAnyLocal() throws Exception {
        var snapshot = collector.collect(java.util.List.of(
                java.net.InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, 1}),
                java.net.InetAddress.getByAddress(new byte[]{0, 0, 0, 0}),
                java.net.InetAddress.getByName("10.20.30.40")));

        assertEquals("10.20.30.40", snapshot.srcIp());
    }

    @Test
    void bestEffortNullIpWhenNothingUsable() throws Exception {
        var snapshot = collector.collect(java.util.List.of(
                java.net.InetAddress.getByName("127.0.0.1"),
                java.net.InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 9, 9})));

        assertNull(snapshot.srcIp());
        assertTrue(snapshot.ts() > 0, "ts must be epoch-ms");
        assertNull(snapshot.claimedMsisdn());
        assertNull(snapshot.imsi());
    }

    @Test
    void deterministicOrderPreservedForEqualCandidates() throws Exception {
        var snapshot = collector.collect(java.util.List.of(
                java.net.InetAddress.getByName("172.16.5.5"),
                java.net.InetAddress.getByAddress(new byte[]{100, 64, 0, 1})));

        assertEquals("172.16.5.5", snapshot.srcIp());
    }

    @Test
    void cellularBearerWinsOverWifiOnDualAttachedDevice() throws Exception {
        var candidates = java.util.List.of(
                new SessionTupleCollector.Candidate(
                        java.net.InetAddress.getByName("192.168.10.7"), "wlan0"),
                new SessionTupleCollector.Candidate(
                        java.net.InetAddress.getByAddress(new byte[]{10, 64, 12, 34}), "rmnet_data7"));

        var snapshot = SessionTupleCollector.collectFrom(candidates, CellularRequirement.CELLULAR);

        assertEquals("10.64.12.34", snapshot.srcIp(), "cellular address must win");
        assertEquals(AccessTech.GS_2G3G, snapshot.accessTech());
    }

    @Test
    void wifiOnlyDeviceFailsClosedInsteadOfPostingAWifiTuple() throws Exception {
        var candidates = java.util.List.of(new SessionTupleCollector.Candidate(
                java.net.InetAddress.getByName("192.168.10.7"), "wlan0"));

        assertThrows(CellularRequirement.CellularBearerException.class,
                () -> SessionTupleCollector.collectFrom(candidates, CellularRequirement.CELLULAR));
    }

    @Test
    void anyRequirementKeepsWifiTupleWithHonestDeclaration() throws Exception {
        var candidates = java.util.List.of(new SessionTupleCollector.Candidate(
                java.net.InetAddress.getByName("192.168.10.7"), "wlan0"));

        var snapshot = SessionTupleCollector.collectFrom(candidates, CellularRequirement.ANY);

        assertEquals("192.168.10.7", snapshot.srcIp());
        assertEquals(AccessTech.WIFI, snapshot.accessTech());
    }

    @Test
    void tunnelInterfaceNeverClaimsCellular() throws Exception {
        var candidates = java.util.List.of(new SessionTupleCollector.Candidate(
                java.net.InetAddress.getByName("10.8.0.2"), "tun0"));

        assertThrows(CellularRequirement.CellularBearerException.class,
                () -> SessionTupleCollector.collectFrom(candidates, CellularRequirement.CELLULAR));
    }
}

