/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerificationEvidence;

import java.util.concurrent.CompletableFuture;

/**
 * SBB → MAP verifier RA command: verify a resolved subscriber (one TCAP dialog,
 * PSI/SAI; never ATI). The RA completes {@link #reply()} after the HLR answers
 * or the 2 s budget expires (dialog aborted).
 */
public record MapVerifyCommand(
        String reqId,
        String msisdn,
        String imsi,
        AccessTech accessTech,
        CompletableFuture<VerificationEvidence> reply) implements OutboundCommand {
}