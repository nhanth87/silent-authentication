/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.util.Locale;

/**
 * Radio access technology the tuple was captured on. Wire names mirror the SAS
 * enum {@code et.restlink.sas.model.AccessTech}.
 *
 * <p>Silent auth by IP-match is only valid over a cellular data bearer: only
 * the PGW/GGSN can attest {@code IP -> MSISDN}. A Wi-Fi address is not
 * operator-attested, so {@link #cellular()} is what the SDK and the SAS both
 * use to decide whether a tuple may seed a binding at all.</p>
 */
public enum AccessTech {

    /** 2G/3G packet domain (GPRS/EDGE/UMTS/HSPA) — MAP verifier, TS 29.002. */
    GS_2G3G,
    /** 4G LTE — Diameter S6a verifier, TS 29.272. */
    LTE,
    /** 5G NR (SA or NSA). */
    NR,
    /** Wi-Fi — and Wi-Fi calling (IWLAN): not a cellular data path. */
    WIFI,
    /** Ethernet/other fixed access. */
    FIXED,
    /** Could not be determined. */
    UNKNOWN;

    /** True only for a real cellular 2G/3G/4G/5G data bearer. */
    public boolean cellular() {
        return this == GS_2G3G || this == LTE || this == NR;
    }

    /** Parses any casing; null/blank/unknown values become {@link #UNKNOWN}. */
    public static AccessTech parse(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    /**
     * Maps {@code android.telephony.TelephonyManager.NETWORK_TYPE_*} to this
     * enum using the documented public values.
     *
     * <p>On a device, {@code CellularBearer.classifyRadio} resolves the
     * security-relevant constants ({@code NETWORK_TYPE_NR}, {@code _LTE},
     * {@code _IWLAN}) from the framework first; this table is the fallback that
     * keeps the mapping deterministic off-device and covers the 2G/3G families.
     * Do not add a case here without the framework lookup also being correct.</p>
     *
     * <p>Note {@code NETWORK_TYPE_IWLAN} (18) is Wi-Fi calling: the transport
     * is Wi-Fi even though the subscription is cellular, so it must NOT be
     * treated as a cellular data bearer for binding purposes.</p>
     *
     * @param networkType a {@code NETWORK_TYPE_*} value, or -1 / unknown
     */
    public static AccessTech fromTelephonyNetworkType(int networkType) {
        switch (networkType) {
            case 13: // NETWORK_TYPE_LTE
                return LTE;
            case 20: // NETWORK_TYPE_NR
                return NR;
            case 18: // NETWORK_TYPE_IWLAN - Wi-Fi calling, not cellular data
                return WIFI;
            case -1: // NETWORK_TYPE_UNKNOWN
            case 0:  // NETWORK_TYPE_NONE (and the "not available" sentinel)
                return UNKNOWN;
            default:
                // 1..17 minus IWLAN are 2G/3G/CDMA families: MAP-verifiable.
                return networkType > 0 && networkType < 21 ? GS_2G3G : UNKNOWN;
        }
    }
}
