/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector;

import java.util.concurrent.CompletableFuture;

/**
 * Where the entitlement service gets its EAP-AKA vectors (plan Phase 1b, D6 Shape S).
 *
 * <p>Implementations live under {@code /ras/} — gate H24 forbids a jSS7 or Diameter
 * client anywhere else, and this is the only place that fetches authentication
 * vectors for the whole SAS. Note the deliberate asymmetry with the verify path:
 * {@code /verify} never consumes vectors, so the {@code AGENTS.md} §5 "no AIR on the
 * verify path" rule stays intact.</p>
 *
 * <p>Both methods complete on the RA's own 2 s budget
 * ({@link et.restlink.sas.fsm.SasTimeouts#DIAMETER_MS}); the RA aborts the Diameter
 * session on expiry. A failed future is always a refusal, never a partial vector.</p>
 */
public interface AuthVectorBackend {

    /** Own-HSS-only invariant: vectors come from the operator, never interconnect. */
    boolean NO_INTERCONNECT_VECTORS = true;

    /**
     * Fetch one fresh EAP-AKA vector.
     *
     * @param imsi   subscriber identity, NAI form
     * @param scheme {@link AuthVector#EAP_AKA} or {@link AuthVector#EAP_AKA_PRIME}
     * @return the vector, or a failed future
     */
    CompletableFuture<AuthVector> fetch(String imsi, String scheme);

    /**
     * Resynchronisation after the peer sent {@code AT_AUTS} (RFC 4187 §4.4). Carries
     * the {@code RAND} the peer is resynchronising on. At most <strong>one</strong> resync
     * per session is permitted by the caller — each attempt moves the AuC sequence
     * number, and a loop desynchronises a real subscriber.
     *
     * @param auts the peer's {@code AT_AUTS} value
     */
    CompletableFuture<AuthVector> resync(String imsi, String scheme, byte[] rand, byte[] auts);

    /**
     * Release the transport. Called from {@code deactivate()}; must be idempotent and
     * must abort any in-flight session so a dialog cannot leak across a restart.
     */
    void stop();

    /** Human-readable backend name for the startup banner and the admin surface. */
    String name();
}
