/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * CAMARA SimSwap v2.1.0 {@code POST /sim-swap/v2/retrieve-date} response body
 * ({@code SimSwapInfo}). {@code latestSimChange} is RFC 3339 with a time zone;
 * the SAS renders it as an ISO-8601 instant ({@code ...Z}). {@code
 * monitoredPeriod} (days of SIM-change supervision kept by the operator) is
 * optional and omitted while the SAS has no configured monitoring window —
 * nulls are never serialized ({@code NON_NULL}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimSwapInfo(String latestSimChange, Integer monitoredPeriod) {

    /** Render one evidence timestamp on the wire (UTC, RFC 3339 compatible). */
    public static SimSwapInfo of(Instant latestSimChange) {
        return new SimSwapInfo(DateTimeFormatter.ISO_INSTANT.format(latestSimChange), null);
    }
}
