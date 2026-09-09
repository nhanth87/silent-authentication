/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import et.restlink.sas.api.ApiCdrRecorder.ApiCdrRecord;
import et.restlink.sas.persist.SasCdrEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SasCdrApiRecordTest {

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

    @Test
    void completedApiRecordWritesMaskedRow() {
        service.record(new ApiCdrRecord("corr-1", "SIMSWAP", "SIMSWAP", "+251****11",
                true, 200, null, "action=check swapped=true", "bankA", 12));

        SasCdrEntity row = flusher.recent(10).get(0);
        assertEquals("corr-1", row.correlationId);
        assertEquals("SIMSWAP", row.phase);
        assertEquals("SIMSWAP", row.operation);
        assertEquals("COMPLETED", row.status);
        assertEquals("+251****11", row.msisdn);
        assertEquals("bankA", row.tenantId);
        assertEquals(12, row.totalMs);
        assertTrue(row.detail.contains("action=check"));
        assertTrue(row.detail.contains("http=200"));
        assertFalse(row.detail.contains("code="));
        assertTrue(row.csvLine.endsWith(",bankA"));
    }

    @Test
    void failedApiRecordCarriesHttpAndErrorCode() {
        service.record(new ApiCdrRecord("corr-2", "OTP", "OTP", "+251****22",
                false, 400, "ONE_TIME_PASSWORD_SMS.INVALID_OTP",
                "action=validate-code result=INVALID", "bankB", 3));

        SasCdrEntity row = flusher.recent(10).get(0);
        assertEquals("FAILED", row.status);
        assertEquals("OTP", row.phase);
        assertTrue(row.detail.contains("http=400"));
        assertTrue(row.detail.contains("code=ONE_TIME_PASSWORD_SMS.INVALID_OTP"));
        assertTrue(row.eventsJson.contains("http=400"));
        assertTrue(row.eventsJson.contains("ONE_TIME_PASSWORD_SMS.INVALID_OTP"));
    }

    @Test
    void blankCorrelationIsReplacedByTraceableId() {
        service.record(new ApiCdrRecord("  ", "SIMSWAP", "SIMSWAP", null,
                true, 200, null, null, null, 1));

        SasCdrEntity row = flusher.recent(10).get(0);
        assertNotNull(row.correlationId);
        assertFalse(row.correlationId.isBlank());
        assertNull(row.msisdn);
        assertNull(row.tenantId);
    }

    @Test
    void longFieldsAreTruncatedToTheCdrSchema() {
        service.record(new ApiCdrRecord("x".repeat(200), "SIMSWAP", "SIMSWAP", null,
                true, 200, null, "y".repeat(2000), "t".repeat(200), 1));

        SasCdrEntity row = flusher.recent(10).get(0);
        assertEquals(128, row.correlationId.length());
        assertEquals(128, row.tenantId.length());
        assertTrue(row.detail.length() <= 1024);
    }

    @Test
    void disabledServiceWritesNothing() {
        service.enabled = false;
        service.record(new ApiCdrRecord("corr-3", "OTP", "OTP", null,
                true, 200, null, null, null, 1));

        assertEquals(0, flusher.recent(10).size());
    }

    @Test
    void recentReturnsNewestFirstAcrossFlowAndApiRecords() {
        service.record(new ApiCdrRecord("api-first", "OTP", "OTP", null,
                true, 200, null, null, null, 1));
        service.recordFlow("flow-second", "+251911111111", new SasCdrService.FlowDetail(
                true, "APPROVE", 85, 70, "HIGH", "LOGIN",
                "GS_2G3G", null, "BOUND", "MAP-PSI+SAI", "{}", 42, "bankA"));

        List<SasCdrEntity> rows = flusher.recent(10);
        assertEquals(2, rows.size());
        assertEquals("flow-second", rows.get(0).correlationId);
        assertEquals("api-first", rows.get(1).correlationId);
    }
}
