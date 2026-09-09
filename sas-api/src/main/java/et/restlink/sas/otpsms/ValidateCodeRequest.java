/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

/**
 * CAMARA OneTimePasswordSMS v1.1.1 {@code POST /validate-code} request body
 * ({@code ValidateCodeBody}): the attempt id from {@code /send-code} plus the
 * code the subscriber received. Both are required; {@code code} is capped at
 * 10 characters and {@code authenticationId} at 36 by the spec schemas.
 */
public record ValidateCodeRequest(String authenticationId, String code) {
}
