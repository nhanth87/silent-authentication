/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.ras.resolver.ResolverBackend;

import java.util.concurrent.CompletableFuture;

/**
 * Port from the CAMARA northbound surface into the running SAS engine
 * (submit / release / resolver access). Adapted in production by the host's
 * {@code SasBootstrap}; faked with stubs in library unit tests. Keeps sas-api
 * free of any dependency on the SLEE composition app.
 */
public interface SasVerifyEngine {

    /**
     * Synchronous bridge into the SLEE event router. Idempotent per
     * {@code reqId}: completed requests return the cached result, in-flight
     * duplicates await the same future.
     */
    CompletableFuture<VerifyResult> submit(VerifyRequestEvent evt);

    /** Release the per-request SBB entity after the terminal result is read. */
    void release(String reqId);

    /** Expose the active resolver backend (for the session-tuple collector). */
    ResolverBackend resolverBackend();
}
