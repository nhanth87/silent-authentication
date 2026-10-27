/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;

/**
 * The device cannot serve a silent-auth request over a bearer the SAS can
 * attest: mobile data is off, the only live network is Wi-Fi, or the radio is
 * below the generation the bank asked for.
 *
 * <p>This is a <em>fallback signal</em>, not a bug to retry: the embedding app
 * must take the OTP / passkey / push path. Retrying over the Wi-Fi route would
 * make the SAS see a non-operator IP, which is exactly the case the fail-closed
 * FSM exists to reject.</p>
 */
public class CellularUnavailableException extends IOException {

    private final CellularRequirement requirement;
    private final AccessTech observed;

    CellularUnavailableException(CellularRequirement requirement, AccessTech observed) {
        super("silent auth needs " + requirement + " bearer, device is on "
                + (observed == null ? AccessTech.UNKNOWN : observed)
                + " (turn mobile data on / drop Wi-Fi) - fall back to OTP, never proceed");
        this.requirement = requirement;
        this.observed = observed;
    }

    /** What the caller demanded. */
    public CellularRequirement requirement() {
        return requirement;
    }

    /** What the device actually had. */
    public AccessTech observed() {
        return observed == null ? AccessTech.UNKNOWN : observed;
    }
}
