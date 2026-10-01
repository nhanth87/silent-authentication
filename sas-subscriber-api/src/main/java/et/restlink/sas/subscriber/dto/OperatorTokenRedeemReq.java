/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RedeemOperatorToken request (vendor extension); the token without its {@code operatortoken:} prefix. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperatorTokenRedeemReq(String afId, String operatorToken, String clientId) {
}
