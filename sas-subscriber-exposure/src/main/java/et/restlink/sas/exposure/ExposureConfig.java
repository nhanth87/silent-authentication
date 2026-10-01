/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.exposure;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Exposure surface switches. Off by default: until migration step M4 adds mTLS and the
 * client-credentials token, the surface has no client authentication, so it may only be
 * on in the lab profile (prod preflight refuses it).
 */
@ApplicationScoped
public class ExposureConfig {

    @ConfigProperty(name = "sas.exposure.enabled", defaultValue = "false")
    boolean enabled;

    /** Which verifier answers monitoring: {@code map} (PSI) or {@code s6a} (ULR + Sh). */
    @ConfigProperty(name = "sas.subscriber.monitoring-via", defaultValue = "map")
    String monitoringVia;

    public boolean enabled() {
        return enabled;
    }

    public boolean monitoringViaS6a() {
        return "s6a".equalsIgnoreCase(monitoringVia == null ? "" : monitoringVia.trim());
    }
}
