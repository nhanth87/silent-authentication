/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import et.restlink.sas.model.ResolverResult;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable IP-Resolver backend (data-plane). Concrete sources per operator:
 * PGW RADIUS accounting, PCRF Gx/Sd, or CGNAT log. See
 * {@code docs/design/silent-auth-flow.md} §3 and the open item in AGENTS.md §10.
 */
public interface ResolverBackend {

    /**
     * Resolve the subscriber that owns {@code srcIp:srcPort} at {@code tsEpochMs}.
     *
     * @return completed with a bound result, or a miss carrying NO_BINDING /
     *         AMBIGUOUS_BINDING.
     */
    CompletableFuture<ResolverResult> resolve(String srcIp, int srcPort, long tsEpochMs);
}