/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tenant resolution + quota metering matrix: key-map parsing, fail-closed
 * unknown/missing keys under enforcement, implicit lab tenant without it,
 * unlimited/present/exhausted quotas and per-tenant independence.
 */
class TenantRegistryTest {

    private static final String KEY_MAP = "bankA=key1,bankB=key2";

    @Test
    void parsesKeyMap_resolvesTenants() {
        TenantRegistry registry = enforced(KEY_MAP);
        assertEquals("bankA", registry.resolve("key1").tenantId());
        assertEquals("bankB", registry.resolve("key2").tenantId());
    }

    @Test
    void unknownOrMissingKey_enforced_failsClosed() {
        TenantRegistry registry = enforced(KEY_MAP);
        assertNull(registry.resolve("nope"));
        assertNull(registry.resolve(null));
        assertNull(registry.resolve("   "));
    }

    @Test
    void enforcementOff_implicitLabTenantRegardlessOfKey() {
        TenantRegistry registry = new TenantRegistry();
        set(registry, "enforceApiKeys", false);
        set(registry, "apiKeysRaw", Optional.of(KEY_MAP));
        assertEquals(TenantRegistry.LAB_TENANT, registry.resolve("key1").tenantId());
        assertEquals(TenantRegistry.LAB_TENANT, registry.resolve("garbage").tenantId());
        assertEquals(TenantRegistry.LAB_TENANT, registry.resolve(null).tenantId());
    }

    @Test
    void enforcedWithBlankKeyMap_misconfiguredRejectsAll() {
        TenantRegistry registry = enforced("");
        assertNull(registry.resolve("key1"));
        assertNull(registry.resolve(null));
    }

    @Test
    void malformedEntries_skipped() {
        TenantRegistry registry = enforced("junk,bad=,,=v,bankA=key1,t=");
        assertEquals("bankA", registry.resolve("key1").tenantId());
        assertNull(registry.resolve("junk"));
        assertNull(registry.resolve(""));
    }

    @Test
    void quotaAbsent_unlimitedAndMetered() {
        TenantRegistry registry = new TenantRegistry();
        assertTrue(registry.checkAndIncrement("lab"));
        assertTrue(registry.checkAndIncrement("lab"));
        assertTrue(registry.checkAndIncrement("lab"));
        assertEquals(3L, registry.usageOf("lab"));
        assertEquals(0L, registry.usageOf("nobody"));
    }

    @Test
    void quotaPresent_exhaustedAtLimit() {
        TenantRegistry registry = new TenantRegistry() {
            @Override
            Optional<Integer> configuredQuota(String tenantId) {
                return Optional.of(2);
            }
        };
        assertTrue(registry.checkAndIncrement("bankA"));
        assertTrue(registry.checkAndIncrement("bankA"));
        assertFalse(registry.checkAndIncrement("bankA"));
        assertFalse(registry.checkAndIncrement("bankA"));
    }

    @Test
    void nonPositiveQuota_treatedUnlimited() {
        TenantRegistry registry = new TenantRegistry() {
            @Override
            Optional<Integer> configuredQuota(String tenantId) {
                return Optional.of(0);
            }
        };
        for (int i = 0; i < 5; i++) {
            assertTrue(registry.checkAndIncrement("bankA"), "attempt " + i);
        }
    }

    @Test
    void tenantsMeterIndependently() {
        TenantRegistry registry = new TenantRegistry() {
            @Override
            Optional<Integer> configuredQuota(String tenantId) {
                return "bankA".equals(tenantId) ? Optional.of(1) : Optional.empty();
            }
        };
        assertTrue(registry.checkAndIncrement("bankA"));
        assertFalse(registry.checkAndIncrement("bankA"));
        assertTrue(registry.checkAndIncrement("bankB"));
        assertTrue(registry.checkAndIncrement("bankB"));
    }

    // ---- helpers ----

    private static TenantRegistry enforced(String apiKeysRaw) {
        TenantRegistry registry = new TenantRegistry();
        set(registry, "enforceApiKeys", true);
        set(registry, "apiKeysRaw",
                apiKeysRaw.isEmpty() ? Optional.<String>empty() : Optional.of(apiKeysRaw));
        return registry;
    }

    private static void set(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
