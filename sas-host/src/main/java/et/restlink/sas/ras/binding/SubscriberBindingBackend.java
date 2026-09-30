/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding;

import java.util.concurrent.CompletableFuture;

/**
 * One source of IMSI → MSISDN bindings for the entitlement service (plan Phase 1c).
 *
 * <p>Implementations live under {@code /ras/} — gate H24 again. The RA consults them
 * in a configured order and fails closed when none of them answers.</p>
 *
 * <p><strong>Prohibited sources.</strong> SRI-SM (Send Routing Info for SM) must never
 * appear here: when SMS Home Routing is enabled it answers with a correlation ID or a
 * routing proxy, not a number, so binding on it produces a spurious match. ATI is banned
 * outright by GSMA FS.11 Category 1.</p>
 */
public interface SubscriberBindingBackend {

    /** Never bind from SRI-SM — Home Routing makes the answer a correlation ID. */
    boolean NEVER_SRI_SM = true;

    /** Never interrogate another operator's network. */
    boolean NO_INTERCONNECT_BINDING = true;

    /**
     * Resolve one subscriber by its IMSI.
     *
     * @param imsi the identity proved by the EAP exchange
     * @return a resolved or unresolved binding, or a failed future on a transport error
     */
    CompletableFuture<SubscriberBinding> lookup(String imsi);

    /**
     * Optional second question, for sources that are driven by the <b>number</b> rather
     * than the IMSI — MAP {@code SendIMSI} being the case in point: it answers
     * "which IMSI owns this MSISDN?", so it can only confirm or deny a claim, never
     * discover a number.
     *
     * <p>Only consulted when {@link #supportsClaimVerification()} is true. That flag
     * matters: a source that discovers the number from the IMSI must still be asked
     * {@link #lookup} even when the caller happens to have a claim, or a claim would
     * silently disable discovery.</p>
     *
     * <p>Default: "this source cannot answer that", so the RA simply moves on. A source
     * that implements it returns a <em>resolved</em> binding only when the network
     * confirms the claimed number belongs to the proved IMSI; anything else is
     * unresolved, which the caller must treat as a refusal.</p>
     *
     * @param provedImsi   the identity the EAP exchange proved
     * @param claimedMsisdn the number the bank asserted
     */
    default boolean supportsClaimVerification() {
        return false;
    }

    default CompletableFuture<SubscriberBinding> verifyClaim(String provedImsi,
                                                             String claimedMsisdn) {
        return CompletableFuture.completedFuture(
                SubscriberBinding.unresolved(provedImsi, name() + "-no-claim-support"));
    }

    /** Release the transport. Idempotent; must not leave a session in flight. */
    void stop();

    /** Short source name for the audit record and the admin surface. */
    String name();
}
