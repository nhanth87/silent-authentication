/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.ras.binding.SubscriberBinding;

import java.util.concurrent.CompletableFuture;

/**
 * Outbound command: resolve IMSI → MSISDN.
 *
 * <p>The RA owns the 2 s budget across every source it consults, and the command's
 * future completes with an unresolved binding rather than a boolean — the caller still
 * has to decide what an unresolved answer means for its own flow.</p>
 */
public record LookupBindingCommand(
        String reqId,
        String imsi,
        String claimedMsisdn,
        CompletableFuture<SubscriberBinding> reply) implements OutboundCommand {
}
