/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Full-flow CDR: one row per {@code /verify} request, masked MSISDN, all
 * assurance/stage columns populated.
 */
class SasCdrFlowRecordTest {

    private static final String MSISDN = "+251911111111";

    private SasCdrService service;
    private CdrDbFlusher flusher;

    @BeforeEach
    void wire() {
        flusher = new CdrDbFlusher();
        flusher.init();
        service = new SasCdrService();
        service.flusher = flusher;
        service.enabled = true;
        service.dbEnabled = true;
    }

    @AfterEach
    void stop() {
        flusher.stop();
    }

    private static SasCdrService.FlowDetail approveDetail() {
        return new SasCdrService.FlowDetail(
                true, "APPROVE", 85, 70, "HIGH", "LOGIN",
                "GS_2G3G", null, "BOUND", "MAP-PSI+SAI",
                "{\"score\":85,\"threshold\":70,\"factors\":{"
                        + "\"ipBindingFresh\":{\"value\":1.0,\"weight\":0.25}}}", 42);
    }

    @Test
    void recordFlowWritesOneMaskedRowPerRequest() {
        service.recordFlow("req-1", MSISDN, approveDetail());

        List<et.restlink.sas.persist.SasCdrEntity> rows = flusher.recent(10);
        assertEquals(1, rows.size());
        var row = rows.get(0);

        assertEquals("req-1", row.correlationId);
        assertEquals("FLOW", row.phase);
        assertEquals("APPROVE", row.status);
        assertEquals("VERIFY", row.operation);

        // privacy — masked number only, never the raw MSISDN
        assertEquals("+251****11", row.msisdn);
        assertFalse(row.csvLine.contains(MSISDN));
        assertFalse(String.valueOf(row.detail).contains(MSISDN));

        // full-flow columns
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
        assertNotNull(row.evidenceJson);
        assertTrue(row.evidenceJson.contains("\"weight\":0.25"));
        assertEquals(42, row.totalMs);
    }

    @Test
    void recordFlowFallbackCarriesReasonAndVerifiedFalse() {
        service.recordFlow("req-2", MSISDN, new SasCdrService.FlowDetail(
                false, "FALLBACK", 60, 70, "FALLBACK", "LOGIN",
                "WIFI", "LOW_ASSURANCE", "SKIPPED_WIFI", "SWX",
                "{}", 17));

        var row = flusher.recent(10).get(0);
        assertEquals(Boolean.FALSE, row.verified);
        assertEquals("FALLBACK", row.decision);
        assertEquals("LOW_ASSURANCE", row.fallbackReason);
        assertEquals("SKIPPED_WIFI", row.resolverStatus);
        assertEquals("SWX", row.evidenceSource);
        assertEquals(60, row.score);
    }

    @Test
    void eachRequestWritesExactlyOneRow() {
        service.recordFlow("req-3", MSISDN, approveDetail());
        service.recordFlow("req-4", MSISDN, approveDetail());
        assertEquals(2, flusher.recent(10).size());
    }

    @Test
    void disabledServiceWritesNothing() {
        service.enabled = false;
        service.recordFlow("req-5", MSISDN, approveDetail());
        assertEquals(0, flusher.recent(10).size());
    }

    @Test
    void shortOrMissingNumbersAreNeverStored() {
        assertNull(SasCdrService.maskMsisdn(null));
        assertNull(SasCdrService.maskMsisdn(""));
        assertNull(SasCdrService.maskMsisdn("12345"));
        assertEquals("1234****67", SasCdrService.maskMsisdn("1234567"));
        assertEquals("+251****11", SasCdrService.maskMsisdn(MSISDN));
        service.recordFlow("req-6", "123", approveDetail());
        assertNull(flusher.recent(10).get(0).msisdn);
    }
}
