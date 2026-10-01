/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One-time, immediate monitoring request — TS 29.122 field model, synchronous profile. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonitoringEventSubscription(String msisdn, String monitoringType, List<String> addnMonTypes, String locationType,
        Integer maximumNumberOfReports, Boolean immediateRep) {
}
