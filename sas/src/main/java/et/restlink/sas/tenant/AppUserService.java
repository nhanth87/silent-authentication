/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.tenant;

import et.restlink.sas.persist.SasAppUserEntity;
import et.restlink.sas.persist.SasTenantEntity;
import et.restlink.sas.security.ApiKeyGenerator;
import et.restlink.sas.security.PasswordHasher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AppUserService {
    @Inject TenantService tenants;

    public record Created(String username, String tenantId, int networkId, String apiKey) {}

    @Transactional
    public List<SasAppUserEntity> list() {
        return SasAppUserEntity.listAll();
    }

    @Transactional
    public Optional<SasAppUserEntity> byUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return SasAppUserEntity.findByIdOptional(username.trim());
    }

    @Transactional
    public Created create(String username, String tenantId) {
        String u = username.trim();
        String tid = tenantId.trim();
        if (SasAppUserEntity.findById(u) != null) {
            throw new IllegalArgumentException("username already exists: " + u);
        }
        Optional<SasTenantEntity> tenant = tenants.byId(tid);
        if (tenant.isEmpty()) {
            throw new IllegalArgumentException("tenant not found: " + tid);
        }
        String apiKey = ApiKeyGenerator.generate();
        SasAppUserEntity e = new SasAppUserEntity();
        e.username = u;
        e.tenantId = tid;
        e.networkId = tenant.get().networkId;
        e.apiKeyHash = PasswordHasher.hash(apiKey);
        e.apiKeyFp = ApiKeyGenerator.fingerprint(apiKey);
        e.enabled = true;
        e.createdAt = Instant.now();
        e.persist();
        return new Created(u, tid, e.networkId, apiKey);
    }

    @Transactional
    public Optional<SasAppUserEntity> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        String key = apiKey.trim();
        for (SasAppUserEntity e : SasAppUserEntity.<SasAppUserEntity>list("enabled = true")) {
            if (e.apiKeyHash != null && PasswordHasher.matches(key, e.apiKeyHash)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    @Transactional
    public SasAppUserEntity update(String username, String tenantId, boolean enabled) {
        SasAppUserEntity e = SasAppUserEntity.findById(username.trim());
        if (e == null) throw new IllegalArgumentException("user not found: " + username);
        if (tenantId != null && !tenantId.isBlank()) {
            String tid = tenantId.trim();
            Optional<SasTenantEntity> tenant = tenants.byId(tid);
            if (tenant.isEmpty()) {
                throw new IllegalArgumentException("tenant not found: " + tid);
            }
            e.tenantId = tid;
            e.networkId = tenant.get().networkId;
        }
        e.enabled = enabled;
        return e;
    }

    @Transactional
    public boolean delete(String username) {
        return SasAppUserEntity.deleteById(username);
    }
}