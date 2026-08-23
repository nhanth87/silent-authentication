/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier.command;

import com.microjainslee.api.OutboundCommand;

/**
 * SBB → MAP verifier RA: abort a dialog for {@code reqId}. Used by the FSM when
 * the verifier budget expires while the backend still holds the dialog.
 */
public record AbortMapCommand(String reqId, String dialogId) implements OutboundCommand {
}