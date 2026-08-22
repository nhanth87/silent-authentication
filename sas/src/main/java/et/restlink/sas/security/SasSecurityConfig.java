/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Set;

/**
 * P1 security configuration for the bank→SAS northbound surface.
 * All values are injectable via {@code sas.security.*} properties.
 */
@ApplicationScoped
public class SasSecurityConfig {

    @ConfigProperty(name = "sas.security.token-validation-enabled", defaultValue = "false")
    boolean tokenValidationEnabled;

    @ConfigProperty(name = "sas.security.hmac-secret", defaultValue = "")
    String hmacSecret;

    @ConfigProperty(name = "sas.security.expected-issuer", defaultValue = "")
    String expectedIssuer;

    @ConfigProperty(name = "sas.security.expected-audience", defaultValue = "")
    String expectedAudience;

    @ConfigProperty(name = "sas.security.required-scopes", defaultValue = "")
    String requiredScopesRaw;

    @ConfigProperty(name = "sas.security.clock-skew-seconds", defaultValue = "30")
    long clockSkewSeconds;

    @ConfigProperty(name = "sas.security.replay-window-seconds", defaultValue = "300")
    long replayWindowSeconds;

    @ConfigProperty(name = "sas.security.reqid-ttl-seconds", defaultValue = "600")
    long reqIdTtlSeconds;

    public boolean tokenValidationEnabled() {
        return tokenValidationEnabled;
    }

    public String hmacSecret() {
        return hmacSecret;
    }

    public String expectedIssuer() {
        return expectedIssuer;
    }

    public String expectedAudience() {
        return expectedAudience;
    }

    public Set<String> requiredScopes() {
        if (requiredScopesRaw == null || requiredScopesRaw.isBlank()) {
            return Set.of();
        }
        return Set.of(requiredScopesRaw.split(","));
    }

    public long clockSkewSeconds() {
        return clockSkewSeconds;
    }

    public long replayWindowSeconds() {
        return replayWindowSeconds;
    }

    public long reqIdTtlSeconds() {
        return reqIdTtlSeconds;
    }
}
