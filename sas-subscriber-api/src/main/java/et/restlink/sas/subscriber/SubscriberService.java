/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import java.util.concurrent.CompletableFuture;

/**
 * The boundary between the CAMARA gateway and whatever knows the subscriber (layered ADR
 * R1). sas-camara only ever calls this interface; sas-ecs implements it over the operator
 * adapter, and a real NEF / operator platform could implement it later without touching
 * sas-camara.
 *
 * <p>One method per operation of {@code sas-subscriber-exposure-v1.yaml}. Every future
 * completes — with a value or a problem — and never exceptionally for a business outcome;
 * the implementation decides the data source, never the caller.</p>
 */
public interface SubscriberService {

    /** {@code POST /ue-id/retrieve}: who holds this IP:port at that time. */
    CompletableFuture<ExposureOutcome<UeIdInfo>> retrieveUeId(ExposureContext ctx, UeIdReq req);

    /** {@code POST /monitoring-event/{scsAsId}/subscriptions}: immediate one-time report. */
    CompletableFuture<ExposureOutcome<MonitoringEventReports>> monitor(
            ExposureContext ctx, String scsAsId, MonitoringEventSubscription req);

    /** {@code POST /sim-change/retrieve}: latest SIM change, or {@code NO_EVIDENCE}. */
    CompletableFuture<ExposureOutcome<SimChangeInfo>> retrieveSimChange(
            ExposureContext ctx, SimChangeReq req);

    /** {@code POST /operator-token/redeem}: single use, keyed on (token jti, request id). */
    CompletableFuture<ExposureOutcome<SubscriberVerificationResult>> redeemOperatorToken(
            ExposureContext ctx, OperatorTokenRedeemReq req);
}
