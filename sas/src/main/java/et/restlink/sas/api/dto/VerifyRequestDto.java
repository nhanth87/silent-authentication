/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api.dto;

/**
 * CAMARA NumberVerification v2.1.0 {@code POST /verify} request body.
 * Exactly one of {@code phoneNumber} / {@code hashedPhoneNumber} must be
 * present ({@code minProperties:1, maxProperties:1}).
 */
public record VerifyRequestDto(String phoneNumber, String hashedPhoneNumber) {
}