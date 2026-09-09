/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.api.ApiCdrRecorder.ApiCdrRecord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAuditTest {

    @Test
    void blankCorrelatorGetsTraceableId() {
        ApiCdrRecord record = ApiAudit.start("OTP", "OTP", "  ")
                .complete(200, null);

        assertNotNull(record.correlationId());
        assertFalse(record.correlationId().isBlank());
        assertTrue(record.ok());
    }

    @Test
    void detailPairsAreSpaceSeparatedAndNewlinesFlattened() {
        ApiCdrRecord record = ApiAudit.start("SIMSWAP", "SIMSWAP", "corr")
                .detail("action", "check")
                .detail("swapped", true)
                .detail("note", "first\nsecond")
                .detail("ignored", null)
                .complete(200, null);

        assertEquals("action=check swapped=true note=first second", record.detail());
    }

    @Test
    void errorCodeMarksRecordFailed() {
        ApiCdrRecord record = ApiAudit.start("OTP", "OTP", "corr")
                .msisdn("+251****11")
                .tenant("bankA")
                .complete(400, "ONE_TIME_PASSWORD_SMS.INVALID_OTP");

        assertFalse(record.ok());
        assertEquals(400, record.httpStatus());
        assertEquals("ONE_TIME_PASSWORD_SMS.INVALID_OTP", record.errorCode());
        assertEquals("+251****11", record.msisdn());
        assertEquals("bankA", record.tenantId());
    }

    @Test
    void serverErrorWithoutErrorCodeIsStillFailed() {
        ApiCdrRecord record = ApiAudit.start("OTP", "OTP", "corr")
                .complete(500, null);

        assertFalse(record.ok());
        assertNull(record.errorCode());
    }
}
