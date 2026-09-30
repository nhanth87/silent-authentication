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
     * Resolve one subscriber.
     *
     * @param imsi the identity proved by the EAP exchange
     * @return a resolved or unresolved binding, or a failed future on a transport error
     */
    CompletableFuture<SubscriberBinding> lookup(String imsi);

    /** Release the transport. Idempotent; must not leave a session in flight. */
    void stop();

    /** Short source name for the audit record and the admin surface. */
    String name();
}
