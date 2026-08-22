/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.admin;

import et.restlink.sas.config.SasAdminRuntimeConfig;
import et.restlink.sas.persist.SasAdminUserEntity;
import et.restlink.sas.tenant.AdminUserService;
import et.restlink.sas.tenant.TenantGuard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdminAuthService {
    private static final Logger LOG = LogManager.getLogger(AdminAuthService.class);
    public static final String ADMIN_KEY_HEADER = "X-Sas-Admin-Key";

    public record Principal(String role, String tenantId, String username, boolean fromSession) {
        public boolean isTenantScoped() {
            return "TENANT".equals(role) && tenantId != null && !tenantId.isBlank();
        }
        public boolean isAdminOrOps() {
            return "ADMIN".equals(role) || "OPS".equals(role);
        }
    }

    @Inject SasAdminRuntimeConfig config;
    @Inject TenantGuard tenantGuard;
    @Inject AdminUserService users;

    @ConfigProperty(name = "sas.admin.session-hmac-secret",
            defaultValue = "sas-dev-session-hmac-change-me")
    String sessionHmacSecret;

    public Optional<Principal> authenticate(Map<String, String> headers, Map<String, String> query) {
        try {
            return authenticate0(headers);
        } catch (RuntimeException ex) {
            LOG.error("[admin] authentication aborted: {}", ex.toString());
            return Optional.empty();
        }
    }

    public Optional<String> login(String username, String password) {
        if (username == null || password == null || !users.authenticate(username, password)) {
            return Optional.empty();
        }
        Optional<SasAdminUserEntity> u = users.byUsername(username);
        if (u.isEmpty() || !u.get().enabled) return Optional.empty();
        String role = u.get().role == null ? "OPS" : u.get().role;
        String tid = "TENANT".equals(role) ? u.get().tenantId : null;
        Instant exp = Instant.now().plus(1, ChronoUnit.DAYS);
        return Optional.of(SignedSessionCookie.issue(sessionHmacSecret, username, role, tid, exp));
    }

    public String sessionHmacSecret() {
        return sessionHmacSecret;
    }

    private Optional<Principal> authenticate0(Map<String, String> headers) {
        Optional<String> cookieTok = SignedSessionCookie.extractFromCookieHeader(
                header(headers, "Cookie"));
        if (cookieTok.isPresent()) {
            Optional<SignedSessionCookie.Claims> claims =
                    SignedSessionCookie.verify(sessionHmacSecret, cookieTok.get());
            if (claims.isPresent()) {
                SignedSessionCookie.Claims c = claims.get();
                return Optional.of(new Principal(c.role(), c.tenantId(), c.username(), true));
            }
        }
        String key = header(headers, ADMIN_KEY_HEADER);
        if (key != null && config.adminKeyOk(key)) {
            return Optional.of(new Principal("ADMIN", null, "api-key", false));
        }
        if (key != null) {
            return tenantGuard.byHttpApiKey(key.trim())
                    .map(t -> new Principal("TENANT", t.tenantId(), t.tenantId(), false));
        }
        return Optional.empty();
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        String v = headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}