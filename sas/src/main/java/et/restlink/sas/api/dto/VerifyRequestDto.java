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
 * present ({@code minProperties:1, maxProperties:1}).
 *
 * <p>{@code riskClass} is an optional extension: one of {@code LOGIN},
 * {@code TRANSFER}, {@code HIGH_VALUE}, case-insensitive. Absent or
 * unparseable values yield {@code null} from {@link #parse(String)}; callers
 * decide to reject or default (the SAS itself defaults null → LOGIN).</p>
 */
public record VerifyRequestDto(String phoneNumber,
                               String hashedPhoneNumber,
                               String riskClass) {

    public VerifyRequestDto(String phoneNumber, String hashedPhoneNumber) {
        this(phoneNumber, hashedPhoneNumber, null);
    }

    /**
     * Case-insensitive risk-class parse. Returns {@code null} for
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
