/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier.command;

import com.microjainslee.api.OutboundCommand;

/**
 * SBB → S6a verifier RA: abort the Diameter session for {@code reqId}. Used by
 * the FSM when the S6a budget expires while the backend still holds the
 * session (dialog hygiene — no leak, gate H7).
 */
public record AbortS6aCommand(String reqId, String dialogId) implements OutboundCommand {
}