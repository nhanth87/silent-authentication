/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import et.restlink.sas.model.VerifyResult;

/**
 * CAMARA NumberVerification v2.1.0 {@code POST /verify} response body.
 * Default wire shape is CAMARA-pure: exactly
 * {@code {"devicePhoneNumberVerified":boolean}}. The SAS assurance snapshot
 * (reqId/decision/assurance/fallbackReason) is opt-in (F2) via
 * {@link #from(boolean, VerifyResult, boolean)}; see
 * {@code ApiTogglesConfig}. Privacy rule: the MSISDN/IMSI is structurally
 * absent — there is no field that could carry it.
 *
 * <p>Nulls are omitted ({@code NON_NULL}) so approved responses stay compact;
 * {@code fallbackReason} appears only on fail-closed outcomes and the
 * {@code assurance} block only when a score was actually computed.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyResponseDto(
        boolean devicePhoneNumberVerified,
        String reqId,
        String decision,
        Assurance assurance,
        String fallbackReason) {

    /** Legacy single-boolean shape (pre-enrichment callers). */
    public VerifyResponseDto(boolean devicePhoneNumberVerified) {
        this(devicePhoneNumberVerified, null, null, null, null);
    }

    /**
     * Enriched mapping — the opt-in detail body (assurance snapshot rides
     * along when {@code includeAssuranceDetail} is true).
     */
    public static VerifyResponseDto from(boolean devicePhoneNumberVerified,
                                         VerifyResult result,
                                         boolean includeAssuranceDetail) {
        return includeAssuranceDetail
                ? from(devicePhoneNumberVerified, result)
                : new VerifyResponseDto(devicePhoneNumberVerified);
    }

    /** Maps a terminal SAS result onto the enriched wire shape (privacy-safe). */
    public static VerifyResponseDto from(boolean devicePhoneNumberVerified, VerifyResult result) {
        Assurance assurance = null;
        if (result != null && result.hasAssurance()) {
            assurance = new Assurance(
                    result.score(),
                    result.assurance() == null ? null : result.assurance().name(),
                    result.threshold(),
                    result.riskClass(),
                    new Factors(Factor.of(result.ipBindingFresh()),
                            Factor.of(result.reachable()),
                            Factor.of(result.notSimSwapped()),
                            Factor.of(result.locationPlausible())));
        }
        return new VerifyResponseDto(
                devicePhoneNumberVerified,
                result == null ? null : result.reqId(),
                result == null ? null : result.decision(),
                assurance,
                result == null || result.fallbackReason() == null
                        ? null : result.fallbackReason().name());
    }

    /** Score/threshold snapshot with per-factor evidence. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Assurance(
            Integer score,
            String level,
            Integer threshold,
            String riskClass,
            Factors factors) {
    }

    /** The four weighted assurance factors. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Factors(
            Factor ipBindingFresh,
            Factor reachable,
            Factor notSimSwapped,
            Factor locationPlausible) {
    }

    /** Evidence value 0..1 with its configured weight. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Factor(Double value, Double weight) {

        public static Factor of(VerifyResult.Factor f) {
            return f == null ? null : new Factor(f.value(), f.weight());
        }
    }
}
