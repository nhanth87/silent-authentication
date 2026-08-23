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

    @ConfigProperty(name = "sas.security.hmac-secret")
    java.util.Optional<String> hmacSecret;

    @ConfigProperty(name = "sas.security.expected-issuer")
    java.util.Optional<String> expectedIssuer;

    @ConfigProperty(name = "sas.security.expected-audience")
    java.util.Optional<String> expectedAudience;

    @ConfigProperty(name = "sas.security.required-scopes")
    java.util.Optional<String> requiredScopesRaw;

    @ConfigProperty(name = "sas.security.clock-skew-seconds", defaultValue = "30")
    long clockSkewSeconds;

    @ConfigProperty(name = "sas.security.replay-window-seconds", defaultValue = "300")
    long replayWindowSeconds;

    @ConfigProperty(name = "sas.security.reqid-ttl-seconds", defaultValue = "600")
    long reqIdTtlSeconds;

    @ConfigProperty(name = "sas.security.api-key")
    java.util.Optional<String> apiKey;

    @ConfigProperty(name = "sas.security.enforce-api-keys", defaultValue = "false")
    boolean enforceApiKeys;

    public boolean tokenValidationEnabled() {
        return tokenValidationEnabled;
    }

    public String hmacSecret() {
        return hmacSecret.orElse("");
    }

    public String expectedIssuer() {
        return expectedIssuer.orElse("");
    }

    public String expectedAudience() {
        return expectedAudience.orElse("");
    }

    public Set<String> requiredScopes() {
        if (requiredScopesRaw == null || requiredScopesRaw.isEmpty() || requiredScopesRaw.get().isBlank()) {
            return Set.of();
        }
        return Set.of(requiredScopesRaw.get().split(","));
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

    public String apiKey() {
        return apiKey.orElse("");
    }

    public boolean enforceApiKeys() {
        return enforceApiKeys;
    }

    /** Parsed {@code sas.security.api-key} list; empty means disabled (lab). */
    public List<String> expectedApiKeys() {
        if (apiKey == null || apiKey.isEmpty() || apiKey.get().isBlank()) {
            return List.of();
        }
        return List.of(apiKey.get().split(",")).stream()
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .toList();
    }
}
