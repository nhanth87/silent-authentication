/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api.dto;

/**
 * CAMARA NumberVerification v2.1.0 {@code POST /verify} response body.
 * A single boolean — never the MSISDN/IMSI (privacy rule H8).
 */
public record VerifyResponseDto(boolean devicePhoneNumberVerified) {
}