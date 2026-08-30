/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

/**
 * Point-in-time device tuple posted to SAS {@code /session-tuple}.
 *
 * <p>Devices cannot observe the real CGNAT source IP/port: {@code srcIp} and
 * {@code srcPort} are only ever set when the embedding app supplies them, so
 * in practice snapshots carry {@code ts}, an optional app-supplied MSISDN and
 * the {@link AccessTech} the SDK saw. The SDK itself never reads subscriber
 * identifiers.</p>
 *
 * <p>{@code accessTech} is not decoration: the SAS only trusts an IP&rarr;MSISDN
 * binding it can attribute to a cellular bearer, so a Wi-Fi tuple must be
 * identifiable as such rather than looking like a silent cellular one.</p>
 */
public final class TupleSnapshot {

    private final String srcIp;
    private final Integer srcPort;
    private final long ts;
    private final String claimedMsisdn;
    private final String imsi;
    private final AccessTech accessTech;

    public TupleSnapshot(String srcIp, Integer srcPort, long ts,
                         String claimedMsisdn, String imsi) {
        this(srcIp, srcPort, ts, claimedMsisdn, imsi, AccessTech.UNKNOWN);
    }

    public TupleSnapshot(String srcIp, Integer srcPort, long ts,
                         String claimedMsisdn, String imsi, AccessTech accessTech) {
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.ts = ts;
        this.claimedMsisdn = claimedMsisdn;
        this.imsi = imsi;
        this.accessTech = accessTech == null ? AccessTech.UNKNOWN : accessTech;
    }

    /**
     * Device-visible snapshot: capture timestamp now, no CGNAT visibility.
     * Declares {@link AccessTech#UNKNOWN} — prefer {@link #cellularNow}.
     */
    public static TupleSnapshot now(String claimedMsisdnNullable) {
        return new TupleSnapshot(null, null, System.currentTimeMillis(),
                claimedMsisdnNullable, null);
    }

    /**
     * Snapshot taken on a {@link CellularBearer}: the declared access technology
     * travels with the tuple so the SAS can refuse a non-cellular binding.
     */
    public static TupleSnapshot cellularNow(String claimedMsisdnNullable, AccessTech accessTech) {
        return new TupleSnapshot(null, null, System.currentTimeMillis(),
                claimedMsisdnNullable, null, accessTech);
    }

    public String srcIp() {
        return srcIp;
    }

    public Integer srcPort() {
        return srcPort;
    }

    public long ts() {
        return ts;
    }

    public String claimedMsisdn() {
        return claimedMsisdn;
    }

    public String imsi() {
        return imsi;
    }

    /** Never null; {@link AccessTech#UNKNOWN} when the radio could not be read. */
    public AccessTech accessTech() {
        return accessTech;
    }
}

