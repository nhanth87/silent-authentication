/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.tenant;

import et.restlink.sas.persist.SasTenantEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TenantService {
    @Transactional
    public List<SasTenantEntity> list() {
        return SasTenantEntity.listAll();
    }

    @Transactional
    public Optional<SasTenantEntity> byId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        return SasTenantEntity.findByIdOptional(tenantId.trim());
    }

    @Transactional
    public Optional<SasTenantEntity> byAdminApiKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return SasTenantEntity.find("adminApiKey = ?1 and enabled = true", key.trim())
                .firstResultOptional();
    }

    @Transactional
    public SasTenantEntity upsert(String tenantId, String displayName, int networkId,
                                  boolean enabled, String adminApiKey) {
        String id = tenantId.trim();
        SasTenantEntity e = SasTenantEntity.findById(id);
        Instant now = Instant.now();
        if (e == null) {
            e = new SasTenantEntity();
            e.tenantId = id;
            e.createdAt = now;
        }
        e.displayName = blank(displayName);
        e.networkId = resolveNetworkId(id, networkId);
        e.enabled = enabled;
        if (adminApiKey != null && !adminApiKey.isBlank()) {
            e.adminApiKey = adminApiKey.trim();
        }
        e.updatedAt = now;
        e.persist();
        return e;
    }

    @Transactional
    public boolean delete(String tenantId) {
        return SasTenantEntity.deleteById(tenantId);
    }

    private int resolveNetworkId(String tenantId, int requested) {
        if (requested <= 0) {
            return nextFreeNetworkId();
        }
        long clash = SasTenantEntity.count("networkId = ?1 and tenantId != ?2", requested, tenantId);
        if (clash > 0) {
            throw new IllegalArgumentException("networkId already in use: " + requested);
        }
        return requested;
    }

    private int nextFreeNetworkId() {
        int id = 1;
        while (SasTenantEntity.count("networkId = ?1", id) > 0) {
            id++;
        }
        return id;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}