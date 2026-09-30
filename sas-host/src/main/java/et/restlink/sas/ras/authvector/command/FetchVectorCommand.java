/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.ras.authvector.AuthVector;

import java.util.concurrent.CompletableFuture;

/**
 * Outbound command: fetch one EAP-AKA vector for the entitlement service.
 *
 * <p>The RA owns the 2 s budget and the dialog; the SBB only learns success or
 * failure. A failed future is always a refusal — never a partially populated
 * vector.</p>
 */
public record FetchVectorCommand(
        String reqId,
        String imsi,
        String scheme,
        CompletableFuture<AuthVector> reply) implements OutboundCommand {
}
