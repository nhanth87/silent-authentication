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
