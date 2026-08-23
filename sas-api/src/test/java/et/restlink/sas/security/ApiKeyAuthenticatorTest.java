/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-key check helper tests: enforcement off = lab-open, enforcement on =
 * fail-closed (misconfigured blank key list rejects too).
 */
class ApiKeyAuthenticatorTest {

    private ApiKeyAuthenticator authenticator;
    private SasSecurityConfig config;

    @BeforeEach
    void setUp() {
        authenticator = new ApiKeyAuthenticator();
        config = new SasSecurityConfig();
        try {
            var field = ApiKeyAuthenticator.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(authenticator, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void enforcementDisabled_openForLab() throws Exception {
        set(config, "enforceApiKeys", false);
        set(config, "apiKey", "secret-key");
        assertNull(authenticator.validate("wrong-key"), "lab mode accepts anything");
        assertNull(authenticator.validate(null));
        assertNull(authenticator.validate(""));
    }

    @Test
    void enforcementDisabled_evenBlankKeyList_openForLab() throws Exception {
        set(config, "enforceApiKeys", false);
        set(config, "apiKey", "");
        assertNull(authenticator.validate(null));
    }

    @Test
    void enabled_matchingKey_passes() throws Exception {
        set(config, "enforceApiKeys", true);
        set(config, "apiKey", "secret-key");
        assertNull(authenticator.validate("secret-key"));
    }

    @Test
    void enabled_multiKeyList_anyMatchPasses() throws Exception {
        set(config, "enforceApiKeys", true);
        set(config, "apiKey", "bank-a-key, bank-b-key ,bank-c-key");
        assertNull(authenticator.validate("bank-a-key"));
        assertNull(authenticator.validate("bank-b-key"), "whitespace around keys trimmed");
        assertNull(authenticator.validate("bank-c-key"));
    }

    @Test
    void enabled_missingOrWrongKey_rejected() throws Exception {
        set(config, "enforceApiKeys", true);
        set(config, "apiKey", "secret-key");
        assertNotNull(authenticator.validate(null));
        assertNotNull(authenticator.validate(""));
        assertNotNull(authenticator.validate("   "));
        assertNotNull(authenticator.validate("wrong-key"));
        assertNotNull(authenticator.validate("secret-key "));
        assertNotNull(authenticator.validate("SECRET-KEY"));
    }

    @Test
    void enabled_blankExpectedKeyList_failsClosed() throws Exception {
        set(config, "enforceApiKeys", true);
        set(config, "apiKey", "");
        assertNotNull(authenticator.validate("anything"),
                "misconfigured enforcement must reject (fail-closed)");
        assertNotNull(authenticator.validate(null));
    }

    private static void set(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == java.util.Optional.class) {
            field.set(obj, java.util.Optional.ofNullable((String) value));
        } else {
            field.set(obj, value);
        }
    }
}
