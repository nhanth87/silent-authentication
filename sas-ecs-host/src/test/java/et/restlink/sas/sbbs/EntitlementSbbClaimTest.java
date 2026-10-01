/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.sbbs;

import et.restlink.sas.coordinator.EntitlementCoordinator;
import et.restlink.sas.coordinator.EntitlementSessions;
import et.restlink.sas.events.Ts43RequestEvent;
import et.restlink.sas.model.Ts43Result;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A malformed claimed number is refused at the Challenge hop. It must never be turned into
 * "no claim", which would quietly switch the binding step from confirmation to discovery.
 */
class EntitlementSbbClaimTest {

    @Test
    void malformedClaimIsRefusedBeforeAnyNetworkCall() throws Exception {
        EntitlementCoordinator coordinator = new EntitlementCoordinator();
        // No RA is bound: reaching the vector fetch would answer a different failure.
        EntitlementSbb sbb = new EntitlementSbb(coordinator, new EntitlementSessions(), null, null);
        CompletableFuture<Ts43Result> answer =
                coordinator.register("r-claim", Ts43RequestEvent.Phase.CHALLENGE);

        sbb.onEvent(Ts43RequestEvent.challenge("r-claim", "655010000000001", "0911111111"), null);

        Ts43Result result = answer.get(1, TimeUnit.SECONDS);
        assertEquals(Ts43Result.Outcome.FAILURE, result.outcome());
        assertEquals("claimed number is not a valid E.164 number", result.message());
    }

    @Test
    void separatorsInAValidClaimAreNotARefusal() throws Exception {
        EntitlementCoordinator coordinator = new EntitlementCoordinator();
        EntitlementSbb sbb = new EntitlementSbb(coordinator, new EntitlementSessions(), null, null);
        CompletableFuture<Ts43Result> answer =
                coordinator.register("r-ok", Ts43RequestEvent.Phase.CHALLENGE);

        sbb.onEvent(Ts43RequestEvent.challenge("r-ok", "655010000000001", "+251 911-111-111"), null);

        // The claim passes; with no auth-vector RA bound the hop fails closed further on.
        Ts43Result result = answer.get(1, TimeUnit.SECONDS);
        assertEquals(Ts43Result.Outcome.FAILURE, result.outcome());
        assertEquals("no authentication vector available", result.message());
    }
}
