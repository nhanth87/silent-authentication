/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * CAMARA Number Verification v2 device side. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;

/**
 * Supplies a GSMA TS.43 temporary operator token — the SIM-based identity that
 * lets Number Verification work over Wi-Fi.
 *
 * <p><strong>Why this is an interface and not an implementation.</strong> The
 * token comes from the operator's entitlement server after an EAP-AKA run
 * against the SIM. On Android that SIM step needs
 * {@code TelephonyManager.getIccAuthentication()}, which only an app holding
 * carrier privileges for the SIM may call (the operator's own app, or one the
 * operator signs into its UICC access rules). A bank app has no such privilege,
 * so it gets the token from whatever the operator provides — an operator app, an
 * OS credential service, or in the lab the Restlink entitlement server. The SDK
 * takes the result and never pretends to compute it.</p>
 *
 * <p>Contract: return the bare token (no {@code operatortoken:} prefix), or
 * {@code null} when none can be had. A token is single use: fetch a fresh one
 * for every authorization. Never log it.</p>
 */
public interface OperatorTokenSource {

    /**
     * @return a fresh operator token, or null when the SIM-based path is unavailable
     * @throws IOException when the source failed; treated as "unavailable"
     */
    String fetch() throws IOException;
}
