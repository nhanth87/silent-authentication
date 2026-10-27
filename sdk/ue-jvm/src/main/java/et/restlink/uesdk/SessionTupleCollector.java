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
import java.util.Enumeration;
import java.util.List;

/**
 * Collects the device network snapshot posted to SAS {@code /session-tuple}
 * (CGNAT disambiguation path A). This is NOT authentication logic.
 *
 * <p>The CGNAT source port is NOT observable on the device, so
 * {@link TupleSnapshot#srcPort()} is always {@code null}; the SAS Resolver
 * correlates on IP + capture timestamp instead.</p>
 *
 * <p><strong>Cellular bearers.</strong> Silent auth by IP-match only works
 * over a cellular data bearer, because only the PGW/GGSN can attest
 * {@code IP -> MSISDN}. A snapshot therefore carries the {@link AccessTech} the
 * address was seen on, and {@link #collectCellular(CellularRequirement)} (a)
 * prefers an address owned by a cellular interface and (b) throws
 * {@link CellularRequirement.CellularBearerException} instead of returning a
 * Wi-Fi tuple when the caller demanded cellular. On Android the app must also
 * route the SAS call itself through the cellular {@code Network} — see
 * {@code ue-sdk-android}'s {@code CellularBearer}.</p>
 */
public final class SessionTupleCollector {

    /**
     * Point-in-time device tuple. {@code claimedMsisdn} / {@code imsi} are
     * nullable and only ever set when the embedding app supplies them
     * voluntarily — the SDK itself never reads subscriber identifiers.
     * {@code accessTech} is what the device observed; it is never assumed.
     */
    public record TupleSnapshot(String srcIp, Integer srcPort, long ts,
                                String claimedMsisdn, String imsi,
                                AccessTech accessTech) {

        /** Pre-accessTech shape: declares {@link AccessTech#UNKNOWN}. */
        public TupleSnapshot(String srcIp, Integer srcPort, long ts,
                             String claimedMsisdn, String imsi) {
            this(srcIp, srcPort, ts, claimedMsisdn, imsi, AccessTech.UNKNOWN);
        }
    }

    /** One observed address plus the interface that owns it. */
    record Candidate(InetAddress address, String interfaceName) {

        AccessTech accessTech() {
            return AccessTech.fromInterfaceName(interfaceName);
        }
    }

    /**
     * Best-effort collection from the real network interfaces. The access
     * technology is whatever the owning interface looks like; a Wi-Fi device
     * gets a {@code WIFI}/{@code UNKNOWN} declaration, never a fake cellular.
     *
     * @throws CellularRequirement.CellularBearerException unreachable for the
     *         {@link CellularRequirement#ANY} policy; declared so callers must
     *         handle it uniformly with {@link #collectCellular(CellularRequirement)}.
     */
    public TupleSnapshot collect() throws CellularRequirement.CellularBearerException {
        return collectFrom(enumerateCandidates(), CellularRequirement.ANY);
    }

    /**
     * Collects a tuple that satisfies {@code requirement}, preferring a
     * cellular interface address when the requirement demands one.
     *
     * @throws CellularRequirement.CellularBearerException when the device is
     *         not attached to a bearer that can support silent auth by IP-match.
     */
    public TupleSnapshot collectCellular(CellularRequirement requirement)
            throws CellularRequirement.CellularBearerException {
        return collectFrom(enumerateCandidates(), requirement);
    }

    static TupleSnapshot collectFrom(List<Candidate> candidates,
                                      CellularRequirement requirement)
            throws CellularRequirement.CellularBearerException {
        // Two passes: cellular first, then anything else. Ordering keeps the
        // IPv4-scan behaviour identical for ANY while making rmnet/pdp_ip win
        // over wlan0 on a dual-attached phone.
        for (boolean cellularOnly : new boolean[] {true, false}) {
            for (Candidate c : candidates) {
                AccessTech tech = c.accessTech();
                if (cellularOnly && !tech.cellular()) {
                    continue;
                }
                if (!usable(c.address())) {
                    continue;
                }
                TupleSnapshot snapshot = new TupleSnapshot(
                        c.address().getHostAddress(), null, System.currentTimeMillis(),
                        null, null, tech);
                requirement.check(snapshot.accessTech());
                return snapshot;
            }
        }
        // Nothing usable: a timestamp-only tuple with UNKNOWN tech, which still
        // fails a cellular requirement rather than looking plausible.
        TupleSnapshot fallback = new TupleSnapshot(null, null,
                System.currentTimeMillis(), null, null, AccessTech.UNKNOWN);
        requirement.check(fallback.accessTech());
        return fallback;
    }

    /**
     * Injectable variant for tests: picks the first usable IPv4 from
     * {@code candidates} in deterministic order.
     */
    TupleSnapshot collect(List<InetAddress> candidates) {
        return collect(candidates, AccessTech.UNKNOWN);
    }

    /** Injectable variant that also fixes the declared access technology. */
    TupleSnapshot collect(List<InetAddress> candidates, AccessTech accessTech) {
        String ip = null;
        if (candidates != null) {
            for (InetAddress addr : candidates) {
                if (usable(addr)) {
                    ip = addr.getHostAddress();
                    break;
                }
            }
        }
        return new TupleSnapshot(ip, null, System.currentTimeMillis(), null, null, accessTech);
    }

    private static boolean usable(InetAddress addr) {
        return addr instanceof Inet4Address
                && !addr.isLoopbackAddress()
                && !addr.isLinkLocalAddress()
                && !addr.isAnyLocalAddress()
                && !addr.isMulticastAddress();
    }

    private static List<Candidate> enumerateCandidates() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements()) {
                NetworkInterface nif = e.nextElement();
                Enumeration<InetAddress> a = nif.getInetAddresses();
                while (a.hasMoreElements()) {
                    candidates.add(new Candidate(a.nextElement(), nif.getName()));
                }
            }
        } catch (Exception interruptedOrIo) {
            return List.of();
        }
        return candidates;
    }
}

