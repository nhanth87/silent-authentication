/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.events.Ts43RequestEvent;
import et.restlink.sas.model.Ts43Result;

import java.util.concurrent.CompletableFuture;

/**
 * Port from the TS.43 {@code /ts43} REST surface into the running entitlement
 * engine. Adapted in production by the host's {@code SasBootstrap}, stubbed in
 * library tests.
 *
 * <p>Separate from {@link SasVerifyEngine} on purpose: the CAMARA {@code /verify}
 * path and the TS.43 entitlement path are different contracts with different
 * budgets, and a caller that could reach one through the other's port would be a
 * design smell. Both are implemented by the same composition bean, which is the
 * single {@code com.microjainslee.core.*} seam (H24).</p>
 */
public interface SasEntitlementEngine {

    /**
     * Synchronous bridge into the SLEE event router for one TS.43 hop.
     *
     * <p>Idempotent per {@code reqId} within a hop: a duplicate {@code CHALLENGE}
     * replays the cached Challenge, a duplicate {@code RESPOND} replays the cached
     * terminal answer. The two hops share a {@code reqId} <em>on purpose</em> — that
     * is what binds the Response to its Challenge inside one activity.</p>
     */
    CompletableFuture<Ts43Result> submitTs43(Ts43RequestEvent evt);

    /** Release the per-request SBB entity and the EAP state it holds. */
    void releaseTs43(String reqId);
}