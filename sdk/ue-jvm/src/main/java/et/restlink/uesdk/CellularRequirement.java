/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.io.IOException;

/**
 * The SDK refuses to emit a tuple that does not satisfy this requirement.
 * Fail-closed: an unsatisfied requirement is an exception, never a silently
 * downgraded declaration.
 */
public enum CellularRequirement {

    /** Lab / TS.43 path — a Wi-Fi tuple may still be worth posting. */
    ANY,
    /** Any cellular bearer: 2G, 3G, 4G or 5G. */
    CELLULAR,
    /** LTE or NR only — the bank demands an EPS/5GS verifier path (S6a / 5G). */
    CELLULAR_4G_PLUS;

    /**
     * @throws CellularBearerException when {@code observed} does not satisfy
     *                                 this requirement (the caller must then
     *                                 fall back to OTP/passkey, never proceed).
     */
    public void check(AccessTech observed) throws CellularBearerException {
        switch (this) {
            case ANY -> {
                return;
            }
            case CELLULAR -> {
                if (!observed.cellular()) {
                    throw new CellularBearerException(this, observed);
                }
                return;
            }
            case CELLULAR_4G_PLUS -> {
                if (observed != AccessTech.LTE && observed != AccessTech.NR) {
                    throw new CellularBearerException(this, observed);
                }
                return;
            }
        }
    }

    /**
     * Thrown when the device is not on a bearer that can support the requested
     * silent-auth assurance. This is a <em>fallback signal</em>, not an error to
     * swallow: the embedding app must take the OTP / passkey / push path.
     */
    public static final class CellularBearerException extends IOException {

        private final CellularRequirement requirement;
        private final AccessTech observed;

        CellularBearerException(CellularRequirement requirement, AccessTech observed) {
            super("silent-auth needs " + requirement + " bearer, device is on " + observed
                    + " — fall back to OTP, never proceed");
            this.requirement = requirement;
            this.observed = observed;
        }

        public CellularRequirement requirement() {
            return requirement;
        }

        public AccessTech observed() {
            return observed;
        }
    }
}
