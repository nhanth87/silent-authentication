/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * TS.43 entitlement server configuration.
 */
@ApplicationScoped
public class EntitlementConfig {

    @ConfigProperty(name = "sas.entitlement.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sas.entitlement.hmac-secret", defaultValue = "")
    String hmacSecret;

    @ConfigProperty(name = "sas.entitlement.token-ttl-seconds", defaultValue = "300")
    long tokenTtlSeconds;

    @ConfigProperty(name = "sas.entitlement.ciba-enabled", defaultValue = "false")
    boolean cibaEnabled;

    public boolean enabled() {
        return enabled;
    }

    public String hmacSecret() {
        return hmacSecret;
    }

    public long tokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public boolean cibaEnabled() {
        return cibaEnabled;
    }
}
