/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.exposure.command;

import com.microjainslee.api.OutboundCommand;

import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Outbound command for {@code exposure-ra}: one subscriber exposure call. The SBB says
 * which operation ({@code call}) and gets the answer on {@code reply}; the RA owns the
 * HTTP transport.
 */
public record SubscriberCallCommand<T>(
        String reqId,
        Function<SubscriberService, CompletableFuture<ExposureOutcome<T>>> call,
        CompletableFuture<ExposureOutcome<T>> reply) implements OutboundCommand {
}
