/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 opt-in enrichment toggles: config gate (default off) + header parse.
 */
class ApiTogglesConfigTest {

    private static void setRaw(ApiTogglesConfig cfg, String value) throws Exception {
        var field = ApiTogglesConfig.class.getDeclaredField("assuranceDetailEnabledRaw");
        field.setAccessible(true);
        field.set(cfg, java.util.Optional.ofNullable(value));
    }

    @Test
    void config_default_off() {
        assertFalse(new ApiTogglesConfig().assuranceDetailEnabled());
    }

    @Test
    void config_true_on_falseAndGarbage_off() throws Exception {
        ApiTogglesConfig on = new ApiTogglesConfig();
        setRaw(on, "true");
        assertTrue(on.assuranceDetailEnabled());

        ApiTogglesConfig upper = new ApiTogglesConfig();
        setRaw(upper, "TRUE");
        assertTrue(upper.assuranceDetailEnabled());

        ApiTogglesConfig off = new ApiTogglesConfig();
        setRaw(off, "false");
        assertFalse(off.assuranceDetailEnabled());

        ApiTogglesConfig garbage = new ApiTogglesConfig();
        setRaw(garbage, "yes-please");
        assertFalse(garbage.assuranceDetailEnabled());
    }

    @Test
    void header_parse_caseInsensitiveTrueOnly() {
        assertTrue(ApiTogglesConfig.assuranceDetailRequested("true"));
        assertTrue(ApiTogglesConfig.assuranceDetailRequested(" TRUE "));
        assertFalse(ApiTogglesConfig.assuranceDetailRequested("false"));
        assertFalse(ApiTogglesConfig.assuranceDetailRequested("1"));
        assertFalse(ApiTogglesConfig.assuranceDetailRequested(""));
        assertFalse(ApiTogglesConfig.assuranceDetailRequested(null));
    }

    @Test
    void effectiveGate_headerOrConfig() {
        // Neither → off.
        assertFalse(ApiTogglesConfig.assuranceDetailRequested(null, false));
        // Header alone → on; config alone → on.
        assertTrue(ApiTogglesConfig.assuranceDetailRequested("true", false));
        assertTrue(ApiTogglesConfig.assuranceDetailRequested(null, true));
        assertTrue(ApiTogglesConfig.assuranceDetailRequested("true", true));
        // Junk header with config off stays off.
        assertFalse(ApiTogglesConfig.assuranceDetailRequested("junk", false));
    }
}
