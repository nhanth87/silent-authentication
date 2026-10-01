/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerificationEvidence;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable MAP/identity verifier backend.
 *
 * <p><strong>FS.11 / design invariant:</strong> the verifier targets the
 * operator's OWN HLR/HSS only. {@code anyTimeInterrogation} (ATI) is Category 1
 * on interconnect and MUST NOT be used; prefer {@code provideSubscriberInfo}
 * (PSI, Cat 2.1) + {@code sendAuthenticationInfo} (SAI, Cat 3.2). See
 * {@code docs/design/silent-auth-standard-flow.md} §5.</p>
 */
public interface MapVerifierBackend {

    /** Never-ATI invariant — asserted by {@link InMemoryMapVerifierBackend}. */
    boolean NO_INTERCONNECT_ATI = true;

    /**
     * Produce verification evidence for a resolved subscriber.
     * The RA enforces the 2 s budget and aborts the dialog on timeout.
     */
    CompletableFuture<VerificationEvidence> verify(String msisdn,
                                                   String imsi,
                                                   AccessTech accessTech,
                                                   long nowMs);
}