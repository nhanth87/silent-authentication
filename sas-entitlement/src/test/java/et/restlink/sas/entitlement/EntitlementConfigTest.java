/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B5: the effective entitlement token TTL is clamped to the CAMARA
 * single-use ceiling ({@code 300s}); values at or below pass through.
 */
class EntitlementConfigTest {

    @Test
    void ttlAboveCeiling_clampedTo300() throws Exception {
        assertEquals(300L, configWithTtl(600L).tokenTtlSeconds());
        assertEquals(300L, configWithTtl(86_400L).tokenTtlSeconds());
    }

    @Test
    void ttlAtOrBelowCeiling_unchanged() throws Exception {
        assertEquals(300L, configWithTtl(300L).tokenTtlSeconds());
        assertEquals(60L, configWithTtl(60L).tokenTtlSeconds());
        assertEquals(0L, configWithTtl(0L).tokenTtlSeconds());
    }

    private static EntitlementConfig configWithTtl(long ttlSeconds) throws Exception {
        EntitlementConfig c = new EntitlementConfig();
        var field = EntitlementConfig.class.getDeclaredField("tokenTtlSeconds");
        field.setAccessible(true);
        field.setLong(c, ttlSeconds);
        return c;
    }
}
