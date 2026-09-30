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

    /**
     * Shared secret for the {@code POST /entitlement/issue} AAA attestation
     * MAC. No default value: absent stays absent (the Quarkus empty-string
     * default trap turns "unset" into "blank-but-set").
     */
    @ConfigProperty(name = "sas.entitlement.issue-attestation-secret")
    java.util.Optional<String> issueAttestationSecret;

    @ConfigProperty(name = "sas.entitlement.issue-attestation-required", defaultValue = "false")
    boolean issueAttestationRequired;

    /**
     * Token format: {@code operator-jwe} (the split-deployment profile — ES256 JWS in an
     * ECDH-ES/A256GCM JWE, verifiable by any CAMARA server that trusts this ECS) or the
     * legacy single-binary {@code hmac}.
     */
    @ConfigProperty(name = "sas.entitlement.token-format", defaultValue = "hmac")
    String tokenFormat;

    /** This entitlement server's issuer identifier ({@code iss}). */
    @ConfigProperty(name = "sas.ecs.issuer")
    java.util.Optional<String> ecsIssuer;

    /** Private P-256 JWK the ECS signs operator tokens with. Prod: from the secret store. */
    @ConfigProperty(name = "sas.ecs.signing-jwk")
    java.util.Optional<String> ecsSigningJwk;

    /** Lab only: mint a random signing key per process when none is configured. */
    @ConfigProperty(name = "sas.ecs.allow-ephemeral-signing-key", defaultValue = "false")
    boolean allowEphemeralSigningKey;

    /** The CAMARA server the tokens are for ({@code aud}). */
    @ConfigProperty(name = "sas.ecs.token.audience")
    java.util.Optional<String> tokenAudience;

    /** The CAMARA server's public P-256 encryption JWK ({@code use=enc}). */
    @ConfigProperty(name = "sas.ecs.token.recipient-enc-jwk")
    java.util.Optional<String> recipientEncJwk;

    /** Lab only: emit a bare JWS when no recipient key is configured. */
    @ConfigProperty(name = "sas.ecs.token.allow-unencrypted", defaultValue = "false")
    boolean allowUnencrypted;

    /**
     * Legacy ECS-local {@code POST /entitlement/exchange}. With operator-jwe tokens the
     * CAMARA server keeps the authoritative single-use ledger; an ECS-local redeem is a
     * second, independent consumer — the same token could then be spent once here and
     * once at CAMARA. Lab: on for the old demos. Prod: off (preflight PRO-22).
     */
    @ConfigProperty(name = "sas.entitlement.legacy-exchange-enabled", defaultValue = "true")
    boolean legacyExchangeEnabled;

    public boolean legacyExchangeEnabled() {
        return legacyExchangeEnabled;
    }

    public boolean operatorJweFormat() {
        return "operator-jwe".equalsIgnoreCase(tokenFormat == null ? "" : tokenFormat.trim());
    }

    public String ecsIssuer() {
        return ecsIssuer == null ? "" : ecsIssuer.orElse("").trim();
    }

    public java.util.Optional<String> ecsSigningJwk() {
        return ecsSigningJwk == null ? java.util.Optional.empty()
                : ecsSigningJwk.filter(v -> !v.isBlank());
    }

    public boolean allowEphemeralSigningKey() {
        return allowEphemeralSigningKey;
    }

    public String tokenAudience() {
        return tokenAudience == null ? "" : tokenAudience.orElse("").trim();
    }

    public java.util.Optional<String> recipientEncJwk() {
        return recipientEncJwk == null ? java.util.Optional.empty()
                : recipientEncJwk.filter(v -> !v.isBlank());
    }

    public boolean allowUnencrypted() {
        return allowUnencrypted;
    }

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

    /** Attestation MAC secret; empty = not configured. */
    public String issueAttestationSecret() {
        return issueAttestationSecret == null ? "" : issueAttestationSecret.orElse("");
    }

    public boolean issueAttestationRequired() {
        return issueAttestationRequired;
    }
}
