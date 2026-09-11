/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.security.TokenValidator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OAuthScopePolicy {

    public static final String SCOPE_OPENID = "openid";
    public static final String SCOPE_OFFLINE_ACCESS = "offline_access";
    public static final String PURPOSE_PREFIX = "dpv:";
    public static final String PURPOSE_FRAUD_PREVENTION = "dpv:FraudPreventionAndDetection";

    private static final Set<String> API_SCOPES = Set.of(
            TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY,
            TokenValidator.SCOPE_NUMBER_VERIFICATION_DEVICE_PHONE_NUMBER_READ,
            TokenValidator.SCOPE_SIM_SWAP_CHECK,
            TokenValidator.SCOPE_SIM_SWAP_RETRIEVE_DATE,
            TokenValidator.SCOPE_ONE_TIME_PASSWORD_SMS_SEND_VALIDATE);

    private OAuthScopePolicy() {
    }

    public record ScopeGrant(
            Set<String> apiScopes,
            String purpose,
            boolean openid,
            boolean offlineAccess) {

        public Set<String> responseScopes() {
            Set<String> response = new LinkedHashSet<>();
            if (purpose != null) {
                response.add(purpose);
            }
            response.addAll(apiScopes);
            return Collections.unmodifiableSet(response);
        }
    }

    public static ScopeGrant parse(String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            throw CibaException.invalidRequest("scope is required");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String token : requestedScope.trim().split("\\s+")) {
            if (!token.isBlank()) {
                requested.add(token);
            }
        }
        if (requested.isEmpty()) {
            throw CibaException.invalidRequest("scope is required");
        }

        boolean openid = false;
        boolean offlineAccess = false;
        String purpose = null;
        Set<String> apiScopes = new LinkedHashSet<>();
        for (String scope : requested) {
            if (SCOPE_OPENID.equals(scope)) {
                openid = true;
                continue;
            }
            if (SCOPE_OFFLINE_ACCESS.equals(scope)) {
                offlineAccess = true;
                continue;
            }
            if (scope.startsWith(PURPOSE_PREFIX)) {
                if (scope.length() == PURPOSE_PREFIX.length()) {
                    throw CibaException.invalidScope("dpv purpose value is required");
                }
                if (purpose != null) {
                    throw CibaException.invalidScope("exactly one dpv purpose scope is required");
                }
                purpose = scope;
                continue;
            }
            if (API_SCOPES.contains(scope)) {
                apiScopes.add(scope);
                continue;
            }
            throw CibaException.invalidScope("unsupported scope: " + scope);
        }
        if (apiScopes.isEmpty()) {
            throw CibaException.invalidScope("at least one CAMARA API scope is required");
        }
        return new ScopeGrant(Collections.unmodifiableSet(apiScopes), purpose, openid, offlineAccess);
    }

    public static Set<String> supportedApiScopes() {
        return API_SCOPES;
    }
}
