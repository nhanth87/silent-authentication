/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-bank tenant resolution + quota metering (billing hooks) at the CAMARA
 * edge.
 *
 * <p><strong>Tenants</strong>: {@code sas.tenants.api-keys} maps static API
 * keys to bank tenants — {@code "bankA=key1,bankB=key2"}. The caller's
 * {@code X-Api-Key} resolves to a {@link TenantInfo}; with
 * {@code sas.security.enforce-api-keys=false} (lab) every caller is the
 * single implicit {@value #LAB_TENANT} tenant, and an unknown key under
 * enforcement fails closed ({@code null} → 401 UNAUTHENTICATED), as does a
 * blank key map while enforcement is on.</p>
 *
 * <p><strong>Quota</strong>: {@code sas.tenants.quota.<tenantId>} caps the
 * monthly verify volume per tenant; absent means unlimited. Counters are
 * in-memory and process-local — metering for billing, not a hard security
 * boundary.</p>
 */
@ApplicationScoped
public class TenantRegistry {

    private static final Logger LOG = LogManager.getLogger(TenantRegistry.class);

    /** Implicit single tenant when enforcement/key-map is off or empty. */
    public static final String LAB_TENANT = "lab";

    /** Per-tenant quota property prefix: sas.tenants.quota.&lt;tenantId&gt;. */
    static final String QUOTA_PREFIX = "sas.tenants.quota.";

    /** Resolved caller identity riding the request and the CDR. */
    public record TenantInfo(String tenantId) {}

    @ConfigProperty(name = "sas.security.enforce-api-keys", defaultValue = "false")
    boolean enforceApiKeys;

    @ConfigProperty(name = "sas.tenants.api-keys")
    Optional<String> apiKeysRaw;

    @Inject
    Config mpConfig;

    /** tenantId → lifetime verify counter (billing meter). */
    private final ConcurrentHashMap<String, AtomicLong> usage = new ConcurrentHashMap<>();

    /**
     * Resolve the caller tenant from the presented API key.
     *
     * @return the tenant, the implicit lab tenant when enforcement is off,
     *         or null when the caller must be rejected (unknown/missing key,
     *         or enforced-but-blank key map)
     */
    public TenantInfo resolve(String apiKey) {
        if (!enforceApiKeys) {
            return new TenantInfo(LAB_TENANT);
        }
        Map<String, String> byKey = parseKeyMap();
        if (byKey.isEmpty()) {
            LOG.error("sas.security.enforce-api-keys=true but sas.tenants.api-keys "
                    + "is blank — rejecting all callers");
            return null;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String tenantId = byKey.get(apiKey.trim());
        if (tenantId == null) {
            LOG.warn("X-Api-Key does not map to any tenant");
            return null;
        }
        return new TenantInfo(tenantId);
    }

    /**
     * Meter one billable attempt for the tenant.
     *
     * @return false when the monthly quota is exhausted
     */
    public boolean checkAndIncrement(String tenantId) {
        long used = usage.computeIfAbsent(tenantId, k -> new AtomicLong())
                .incrementAndGet();
        Integer quota = configuredQuota(tenantId).orElse(null);
        boolean allowed = quota == null || quota <= 0 || used <= quota;
        if (!allowed) {
            LOG.warn("Tenant {} exceeded its monthly quota ({} > {})",
                    tenantId, used, quota);
        }
        return allowed;
    }

    /** Lifetime metered attempts for the tenant (admin/billing visibility). */
    public long usageOf(String tenantId) {
        AtomicLong counter = usage.get(tenantId);
        return counter == null ? 0L : counter.get();
    }

    /** Quota seam — overridable in tests; absent/non-positive = unlimited. */
    Optional<Integer> configuredQuota(String tenantId) {
        if (mpConfig == null) {
            return Optional.empty();
        }
        return mpConfig.getOptionalValue(QUOTA_PREFIX + tenantId, Integer.class);
    }

    private Map<String, String> parseKeyMap() {
        if (apiKeysRaw == null || apiKeysRaw.isEmpty() || apiKeysRaw.get().isBlank()) {
            return Map.of();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String entry : apiKeysRaw.get().split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String tenantId = entry.substring(0, eq).trim();
            String key = entry.substring(eq + 1).trim();
            if (!tenantId.isEmpty() && !key.isEmpty()) {
                byKey.put(key, tenantId);
            }
        }
        return byKey;
    }
}
