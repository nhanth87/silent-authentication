/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * CAMARA-side trust configuration for operator tokens issued by an entitlement server
 * (ours — sas-ecs — or the operator's own ECS). Mode 1 (operator already has an ECS)
 * and mode 2 (sas-ecs) differ only in what is listed here.
 *
 * <pre>
 * sas.operator-token.issuers-json = {"issuers":[
 *     {"issuer":"https://ecs.example","jwks_uri":"https://ecs.example/jwks.json"},
 *     {"issuer":"https://ecs2.example","jwks":{"keys":[ ...public ES256 JWKs... ]}} ]}
 * sas.operator-token.decryption-jwk = private P-256 JWK, use=enc (our half of ECDH-ES)
 * </pre>
 */
@ApplicationScoped
public class OperatorTokenConfig {

    @Inject
    @ConfigProperty(name = "sas.operator-token.enabled", defaultValue = "true")
    Optional<Boolean> enabled;

    @Inject
    @ConfigProperty(name = "sas.operator-token.audience")
    Optional<String> audience;

    @Inject
    @ConfigProperty(name = "sas.operator-token.issuers-json")
    Optional<String> issuersJson;

    @Inject
    @ConfigProperty(name = "sas.operator-token.decryption-jwk")
    Optional<String> decryptionJwk;

    @Inject
    @ConfigProperty(name = "sas.operator-token.require-encryption", defaultValue = "true")
    Optional<Boolean> requireEncryption;

    @Inject
    @ConfigProperty(name = "sas.operator-token.header-enabled", defaultValue = "false")
    Optional<Boolean> headerEnabled;

    @Inject
    @ConfigProperty(name = "sas.operator-token.clock-skew-seconds", defaultValue = "30")
    Optional<Long> clockSkewSeconds;

    @Inject
    @ConfigProperty(name = "sas.operator-token.jwks-cache-seconds", defaultValue = "300")
    Optional<Long> jwksCacheSeconds;

    @Inject
    OAuthServerConfig oauth;

    public boolean enabled() {
        return enabled == null || enabled.orElse(true);
    }

    /** Our identifier as the token audience; defaults to the OAuth issuer. */
    public String audience() {
        String configured = audience == null ? null : audience.filter(v -> !v.isBlank()).orElse(null);
        if (configured != null) {
            return configured.trim();
        }
        return oauth == null ? AccessTokenService.ISSUER : oauth.issuer();
    }

    public Optional<String> issuersJson() {
        return issuersJson == null ? Optional.empty() : issuersJson.filter(v -> !v.isBlank());
    }

    public Optional<String> decryptionJwk() {
        return decryptionJwk == null ? Optional.empty() : decryptionJwk.filter(v -> !v.isBlank());
    }

    public boolean requireEncryption() {
        return requireEncryption == null || requireEncryption.orElse(true);
    }

    /** {@code X-Sas-Operator-Token} header accepted (legacy CIBA helper). */
    public boolean headerEnabled() {
        return headerEnabled != null && headerEnabled.orElse(false);
    }

    public long clockSkewSeconds() {
        return clockSkewSeconds == null ? 30L : clockSkewSeconds.orElse(30L);
    }

    public long jwksCacheSeconds() {
        return jwksCacheSeconds == null ? 300L : jwksCacheSeconds.orElse(300L);
    }
}
