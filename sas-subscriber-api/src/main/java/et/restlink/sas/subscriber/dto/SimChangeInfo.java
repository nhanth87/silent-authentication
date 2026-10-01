/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Latest SIM change and where the evidence came from (internal). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimChangeInfo(String msisdn, String latestSimChange, String eventTime, String evidenceSource) {
}
