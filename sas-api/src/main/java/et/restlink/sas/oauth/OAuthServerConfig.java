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

@ApplicationScoped
public class OAuthServerConfig {

    @Inject
    @ConfigProperty(name = "sas.oauth.secret")
    Optional<String> secret;

    @Inject
    @ConfigProperty(name = "sas.oauth.issuer")
    Optional<String> issuer;

    @Inject
    @ConfigProperty(name = "sas.oauth.public-base-url")
    Optional<String> publicBaseUrl;

    @Inject
    @ConfigProperty(name = "sas.oauth.jwks-json")
    Optional<String> jwksJson;

    @Inject
    @ConfigProperty(name = "sas.oauth.clients-json")
    Optional<String> clientsJson;

    @Inject
    @ConfigProperty(name = "sas.oauth.require-client-auth", defaultValue = "false")
    Optional<Boolean> requireClientAuth;

    /** Access/ID-token signing algorithm: ES256 (JWKS-verifiable) or HS256 (lab legacy). */
    @Inject
    @ConfigProperty(name = "sas.oauth.signing-alg", defaultValue = "HS256")
    Optional<String> signingAlg;

    /** Private P-256 JWK (JSON) used for ES256. Prod: from the environment/secret store. */
    @Inject
    @ConfigProperty(name = "sas.oauth.signing-jwk")
    Optional<String> signingJwk;

    /** Retired public keys still published for verification during a rotation (JWKS JSON). */
    @Inject
    @ConfigProperty(name = "sas.oauth.previous-jwks")
    Optional<String> previousJwks;

    /** Lab only: mint a random ES256 key at start when none is configured. */
    @Inject
    @ConfigProperty(name = "sas.oauth.allow-ephemeral-signing-key", defaultValue = "false")
    Optional<Boolean> allowEphemeralSigningKey;

    /** Secret for pairwise ID-token subjects (OIDC Core §8.1). Prod: required. */
    @Inject
    @ConfigProperty(name = "sas.oauth.pairwise-secret")
    Optional<String> pairwiseSecret;

    public String signingAlg() {
        String alg = value(signingAlg, "HS256");
        return alg.toUpperCase(java.util.Locale.ROOT);
    }

    public Optional<String> signingJwk() {
        return normalized(signingJwk);
    }

    public Optional<String> previousJwks() {
        return normalized(previousJwks);
    }

    public boolean allowEphemeralSigningKey() {
        return allowEphemeralSigningKey != null && allowEphemeralSigningKey.orElse(false);
    }

    public Optional<String> pairwiseSecret() {
        return normalized(pairwiseSecret);
    }

    public String secret() {
        return value(secret, "");
    }

    public String issuer() {
        String configuredIssuer = value(issuer, null);
        if (configuredIssuer != null) {
            return configuredIssuer;
        }
        String configuredBase = value(publicBaseUrl, null);
        if (configuredBase != null) {
            return stripTrailingSlash(configuredBase);
        }
        return AccessTokenService.ISSUER;
    }

    public Optional<String> publicBaseUrl() {
        return normalized(publicBaseUrl);
    }

    public Optional<String> jwksJson() {
        return normalized(jwksJson);
    }

    public Optional<String> clientsJson() {
        return normalized(clientsJson);
    }

    public boolean requireClientAuth() {
        return requireClientAuth != null && requireClientAuth.orElse(false);
    }

    private static Optional<String> normalized(Optional<String> value) {
        String resolved = value(value, null);
        return resolved == null ? Optional.empty() : Optional.of(resolved);
    }

    private static String value(Optional<String> value, String fallback) {
        if (value == null || value.isEmpty() || value.get().isBlank()) {
            return fallback;
        }
        return value.get().trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
