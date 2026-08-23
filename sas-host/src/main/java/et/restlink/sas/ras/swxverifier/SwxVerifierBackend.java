/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerificationEvidence;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable SWx verifier backend (TS 29.273 / TS 33.402).
 * SWx carries EAP-AKA auth vectors between 3GPP AAA and HSS for
 * non-3GPP (Wi-Fi) access. This is the TS.43 silent-auth path.
 *
 * <p>Design invariants:</p>
 * <ul>
 *   <li>Own-HSS only — SWx queries the operator AAA/HSS, never interconnect.</li>
 *   <li>Never IP→MSISDN — SWx verifies a resolved identity, not an IP.</li>
 * </ul>
 */
public interface SwxVerifierBackend {

    /** Own-HSS-only invariant. */
    boolean NO_INTERCONNECT_SWX = true;

    /** SWx never performs IP → MSISDN. */
    boolean NEVER_IP_TO_MSISDN = true;

    /**
     * Produce verification evidence for a resolved subscriber over SWx (EAP-AKA).
     * The RA enforces the 2 s budget and aborts the session on timeout.
     */
    CompletableFuture<VerificationEvidence> verify(String msisdn,
                                                    String imsi,
                                                    AccessTech accessTech,
                                                    long nowMs);
}
