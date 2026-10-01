/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Immediate reports for one request, plus the internal audit source. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitoringEventReports(List<MonitoringEventReport> monitoringEventReports, String evidenceSource) {
}
