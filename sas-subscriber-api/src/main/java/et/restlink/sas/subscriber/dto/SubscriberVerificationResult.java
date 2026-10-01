/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What one successful redemption proves (ADR R5). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriberVerificationResult(String msisdn, String authMethod, String authenticatedAt, Boolean simVerified, String tokenId) {
}
