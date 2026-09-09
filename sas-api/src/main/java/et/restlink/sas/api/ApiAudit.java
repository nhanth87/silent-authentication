/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import java.util.UUID;

public final class ApiAudit {

    private final long startNanos = System.nanoTime();
    private final String phase;
    private final String operation;
    private final String correlationId;
    private final StringBuilder detail = new StringBuilder();

    private String msisdn;
    private String tenantId;
    private boolean ok = true;
    private int httpStatus;
    private String errorCode;

    private ApiAudit(String phase, String operation, String correlationId) {
        this.phase = phase;
        this.operation = operation;
        this.correlationId = correlationId;
    }

    public static ApiAudit start(String phase, String operation, String correlationId) {
        String id = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId.trim();
        return new ApiAudit(phase, operation, id);
    }

    public ApiAudit msisdn(String maskedMsisdn) {
        this.msisdn = blankToNull(maskedMsisdn);
        return this;
    }

    public ApiAudit tenant(String tenantId) {
        this.tenantId = blankToNull(tenantId);
        return this;
    }

    public ApiAudit detail(String name, Object value) {
        if (name == null || name.isBlank() || value == null) {
            return this;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return this;
        }
        if (detail.length() > 0) {
            detail.append(' ');
        }
        detail.append(name).append('=').append(text.replace('\n', ' ').replace('\r', ' '));
        return this;
    }

    public ApiCdrRecorder.ApiCdrRecord complete(int httpStatus, String errorCode) {
        this.httpStatus = httpStatus;
        this.errorCode = blankToNull(errorCode);
        this.ok = httpStatus < 400 && this.errorCode == null;
        return toRecord();
    }

    public ApiCdrRecorder.ApiCdrRecord toRecord() {
        long elapsed = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        return new ApiCdrRecorder.ApiCdrRecord(
                correlationId,
                phase,
                operation,
                msisdn,
                ok,
                httpStatus,
                errorCode,
                detail.toString(),
                tenantId,
                (int) Math.min(Integer.MAX_VALUE, elapsed));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
