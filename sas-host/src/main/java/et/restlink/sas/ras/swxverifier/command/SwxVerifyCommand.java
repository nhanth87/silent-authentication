/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.VerificationEvidence;

import java.util.concurrent.CompletableFuture;

/**
 * Outbound command: verify a subscriber over SWx (EAP-AKA, TS 29.273).
 */
public record SwxVerifyCommand(
        String reqId,
        String msisdn,
        String imsi,
        AccessTech accessTech,
        CompletableFuture<VerificationEvidence> reply) implements OutboundCommand {
}
