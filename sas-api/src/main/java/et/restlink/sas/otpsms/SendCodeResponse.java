/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

/**
 * CAMARA OneTimePasswordSMS v1.1.1 {@code POST /send-code} response body
 * ({@code SendCodeResponse}): the id of the verification attempt the bank must
 * present to {@code /validate-code}. The OTP itself is never returned — it only
 * ever travels over the operator SMS channel.
 */
public record SendCodeResponse(String authenticationId) {
}
