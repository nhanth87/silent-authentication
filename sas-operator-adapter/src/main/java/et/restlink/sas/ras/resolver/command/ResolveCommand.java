/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.model.ResolverResult;

import java.util.concurrent.CompletableFuture;

/**
 * SBB → Resolver RA command: resolve {@code srcIp:srcPort:ts} to a subscriber.
 * The RA completes {@link #reply()} after the backend answers or the 300 ms
 * budget expires (fail-closed).
 */
public record ResolveCommand(
        String reqId,
        String srcIp,
        int srcPort,
        long tsEpochMs,
        CompletableFuture<ResolverResult> reply) implements OutboundCommand {
}