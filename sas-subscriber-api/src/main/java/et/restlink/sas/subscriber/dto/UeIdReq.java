/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RetrieveUeId request — modelled on TS 29.522 {@code UeIdReq}; {@code observedAt} is an extension. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UeIdReq(String afId, IpAddr ueIpAddr, Integer portNumber, String observedAt, String dnn) {
}
