/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One immediate report; {@code ueReachable} is an extension. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitoringEventReport(String monitoringType, String msisdn, String eventTime, Boolean ueReachable,
        PlmnId plmnId, LocationInfo locationInfo) {
}
