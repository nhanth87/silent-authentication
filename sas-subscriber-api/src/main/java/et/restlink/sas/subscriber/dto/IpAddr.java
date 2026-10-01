/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** UE IP address (TS 29.571 IpAddr, without the IPv6-prefix alternative). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IpAddr(String ipv4Addr, String ipv6Addr) {
}
