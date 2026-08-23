/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Northbound API behaviour toggles (F2 — opt-in enrichment).
 *
 * <p>Default responses are CAMARA-pure ({@code devicePhoneNumberVerified} /
 * {@code devicePhoneNumber}). The SAS assurance snapshot rides along only
 * when the request carries {@code X-Sas-Assurance-Detail: true} OR the
 * deployment enables it globally via {@code sas.api.assurance-detail-enabled}.</p>
 *
 * <p>The config value deliberately uses the {@link java.util.Optional}
 * pattern with no {@code defaultValue} — a Quarkus 3.x runtime trap makes
 * {@code defaultValue = ""} fail config loading.</p>
 */
@ApplicationScoped
public class ApiTogglesConfig {

    private static final String ASSURANCE_DETAIL_HEADER = "X-Sas-Assurance-Detail";

    @ConfigProperty(name = "sas.api.assurance-detail-enabled")
    java.util.Optional<String> assuranceDetailEnabledRaw;

    /** Global opt-in from configuration; off when unset or not "true". */
    public boolean assuranceDetailEnabled() {
        return parseToggle(assuranceDetailEnabledRaw);
    }

    /**
     * True when the request opted into assurance enrichment
     * (case-insensitive {@code true} on the {@value #ASSURANCE_DETAIL_HEADER}
     * header).
     */
    public static boolean assuranceDetailRequested(String headerValue) {
        return headerValue != null && Boolean.parseBoolean(headerValue.trim());
    }

    /**
     * Effective gate: header request OR global config toggle.
     */
    public static boolean assuranceDetailRequested(String headerValue,
                                                   boolean configEnabled) {
        return configEnabled || assuranceDetailRequested(headerValue);
    }

    private static boolean parseToggle(java.util.Optional<String> raw) {
        return raw != null && raw.isPresent() && Boolean.parseBoolean(raw.get().trim());
    }
}
