/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway side of layered ADR D3: the token is forwarded unread to the entitlement
 * server, which alone decides; every refusal, timeout or transport error is fail-closed.
 */
class OperatorTokenAnchorTest {

    private static final String TOKEN = "eyJlcGsiOnsia3R5IjoiRUMiLCJjcnYi.a.b.c.d";

    private OperatorTokenAnchor anchor;
    private Cfg cfg;
    private final List<OperatorTokenRedeemReq> sent = new ArrayList<>();
    private final List<ExposureContext> contexts = new ArrayList<>();
    private Function<OperatorTokenRedeemReq, CompletableFuture<ExposureOutcome<SubscriberVerificationResult>>> ecs;

    @BeforeEach
    void setUp() {
        cfg = new Cfg();
        anchor = new OperatorTokenAnchor();
        anchor.config = cfg;
        anchor.engine = new Engine();
        ecs = req -> CompletableFuture.completedFuture(ExposureOutcome.ok(
                new SubscriberVerificationResult("251911111111", "EAP-AKA", "2026-10-01T06:00:00Z",
                        true, "jti-1")));
    }

    @Test
    void redeemedAtTheEntitlementServerAndMappedToCanonicalForm() {
        IdentityAnchor.OperatorBinding b = anchor.resolveOperatorToken(TOKEN, "bank-a");
        assertNotNull(b);
        assertEquals("+251911111111", b.msisdn());
        assertNull(b.imsi(), "the gateway never learns the IMSI");
        assertEquals("EAP-AKA", b.eapMethod());
        assertEquals(TOKEN, sent.get(0).operatorToken(), "forwarded unread");
        assertEquals("bank-a", sent.get(0).clientId());
        assertEquals("sas-camara", sent.get(0).afId());
        assertTrue(contexts.get(0).requestId().startsWith("redeem-"));
        assertEquals(OperatorTokenAnchor.REDEEM_BUDGET_MS, contexts.get(0).budgetMs());
    }

    @Test
    void unknownClientIsNamedAsTheGateway() {
        anchor.resolveOperatorToken(TOKEN);
        assertEquals(OperatorTokenAnchor.UNKNOWN_CLIENT, sent.get(0).clientId());
    }

    @Test
    void entitlementServerRefusalIsARefusal() {
        ecs = req -> CompletableFuture.completedFuture(
                ExposureOutcome.problem(ExposureCause.INVALID_TOKEN, "operator token refused"));
        assertNull(anchor.resolveOperatorToken(TOKEN));
    }

    @Test
    void transportFailureOrSilenceFailsClosed() {
        ecs = req -> CompletableFuture.failedFuture(new java.io.IOException("connection refused"));
        assertNull(anchor.resolveOperatorToken(TOKEN));
        ecs = req -> new CompletableFuture<>();
        long t0 = System.nanoTime();
        assertNull(anchor.resolveOperatorToken(TOKEN));
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 2000, "bounded wait");
    }

    @Test
    void answerWithoutSimProofOrNumberIsRefused() {
        ecs = req -> CompletableFuture.completedFuture(ExposureOutcome.ok(
                new SubscriberVerificationResult("251911111111", "EAP-AKA", "x", false, "j")));
        assertNull(anchor.resolveOperatorToken(TOKEN));
        ecs = req -> CompletableFuture.completedFuture(ExposureOutcome.ok(
                new SubscriberVerificationResult("not-a-number", "EAP-AKA", "x", true, "j")));
        assertNull(anchor.resolveOperatorToken(TOKEN));
    }

    @Test
    void disabledOrUnwiredRefusesWithoutCallingTheEcs() {
        cfg.on = false;
        assertNull(anchor.resolveOperatorToken(TOKEN));
        cfg.on = true;
        anchor.engine = null;
        assertNull(anchor.resolveOperatorToken(TOKEN));
        assertTrue(sent.isEmpty());
    }

    @Test
    void candidateExtractionPrecedenceAndTheLabOnlyForms() {
        assertNull(anchor.extractCandidate("Bearer operatortoken:tk1", null, null),
                "direct bearer is a lab-only extension (D6), off by default");
        cfg.direct = true;
        assertEquals("tk1", anchor.extractCandidate("Bearer operatortoken:tk1", "operatortoken:tk2", "tk3"));
        assertEquals("tk2", anchor.extractCandidate("Bearer abc", "operatortoken:tk2", "tk3"));
        assertNull(anchor.extractCandidate(null, null, "tk3"), "header ignored unless enabled");
        cfg.header = true;
        assertEquals("tk3", anchor.extractCandidate(null, null, "tk3"));
    }

    private final class Engine implements SasVerifyEngine {
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
                    throw new UnsupportedOperationException();
                }

                @Override
                public CompletableFuture<ExposureOutcome<SubscriberVerificationResult>> redeemOperatorToken(
                        ExposureContext c, OperatorTokenRedeemReq r) {
                    contexts.add(c);
                    sent.add(r);
                    return ecs.apply(r);
                }
            };
        }
    }

    private static final class Cfg extends OperatorTokenConfig {
        boolean on = true;
        boolean header;
        boolean direct;

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public boolean headerEnabled() {
            return header;
        }

        @Override
        public boolean directBearerEnabled() {
            return direct;
        }
    }
}
