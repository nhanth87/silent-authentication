/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

/**
 * How strictly the SDK must insist on a cellular bearer before it will talk to
 * the SAS. Fail-closed in every mode: an unsatisfied requirement is a
 * {@link CellularUnavailableException}, never a silent downgrade to Wi-Fi.
 */
public enum CellularRequirement {

    /** Lab / TS.43 Wi-Fi path — a Wi-Fi tuple may still be worth posting. */
    ANY,
    /** Any cellular bearer: 2G, 3G, 4G or 5G. */
    CELLULAR,
    /** LTE or NR only — the bank demands an EPS/5GS verifier path. */
    CELLULAR_4G_PLUS;

    /**
     * @throws CellularUnavailableException when {@code observed} cannot satisfy
     *         this requirement; the app must then take the OTP/passkey path
     */
    public void check(AccessTech observed) throws CellularUnavailableException {
        AccessTech tech = observed == null ? AccessTech.UNKNOWN : observed;
        switch (this) {
            case ANY:
                return;
            case CELLULAR:
                if (!tech.cellular()) {
                    throw new CellularUnavailableException(this, tech);
                }
                return;
            case CELLULAR_4G_PLUS:
                if (tech != AccessTech.LTE && tech != AccessTech.NR) {
                    throw new CellularUnavailableException(this, tech);
                }
                return;
            default:
                throw new CellularUnavailableException(this, tech);
        }
    }
}
