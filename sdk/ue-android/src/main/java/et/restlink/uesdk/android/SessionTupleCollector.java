/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 8 / Android minSdk 24; also runs on a plain JVM.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Collects the device network snapshot posted to SAS {@code /session-tuple}
 * (CGNAT disambiguation path A) from the network interfaces, for a device with
 * no radio API: a plain JVM, or an Android app that only has a context-free
 * thread. This is NOT authentication logic.
 *
 * <p>The CGNAT source port is NOT observable on the device, so
 * {@link TupleSnapshot#srcPort()} is always {@code null}; the SAS Resolver
 * correlates on IP + capture timestamp instead.</p>
 *
 * <p><strong>Cellular bearers.</strong> Silent auth by IP-match only works over
 * a cellular data bearer, because only the PGW/GGSN can attest
 * {@code IP -> MSISDN}. A snapshot therefore carries the {@link AccessTech} the
 * address was seen on (from the interface name), and
 * {@link #collectCellular(CellularRequirement)} (a) prefers an address owned by
 * a cellular interface and (b) throws {@link CellularUnavailableException}
 * instead of returning a Wi-Fi tuple when the caller demanded cellular. On
 * Android, route the SAS call itself through {@link CellularBearer}.</p>
 */
public final class SessionTupleCollector {

    /** One observed address plus the interface that owns it. */
    static final class Candidate {
        final InetAddress address;
        final String interfaceName;

        Candidate(InetAddress address, String interfaceName) {
            this.address = address;
            this.interfaceName = interfaceName;
        }

        AccessTech accessTech() {
            return AccessTech.fromInterfaceName(interfaceName);
        }
    }

    /**
     * Best-effort collection from the real network interfaces. The access
     * technology is whatever the owning interface looks like; a Wi-Fi device
     * gets a {@code WIFI}/{@code UNKNOWN} declaration, never a fake cellular.
     */
    public TupleSnapshot collect() {
        try {
            return collectFrom(enumerateCandidates(), CellularRequirement.ANY);
        } catch (CellularUnavailableException unreachable) {
            throw new IllegalStateException("ANY never refuses", unreachable);
        }
    }

    /**
     * Collects a tuple that satisfies {@code requirement}, preferring a
     * cellular interface address when the requirement demands one.
     *
     * @throws CellularUnavailableException when the device is not attached to
     *         a bearer that can support silent auth by IP-match
     */
    public TupleSnapshot collectCellular(CellularRequirement requirement)
            throws CellularUnavailableException {
        return collectFrom(enumerateCandidates(), requirement);
    }

    static TupleSnapshot collectFrom(List<Candidate> candidates, CellularRequirement requirement)
            throws CellularUnavailableException {
        CellularRequirement need = requirement == null ? CellularRequirement.CELLULAR : requirement;
        // Two passes: cellular first, then anything else, so rmnet/pdp_ip wins
        // over wlan0 on a dual-attached phone.
        for (boolean cellularOnly : new boolean[] {true, false}) {
            for (Candidate c : candidates) {
                AccessTech tech = c.accessTech();
                if (cellularOnly && !tech.cellular()) {
                    continue;
                }
                if (!usable(c.address)) {
                    continue;
                }
                need.check(tech);
                return new TupleSnapshot(c.address.getHostAddress(), null,
                        System.currentTimeMillis(), null, null, tech);
            }
        }
        // Nothing usable: a timestamp-only tuple with UNKNOWN tech, which still
        // fails a cellular requirement rather than looking plausible.
        need.check(AccessTech.UNKNOWN);
        return new TupleSnapshot(null, null, System.currentTimeMillis(), null, null, AccessTech.UNKNOWN);
    }

    /**
     * Injectable variant for tests: picks the first usable IPv4 from
     * {@code candidates} in deterministic order, declaring {@link AccessTech#UNKNOWN}.
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
        return new TupleSnapshot(ip, null, System.currentTimeMillis(), null, null, AccessTech.UNKNOWN);
    }

    private static boolean usable(InetAddress addr) {
        return addr instanceof Inet4Address
                && !addr.isLoopbackAddress()
                && !addr.isLinkLocalAddress()
                && !addr.isAnyLocalAddress()
                && !addr.isMulticastAddress();
    }

    private static List<Candidate> enumerateCandidates() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface nif = e.nextElement();
                Enumeration<InetAddress> a = nif.getInetAddresses();
                while (a.hasMoreElements()) {
                    candidates.add(new Candidate(a.nextElement(), nif.getName()));
                }
            }
        } catch (Exception interruptedOrIo) {
            return Collections.emptyList();
        }
        return candidates;
    }
}
