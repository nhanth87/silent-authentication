/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * API-key check helper for machine-to-machine endpoints (no servlet filter —
 * resources are plain JAX-RS classes and call {@link #validate} directly).
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Client sends {@code X-Api-Key: <key>}.</li>
 *   <li>Expected keys come from {@code sas.security.api-key} (comma-separated
 *       list). Blank means DISABLED for lab.</li>
 *   <li>{@code sas.security.enforce-api-keys=false} (default) → open (lab).</li>
 *   <li>{@code sas.security.enforce-api-keys=true} → fail-closed: blank
 *       expected-key config (misconfiguration) or missing/mismatched header
 *       rejects with {@code 401 UNAUTHENTICATED}.</li>
 * </ul>
 */
@ApplicationScoped
public class ApiKeyAuthenticator {

    private static final Logger LOG = LogManager.getLogger(ApiKeyAuthenticator.class);

    @Inject
    SasSecurityConfig config;

    /**
     * Check a presented key against the configured keys.
     *
     * @return null when authorized, or a rejection reason (map to
     *         {@code 401 UNAUTHENTICATED}).
     */
    public String validate(String presentedKey) {
        if (!config.enforceApiKeys()) {
            return null;
        }
        List<String> expected = config.expectedApiKeys();
        if (expected.isEmpty()) {
            LOG.error("sas.security.enforce-api-keys=true but sas.security.api-key is blank — rejecting all");
            return "api key authentication is misconfigured";
        }
        if (presentedKey == null || presentedKey.isBlank()) {
            return "missing X-Api-Key header";
        }
        byte[] actual = presentedKey.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (String key : expected) {
            // Constant-time per candidate; accumulate to avoid list-length leaks.
            match |= MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), actual);
        }
        if (!match) {
            LOG.warn("API key mismatch");
            return "invalid api key";
        }
        return null;
    }
}
