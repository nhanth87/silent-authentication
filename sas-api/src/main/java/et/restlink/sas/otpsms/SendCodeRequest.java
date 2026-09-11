/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * CAMARA OneTimePasswordSMS v1.1.1 {@code POST /send-code} request body
 * ({@code SendCodeBody}). Both fields are required by the spec: {@code message}
 * is the SMS template and MUST carry the {@code {{code}}} label where the OTP
 * goes (≤160 characters — one GSM segment). Unknown properties are rejected as
 * {@code 400 INVALID_ARGUMENT}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SendCodeRequest(String phoneNumber, String message) {
}
