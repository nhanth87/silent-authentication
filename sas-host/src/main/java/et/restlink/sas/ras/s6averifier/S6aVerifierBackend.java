/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerificationEvidence;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable Diameter S6a / S6d verifier backend (TS 29.272).
 *
 * <p><strong>Design invariants (AGENTS.md §3/§4):</strong></p>
 * <ul>
 *   <li>{@link #NO_INTERCONNECT_S6A} — the verifier targets the operator's OWN
 *       HSS only (AIR/IDR/ULR over S6a is intra-network; SEPP filters
 *       cross-PLMN probes on N32, gate H12).</li>
 *   <li>{@link #NEVER_IP_TO_MSISDN} — S6a verifies a resolved MSISDN/IMSI; it
 *       never resolves an IP address into a subscriber identity (the Resolver's
 *       PGW/PCRF data-plane job, gate H13).</li>
 * </ul>
 *
 * <p>The real transport plugs in a corsac-diameter / jDiameter S6a client
 * (mirror {@code vendor-ras/ra-diameter}); the pilot implements an in-memory
 * HSS stand-in ({@link InMemoryS6aVerifierBackend}).</p>
 */
public interface S6aVerifierBackend {

    /** Own-HSS-only invariant (no cross-operator S6a). */
    boolean NO_INTERCONNECT_S6A = true;

    /** S6a never performs IP → MSISDN (resolver is data-plane). */
    boolean NEVER_IP_TO_MSISDN = true;

    /**
     * Produce verification evidence for a resolved subscriber over S6a.
     * The RA enforces the 2 s budget (SasTimeouts.DIAMETER_MS) and aborts the
     * session on timeout.
     */
    CompletableFuture<VerificationEvidence> verify(String msisdn,
                                                   String imsi,
                                                   AccessTech accessTech,
                                                   long nowMs);
}