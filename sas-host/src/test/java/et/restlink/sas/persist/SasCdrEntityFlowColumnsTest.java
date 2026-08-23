/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Column-mapping smoke for the V2 full-flow fields on {@link SasCdrEntity}.
 */
class SasCdrEntityFlowColumnsTest {

    @Test
    void newFlowColumnsRoundTripOnTheEntity() {
        SasCdrEntity row = new SasCdrEntity();
        assertNull(row.verified);
        assertNull(row.decision);
        assertNull(row.score);

        row.verified = Boolean.TRUE;
        row.decision = "APPROVE";
        row.score = 85;
        row.threshold = 70;
        row.assuranceLevel = "HIGH";
        row.riskClass = "LOGIN";
        row.accessTech = "GS_2G3G";
        row.fallbackReason = null;
        row.resolverStatus = "BOUND";
        row.evidenceSource = "MAP-PSI+SAI";
        row.evidenceJson = "{\"score\":85}";
        row.totalMs = 123;

        assertEquals(Boolean.TRUE, row.verified);
        assertEquals("APPROVE", row.decision);
        assertEquals(85, row.score);
        assertEquals(70, row.threshold);
        assertEquals("HIGH", row.assuranceLevel);
        assertEquals("LOGIN", row.riskClass);
        assertEquals("GS_2G3G", row.accessTech);
        assertNull(row.fallbackReason);
        assertEquals("BOUND", row.resolverStatus);
        assertEquals("MAP-PSI+SAI", row.evidenceSource);
        assertEquals("{\"score\":85}", row.evidenceJson);
        assertEquals(123, row.totalMs);
    }

    @Test
    void legacyColumnsStayIntact() {
        SasCdrEntity row = new SasCdrEntity();
        assertEquals(1, row.eventCount);
        assertNull(row.correlationId);
        assertNull(row.phase);
    }
}
