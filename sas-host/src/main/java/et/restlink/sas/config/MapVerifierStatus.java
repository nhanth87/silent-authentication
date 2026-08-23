/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.config;

/**
 * Snapshot of the live MAP verifier wiring for the admin dashboard.
 *
 * <p>This is a pure data record — the {@code SasBootstrap} / admin handler
 * populates it from the actual backend instance. {@code m3uaRouteReady} is
 * true only when an M3UA route toward the home STP/HLR is genuinely up, never
 * inferred optimistically (fail-closed reporting).</p>
 */
public record MapVerifierStatus(
        boolean enabled,
        String backend,
        String hlrGt,
        String localGt,
        boolean m3uaRouteReady,
        String lastError) {

    /**
     * A disabled placeholder for a named backend (used before the lead wires
     * real plumbing, so the dashboard can still render the slot).
     */
    public static MapVerifierStatus of(String backend) {
        return new MapVerifierStatus(false, backend, "", "", false, "");
    }
}