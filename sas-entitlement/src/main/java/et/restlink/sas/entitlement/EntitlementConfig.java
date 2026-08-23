/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TS.43 entitlement server configuration.
 */
@ApplicationScoped
public class EntitlementConfig {

    private static final Logger LOG = LogManager.getLogger(EntitlementConfig.class);

    /** CAMARA single-use ceiling for entitlement token TTL (seconds). */
    public static final long MAX_TOKEN_TTL_SECONDS = 300L;

    /** B5: one-shot warn when an over-ceiling TTL is clamped. */
    private static final AtomicBoolean TTL_CLAMP_WARNED = new AtomicBoolean(false);

    @ConfigProperty(name = "sas.entitlement.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sas.entitlement.hmac-secret")
    java.util.Optional<String> hmacSecret;

    @ConfigProperty(name = "sas.entitlement.token-ttl-seconds", defaultValue = "300")
    long tokenTtlSeconds;

    @ConfigProperty(name = "sas.entitlement.ciba-enabled", defaultValue = "false")
    boolean cibaEnabled;

    @ConfigProperty(name = "sas.entitlement.require-signed", defaultValue = "true")
    boolean requireSigned;

    public boolean enabled() {
        return enabled;
    }

    public String hmacSecret() {
        return hmacSecret.orElse("");
    }

    /** Effective TTL, clamped to {@link #MAX_TOKEN_TTL_SECONDS} (warn once). */
    public long tokenTtlSeconds() {
        if (tokenTtlSeconds > MAX_TOKEN_TTL_SECONDS) {
            if (TTL_CLAMP_WARNED.compareAndSet(false, true)) {
                LOG.warn("sas.entitlement.token-ttl-seconds={} exceeds the CAMARA "
                                + "single-use ceiling of {}s — clamping",
                        tokenTtlSeconds, MAX_TOKEN_TTL_SECONDS);
            }
            return MAX_TOKEN_TTL_SECONDS;
        }
        return tokenTtlSeconds;
    }

    public boolean cibaEnabled() {
        return cibaEnabled;
    }

    public boolean requireSigned() {
        return requireSigned;
    }
}
