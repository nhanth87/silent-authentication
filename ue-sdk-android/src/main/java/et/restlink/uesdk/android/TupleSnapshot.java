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
 * in practice snapshots carry {@code ts} plus an optional app-supplied
 * MSISDN. The SDK itself never reads subscriber identifiers.</p>
 */
public final class TupleSnapshot {

    private final String srcIp;
    private final Integer srcPort;
    private final long ts;
    private final String claimedMsisdn;
    private final String imsi;

    public TupleSnapshot(String srcIp, Integer srcPort, long ts,
                         String claimedMsisdn, String imsi) {
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.ts = ts;
        this.claimedMsisdn = claimedMsisdn;
        this.imsi = imsi;
    }

    /**
     * Device-visible snapshot: capture timestamp now, no CGNAT visibility.
     */
    public static TupleSnapshot now(String claimedMsisdnNullable) {
        return new TupleSnapshot(null, null, System.currentTimeMillis(),
                claimedMsisdnNullable, null);
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
}
