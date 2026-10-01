/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SIM Swap evidence comes from the entitlement server only, and absence fails closed. */
class HostSimSwapEvidenceTest {

    private final List<SimChangeReq> asked = new ArrayList<>();

    private HostSimSwapEvidence with(Function<SimChangeReq, CompletableFuture<ExposureOutcome<SimChangeInfo>>> ecs) {
        HostSimSwapEvidence evidence = new HostSimSwapEvidence();
        evidence.bootstrap = new Engine(ecs);
        return evidence;
    }

    @Test
    void evidenceFromTheEntitlementServer() {
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        HostSimSwapEvidence evidence = with(req -> CompletableFuture.completedFuture(ExposureOutcome.ok(
                new SimChangeInfo(req.msisdn(), at.toString(), Instant.now().toString(), "LAB_HLR"))));
        assertEquals(Optional.of(at), evidence.lastSimChange("+251 911 111 111"));
        assertEquals("251911111111", asked.get(0).msisdn(), "internal digit form on the wire");
        assertEquals("sas-camara", asked.get(0).afId());
    }

    @Test
    void noEvidenceRefusalOrSilenceIsEmpty() {
        assertEquals(Optional.empty(), with(req -> CompletableFuture.completedFuture(
                ExposureOutcome.problem(ExposureCause.NO_EVIDENCE, "x"))).lastSimChange("+251911111111"));
        assertEquals(Optional.empty(), with(req -> CompletableFuture.completedFuture(
                ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE, "x"))).lastSimChange("+251911111111"));
        assertEquals(Optional.empty(), with(req -> CompletableFuture.failedFuture(new IllegalStateException()))
                .lastSimChange("+251911111111"));
    }

    @Test
    void notANumberOrNoWiringNeverAsks() {
        assertEquals(Optional.empty(), with(req -> {
            throw new AssertionError("must not be asked");
        }).lastSimChange("not-a-number"));
        HostSimSwapEvidence unwired = new HostSimSwapEvidence();
        assertEquals(Optional.empty(), unwired.lastSimChange("+251911111111"));
        assertTrue(asked.isEmpty());
    }

    private final class Engine implements SasVerifyEngine {
        private final Function<SimChangeReq, CompletableFuture<ExposureOutcome<SimChangeInfo>>> ecs;

        Engine(Function<SimChangeReq, CompletableFuture<ExposureOutcome<SimChangeInfo>>> ecs) {
            this.ecs = ecs;
        }

        @Override
        public CompletableFuture<VerifyResult> submit(VerifyRequestEvent evt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(String reqId) {
        }

        @Override
        public String afId() {
            return "sas-camara";
        }

        @Override
        public SubscriberService subscribers() {
            return new SubscriberService() {
                @Override
                public CompletableFuture<ExposureOutcome<UeIdInfo>> retrieveUeId(ExposureContext c, UeIdReq r) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<ExposureOutcome<MonitoringEventReports>> monitor(
                        ExposureContext c, String s, MonitoringEventSubscription r) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<ExposureOutcome<SimChangeInfo>> retrieveSimChange(
                        ExposureContext c, SimChangeReq r) {
                    asked.add(r);
                    return ecs.apply(r);
                }

                @Override
                public CompletableFuture<ExposureOutcome<SubscriberVerificationResult>> redeemOperatorToken(
                        ExposureContext c, OperatorTokenRedeemReq r) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
