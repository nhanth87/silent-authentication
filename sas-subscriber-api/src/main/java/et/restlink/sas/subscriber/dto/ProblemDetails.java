/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** TS 29.122-shaped problem details; {@code cause} carries the contract code. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetails(String type, String title, Integer status, String detail, String instance,
        String cause, List<InvalidParam> invalidParams) {
}
