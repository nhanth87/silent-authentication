/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier.command;

import com.microjainslee.api.OutboundCommand;

/**
 * Outbound command: abort an in-flight SWx session (dialog hygiene).
 */
public record AbortSwxCommand(String reqId, String sessionId) implements OutboundCommand {
}
