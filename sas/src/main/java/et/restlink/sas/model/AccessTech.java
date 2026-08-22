/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Subscriber access technology, drives which Verifier protocol the FSM uses.
 *
 * <ul>
 *   <li>{@link #GS_2G3G} — 2G/3G: MAP PSI/ATI/SAI (TS 29.002).</li>
 *   <li>{@link #LTE} / {@link #NR} — 4G/5G: Diameter S6a IDR/AIR (TS 29.272).</li>
 *   <li>{@link #WIFI} — Wi-Fi: SIM/TS.43 EAP-AKA via SWm/SWx (TS 33.402), no cellular bearer.</li>
 * </ul>
 */
public enum AccessTech {
    GS_2G3G,
    LTE,
    NR,
    WIFI
}