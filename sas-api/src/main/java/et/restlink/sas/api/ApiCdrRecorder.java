/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

public interface ApiCdrRecorder {

    ApiCdrRecorder NOOP = record -> {
    };

    void record(ApiCdrRecord record);

    record ApiCdrRecord(
            String correlationId,
            String phase,
            String operation,
            String msisdn,
            boolean ok,
            int httpStatus,
            String errorCode,
            String detail,
            String tenantId,
            int totalMs) {
    }
}
