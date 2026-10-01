/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationAnswer;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.AuthenticationInformationAnswerImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.AuthSessionStateEnum;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Per-Session-Id answer correlation (defect #1): concurrent exchanges get
 * their own answers; unmatched answers are reported fail-closed.
 */
class S6aExchangeCorrelatorTest {

    @Test
    void completesOnlyTheAddressedExchange() throws Exception {
        S6aExchangeCorrelator correlator = new S6aExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer> first =
                correlator.register("session-1");
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer> second =
                correlator.register("session-2");
        assertEquals(2, correlator.size());

        assertTrue(correlator.complete("session-2", aia("session-2", "req-2")));

        assertFalse(first.isDone(), "unrelated exchange must stay pending");
        assertTrue(second.isDone());
        assertEquals("req-2", second.get(0, TimeUnit.MILLISECONDS).getUsername());
        assertEquals(1, correlator.size());
    }

    @Test
    void unmatchedSessionIdIsReportedFalse() throws Exception {
        S6aExchangeCorrelator correlator = new S6aExchangeCorrelator();
        correlator.register("session-1");

        assertFalse(correlator.complete(null, aia("x", "u")));
        assertFalse(correlator.complete("unknown", aia("unknown", "u")));
    }

    @Test
    void hopByHopFallbackCompletesAfterBinding() throws Exception {
        S6aExchangeCorrelator correlator = new S6aExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer> future =
                correlator.register("session-1");
        correlator.bindHopByHop("session-1", 42L);

        assertFalse(correlator.complete("mangled-by-proxy", aia("other", "u")));
        assertTrue(correlator.completeByHopByHop(42L, aia("session-1", "u")));
        assertTrue(future.isDone());
        assertEquals(0, correlator.size());
    }

    @Test
    void removeCleansBothIndexes() throws Exception {
        S6aExchangeCorrelator correlator = new S6aExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer> future =
                correlator.register("session-1");
        correlator.bindHopByHop("session-1", 7L);

        correlator.remove("session-1");

        assertFalse(correlator.complete("session-1", aia("session-1", "u")));
        assertFalse(correlator.completeByHopByHop(7L, aia("session-1", "u")));
        assertEquals(0, correlator.size());
        assertFalse(future.isDone());
        assertDoesNotThrow(() -> correlator.remove("never-registered"));
    }

    @Test
    void failCompletesExceptionallyExactlyOnce() throws Exception {
        S6aExchangeCorrelator correlator = new S6aExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer> future =
                correlator.register("session-1");

        assertTrue(correlator.fail("session-1", new java.util.concurrent.TimeoutException()));
        assertFalse(correlator.complete("session-1", aia("session-1", "late")));
        assertTrue(future.isCompletedExceptionally());

        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer> other =
                correlator.register("session-2");
        correlator.failAll(new IllegalStateException("stop"));
        assertTrue(other.isCompletedExceptionally());
        assertEquals(0, correlator.size());
    }

    private static AuthenticationInformationAnswer aia(String sessionId, String username)
            throws Exception {
        AuthenticationInformationAnswerImpl answer = new AuthenticationInformationAnswerImpl(
                "hss.restlink.et", "restlink.et", false,
                ResultCodes.DIAMETER_SUCCESS, sessionId,
                AuthSessionStateEnum.NO_STATE_MAINTAINED);
        answer.setUsername(username);
        return answer;
    }
}
