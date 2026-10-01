/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Location evidence metadata: the age only — no coordinates, ECGI/NCGI, TAC or cell id (ADR R8). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationInfo(Integer ageOfLocationInfo) {
}
