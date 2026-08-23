/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * Collects the device network snapshot posted to SAS {@code /session-tuple}
 * (CGNAT disambiguation path A). This is NOT authentication logic.
 *
 * <p>The CGNAT source port is NOT observable on the device, so
 * {@link TupleSnapshot#srcPort()} is always {@code null}; the SAS Resolver
 * correlates on IP + capture timestamp instead.</p>
 */
public final class SessionTupleCollector {

    /**
     * Point-in-time device tuple. {@code claimedMsisdn} / {@code imsi} are
     * nullable and only ever set when the embedding app supplies them
     * voluntarily — the SDK itself never reads subscriber identifiers.
     */
    public record TupleSnapshot(String srcIp, Integer srcPort, long ts,
                                String claimedMsisdn, String imsi) {
    }

    /**
     * Best-effort collection from the real network interfaces.
     */
    public TupleSnapshot collect() {
        return collect(enumerateAddresses());
    }

    /**
     * Injectable variant for tests: picks the first usable IPv4 from
     * {@code candidates} in deterministic order.
     */
    TupleSnapshot collect(List<InetAddress> candidates) {
        String ip = null;
        if (candidates != null) {
            for (InetAddress addr : candidates) {
                if (usable(addr)) {
                    ip = addr.getHostAddress();
                    break;
                }
            }
        }
        return new TupleSnapshot(ip, null, System.currentTimeMillis(), null, null);
    }

    private static boolean usable(InetAddress addr) {
        return addr instanceof Inet4Address
                && !addr.isLoopbackAddress()
                && !addr.isLinkLocalAddress()
                && !addr.isAnyLocalAddress()
                && !addr.isMulticastAddress();
    }

    private static List<InetAddress> enumerateAddresses() {
        List<NetworkInterface> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements()) {
                interfaces.add(e.nextElement());
            }
        } catch (Exception interruptedOrIo) {
            return List.of();
        }
        interfaces.sort(Comparator.comparingInt(NetworkInterface::getIndex));
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface nif : interfaces) {
            Enumeration<InetAddress> e = nif.getInetAddresses();
            while (e.hasMoreElements()) {
                addresses.add(e.nextElement());
            }
        }
        return addresses;
    }
}
