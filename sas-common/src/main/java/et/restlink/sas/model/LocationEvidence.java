/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Location evidence metadata (layered ADR R8): how old the last location update is and
 * which network serves the subscriber. Deliberately nothing finer — no cell identity,
 * LAC/TAC or coordinates — so it can cross to the CAMARA gateway, whose policy engine
 * decides freshness and relevance.
 *
 * @param ageMinutes minutes since the last location update (MAP
 *                   {@code ageOfLocationInformation}); {@code null} when the probe did not say
 * @param mcc        serving PLMN country code, 3 digits; {@code null} when unknown
 * @param mnc        serving PLMN network code, 2–3 digits; {@code null} when unknown
 */
public record LocationEvidence(Integer ageMinutes, String mcc, String mnc) {

    public LocationEvidence {
        if (ageMinutes != null && ageMinutes < 0) {
            throw new IllegalArgumentException("ageMinutes must be >= 0");
        }
        if ((mcc == null) != (mnc == null)) {
            throw new IllegalArgumentException("mcc and mnc go together");
        }
        if (mcc != null && (!mcc.matches("[0-9]{3}") || !mnc.matches("[0-9]{2,3}"))) {
            throw new IllegalArgumentException("mcc must be 3 digits, mnc 2-3 digits");
        }
    }

    public boolean hasPlmn() {
        return mcc != null;
    }
}
