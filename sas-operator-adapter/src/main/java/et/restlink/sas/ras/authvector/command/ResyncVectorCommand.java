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
 * Outbound command: AUTS resynchronisation after the peer sent {@code AT_AUTS}
 * (RFC 4187 §4.4).
 *
 * <p>The RA enforces the cap: {@link #maxResync()} attempts per session. Every retry
 * moves the AuC sequence number, and an unbounded loop against a live network is
 * exactly how a third-party EAP server desynchronises a subscriber (D6 consequence).</p>
 */
public record ResyncVectorCommand(
        String reqId,
        String imsi,
        String scheme,
        byte[] rand,
        byte[] auts,
        CompletableFuture<AuthVector> reply) implements OutboundCommand {

    /** At most one resynchronisation per session. */
    public static final int MAX_RESYNC = 1;

    public int maxResync() {
        return MAX_RESYNC;
    }
}
