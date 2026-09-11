/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
public class OAuthMetadataService {

    public static final String OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration";
    public static final String AUTHORIZATION_SERVER_METADATA_PATH =
            "/.well-known/oauth-authorization-server";
    public static final String JWKS_PATH = "/jwks.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_JWKS_JSON = "{\"keys\":[]}";

    @Inject
    OAuthServerConfig config;

    public Map<String, Object> metadata(UriInfo uriInfo) {
        String issuer = issuer();
        String base = baseUrl(issuer, uriInfo);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer);
        metadata.put("token_endpoint", url(base, "/token"));
        metadata.put("jwks_uri", url(base, JWKS_PATH));
        metadata.put("backchannel_authentication_endpoint", url(base, "/bc-authorize"));
        metadata.put("scopes_supported", scopesSupported());
        metadata.put("grant_types_supported", List.of(
                TokenResource.CIBA_GRANT_TYPE,
                TokenResource.CLIENT_CREDENTIALS_GRANT_TYPE,
                TokenResource.JWT_BEARER_GRANT_TYPE));
        metadata.put("token_endpoint_auth_methods_supported", tokenEndpointAuthMethodsSupported());
        metadata.put("token_endpoint_auth_signing_alg_values_supported",
                JsonWebKey.SUPPORTED_SIGNATURE_ALGORITHMS);
        metadata.put("backchannel_token_delivery_modes_supported", List.of("poll"));
        metadata.put("backchannel_user_code_parameter_supported", false);
        metadata.put("id_token_signing_alg_values_supported", List.of("HS256"));
        metadata.put("request_parameter_supported", false);
        metadata.put("require_request_uri_registration", false);
        return metadata;
    }

    public Map<String, Object> jwks() {
        String configured = configuredJwksJson();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(configured, Map.class);
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("invalid sas.oauth.jwks-json", e);
        }
    }

    public String issuer() {
        return config == null ? AccessTokenService.ISSUER : config.issuer();
    }

    public String tokenEndpoint(UriInfo uriInfo) {
        return url(baseUrl(issuer(), uriInfo), "/token");
    }

    public String backchannelEndpoint(UriInfo uriInfo) {
        return url(baseUrl(issuer(), uriInfo), "/bc-authorize");
    }

    public Set<String> tokenEndpointAudiences(UriInfo uriInfo) {
        return audiences(tokenEndpoint(uriInfo), issuer());
    }

    public Set<String> backchannelEndpointAudiences(UriInfo uriInfo) {
        return audiences(backchannelEndpoint(uriInfo), issuer());
    }

    private static Set<String> audiences(String endpoint, String issuer) {
        Set<String> values = new LinkedHashSet<>();
        values.add(endpoint);
        if (issuer != null && !issuer.isBlank()) {
            values.add(issuer);
            values.add(stripTrailingSlash(issuer));
        }
        return values;
    }

    private String baseUrl(String issuer, UriInfo uriInfo) {
        String configured = config == null ? null : config.publicBaseUrl().orElse(null);
        if (configured != null) {
            return normalizeBaseUrl(configured);
        }
        if (issuer.startsWith("http://") || issuer.startsWith("https://")) {
            return normalizeBaseUrl(issuer);
        }
        if (uriInfo != null && uriInfo.getBaseUri() != null) {
            return normalizeBaseUrl(uriInfo.getBaseUri().toString());
        }
        return "/";
    }

    private String configuredJwksJson() {
        String configured = config == null ? null : config.jwksJson().orElse(null);
        return configured == null ? DEFAULT_JWKS_JSON : configured;
    }

    private List<String> tokenEndpointAuthMethodsSupported() {
        if (config != null && config.requireClientAuth()) {
            return List.of(OAuthClient.AUTH_METHOD_PRIVATE_KEY_JWT);
        }
        return List.of(OAuthClient.AUTH_METHOD_PRIVATE_KEY_JWT, "none");
    }

    private static List<String> scopesSupported() {
        List<String> scopes = new ArrayList<>();
        scopes.add(OAuthScopePolicy.SCOPE_OPENID);
        scopes.add(OAuthScopePolicy.SCOPE_OFFLINE_ACCESS);
        scopes.add(OAuthScopePolicy.PURPOSE_FRAUD_PREVENTION);
        scopes.addAll(new TreeSet<>(OAuthScopePolicy.supportedApiScopes()));
        return List.copyOf(scopes);
    }

    private static String url(String base, String path) {
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
