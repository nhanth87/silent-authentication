/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector.command;

import com.microjainslee.api.OutboundCommand;

/**
 * Outbound command: abort an in-flight vector exchange (dialog hygiene).
 */
public record AbortAuthVectorCommand(String reqId, String sessionId) implements OutboundCommand {
}
