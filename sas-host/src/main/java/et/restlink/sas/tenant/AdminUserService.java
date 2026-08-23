/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.tenant;

import et.restlink.sas.persist.SasAdminUserEntity;
import et.restlink.sas.security.PasswordHasher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdminUserService {
    @ConfigProperty(name = "sas.admin.password.bcrypt-cost", defaultValue = "10")
    int bcryptCost = PasswordHasher.DEFAULT_COST;

    @Transactional
    public List<SasAdminUserEntity> list() {
        return SasAdminUserEntity.listAll();
    }

    @Transactional
    public Optional<SasAdminUserEntity> byUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return SasAdminUserEntity.findByIdOptional(username.trim());
    }

    @Transactional
    public SasAdminUserEntity create(String username, String password, String role,
                                     String tenantId, String displayName, boolean enabled) {
        String u = username.trim();
        if (SasAdminUserEntity.findById(u) != null) {
            throw new IllegalArgumentException("username already exists: " + u);
        }
        SasAdminUserEntity e = new SasAdminUserEntity();
        e.username = u;
        e.passwordHash = PasswordHasher.hash(password, bcryptCost);
        e.role = normalizeRole(role);
        e.tenantId = blank(tenantId);
        enforceTenantUsername(e.role, u, e.tenantId);
        e.displayName = blank(displayName);
        e.enabled = enabled;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        e.persist();
        return e;
    }

    @Transactional
    public SasAdminUserEntity update(String username, String passwordOrBlank, String role,
                                     String tenantId, String displayName, boolean enabled) {
        SasAdminUserEntity e = SasAdminUserEntity.findById(username.trim());
        if (e == null) throw new IllegalArgumentException("user not found: " + username);
        if (passwordOrBlank != null && !passwordOrBlank.isBlank()) {
            e.passwordHash = PasswordHasher.hash(passwordOrBlank, bcryptCost);
        }
        e.role = normalizeRole(role);
        e.tenantId = blank(tenantId);
        enforceTenantUsername(e.role, e.username, e.tenantId);
        e.displayName = blank(displayName);
        e.enabled = enabled;
        e.updatedAt = Instant.now();
        return e;
    }

    @Transactional
    public boolean delete(String username) {
        return SasAdminUserEntity.deleteById(username);
    }

    @Transactional
    public boolean authenticate(String username, String password) {
        Optional<SasAdminUserEntity> opt = byUsername(username);
        if (opt.isEmpty() || !opt.get().enabled) return false;
        SasAdminUserEntity e = opt.get();
        if (!PasswordHasher.matches(password, e.passwordHash)) return false;
        if (PasswordHasher.needsRehash(e.passwordHash)) {
            e.passwordHash = PasswordHasher.hash(password, bcryptCost);
            e.updatedAt = Instant.now();
        }
        return true;
    }

    static void enforceTenantUsername(String role, String username, String tenantId) {
        if ("TENANT".equals(role) && (tenantId == null || !tenantId.equals(username))) {
            throw new IllegalArgumentException("TENANT username must equal tenantId");
        }
    }

    private static String normalizeRole(String role) {
        String r = role == null ? "OPS" : role.trim().toUpperCase();
        if (!r.equals("ADMIN") && !r.equals("OPS") && !r.equals("TENANT")) {
            throw new IllegalArgumentException("role must be ADMIN|OPS|TENANT");
        }
        return r;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}