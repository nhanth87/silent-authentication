/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Location metadata for the <b>lab</b> (in-memory) verifier backends, which seed a region
 * label rather than a real location update. A subscriber in the expected region is
 * reported on the home PLMN (Ethio Telecom, 636-01), any other on the ITU test PLMN
 * 001-01, both with a fixed 5-minute age. Real probes report what the network says.
 */
public final class LabLocation {

    public static final int AGE_MINUTES = 5;
    public static final LocationEvidence HOME = new LocationEvidence(AGE_MINUTES, "636", "01");
    public static final LocationEvidence VISITED = new LocationEvidence(AGE_MINUTES, "001", "01");

    private LabLocation() {
    }

    public static LocationEvidence of(boolean inExpectedRegion) {
        return inExpectedRegion ? HOME : VISITED;
    }
}
