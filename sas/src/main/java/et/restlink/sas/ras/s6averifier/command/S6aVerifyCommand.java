/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerificationEvidence;

import java.util.concurrent.CompletableFuture;

/**
 * SBB → S6a verifier RA command: verify a resolved subscriber over Diameter
 * S6a (TS 29.272). One Diameter session per request stage —
 * ULR/ULA (316) for attachment liveness and AIR/AIA (318) for SIM-swap
 * freshness. The RA completes {@link #reply()} after the HSS answers or the
 * 2 s budget expires (session aborted). Never maps IP → MSISDN (that is the
 * Resolver's data-plane job, gate H13).
 */
public record S6aVerifyCommand(
        String reqId,
        String msisdn,
        String imsi,
        AccessTech accessTech,
        CompletableFuture<VerificationEvidence> reply) implements OutboundCommand {
}