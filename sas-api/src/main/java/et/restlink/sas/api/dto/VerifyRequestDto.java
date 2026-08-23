/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api.dto;

import et.restlink.sas.fsm.AssurancePolicy;

import java.util.Locale;

/**
 * CAMARA NumberVerification v2.1.0 {@code POST /verify} request body.
 * Exactly one of {@code phoneNumber} / {@code hashedPhoneNumber} must be
 * present ({@code minProperties:1, maxProperties:1}). Unknown properties are
 * ignored, never parsed (spec request-body strictness — F2/F4: the former
 * {@code riskClass} extension moved to the {@code X-Sas-Risk-Class} header).
 */
public record VerifyRequestDto(String phoneNumber,
                               String hashedPhoneNumber) {

    /**
     * Case-insensitive risk-class parse (used for the
     * {@code X-Sas-Risk-Class} header). Returns {@code null} for
     * {@code null}/blank/unparseable input.
     */
    public static AssurancePolicy.RiskClass parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AssurancePolicy.RiskClass.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
