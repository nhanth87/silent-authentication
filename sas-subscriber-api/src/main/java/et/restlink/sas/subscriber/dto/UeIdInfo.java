/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RetrieveUeId answer — {@code gpsi} replaces the 3GPP {@code externalId} (contract x-deviation, ADR R6). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UeIdInfo(String gpsi, String bindingObservedAt, String evidenceSource) {
}
