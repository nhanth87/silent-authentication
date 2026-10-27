/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.util.Locale;

/**
 * Radio access technology the SDK observed the session tuple on. Wire names
 * mirror the SAS enum {@code et.restlink.sas.model.AccessTech} so the device
 * declaration and the server FSM speak the same language.
 *
 * <p>Why this exists on the <em>device</em> side: the SAS IP-match path is
 * only sound when the captured IP:port:ts belongs to a <strong>cellular
 * bearer</strong> assigned by the PGW/GGSN. A Wi-Fi address is not
 * operator-attested, so a tuple collected over Wi-Fi must never seed a
 * cellular IP&rarr;MSISDN binding. The device therefore declares the access
 * technology it observed and the client refuses to send a tuple it knows came
 * from Wi-Fi when the caller asked for cellular-only assurance
 * (fail-closed — see {@link CellularRequirement}).</p>
 *
 * <p>2G/3G vs 4G/5G matters beyond logging: it selects the Verifier protocol
 * (MAP PSI/ATI/SAI vs Diameter S6a AIR/IDR) and it changes what evidence is
 * even available — a 2G/3G-only device has no S6a context, a 5G SA device may
 * have no MAP at all.</p>
 */
public enum AccessTech {

    /** 2G/3G packet domain (GPRS/EDGE/UMTS/HSPA) — MAP verifier, TS 29.002. */
    GS_2G3G,
    /** 4G LTE — Diameter S6a verifier, TS 29.272. */
    LTE,
    /** 5G NR (SA or NSA) — 5G core verifier / S6a-S6b path. */
    NR,
    /** Wi-Fi / IWLAN — never a cellular binding. */
    WIFI,
    /** Ethernet or other fixed access — treated like WIFI for binding. */
    FIXED,
    /** Could not be determined on this platform. */
    UNKNOWN;

    /**
     * True only for a real cellular 2G/3G/4G/5G bearer. Everything else —
     * Wi-Fi, Ethernet, unknown — is false, so the caller's default must be
     * "do not seed a cellular binding".
     */
    public boolean cellular() {
        return this == GS_2G3G || this == LTE || this == NR;
    }

    /** Lower-camel wire form used by SAS logs and CDRs. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a declaration from any casing/spacing. {@code null}, blank and
     * unrecognized values become {@link #UNKNOWN} — never a silent cellular.
     */
    public static AccessTech parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    /**
     * Classifies a network interface name per Linux/Android/iOS conventions.
     * Used by the JVM and Android collectors where the platform radio API is
     * unavailable; anything not recognised is {@link #UNKNOWN}.
     */
    public static AccessTech fromInterfaceName(String ifName) {
        if (ifName == null || ifName.isBlank()) {
            return UNKNOWN;
        }
        String n = ifName.toLowerCase(Locale.ROOT);
        // Android cellular data: rmnet_data*/rmnet*, ccmapi* (RIL), pdp_ip* (Tizen/older),
        // wwan* (general cellular). iOS cellular is pdp_ip0.
        if (n.startsWith("rmnet") || n.startsWith("ccmapi") || n.startsWith("pdp_ip")
                || n.startsWith("wwan") || n.startsWith("gsm") || n.startsWith("cdma")) {
            // Cellular, generation not readable from the name alone. GS_2G3G is the
            // conservative declaration: it routes the SAS Verifier to MAP PSI, which
            // answers for 2G-5G subscribers, whereas a wrong LTE declaration would
            // route to S6a and miss on a 2G/3G-only device.
            return GS_2G3G;
        }
        // Wi-Fi: Android wlan*, macOS/iOS en*, legacy aiport.
        if (n.startsWith("wlan") || n.startsWith("wl") || n.startsWith("en")
                || n.startsWith("ath") || n.startsWith("airport")) {
            return WIFI;
        }
        // Wired.
        if (n.startsWith("eth") || n.startsWith("ib") || n.equals("lo")) {
            return n.equals("lo") ? UNKNOWN : FIXED;
        }
        // Tunnels/bridges/VPN say nothing about the underlying radio.
        return UNKNOWN;
    }
}
