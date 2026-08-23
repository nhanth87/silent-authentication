/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthAnswer;
import com.mobius.software.telco.protocols.diameter.impl.commands.swx.MultimediaAuthAnswerImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.AuthSessionStateEnum;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Per-Session-Id answer correlation (defect #1): concurrent exchanges get
 * their own answers; unmatched answers are reported fail-closed.
 */
class SwxExchangeCorrelatorTest {

    @Test
    void completesOnlyTheAddressedExchange() throws Exception {
        SwxExchangeCorrelator correlator = new SwxExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer> first =
                correlator.register("session-1");
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer> second =
                correlator.register("session-2");
        assertEquals(2, correlator.size());

        assertTrue(correlator.complete("session-2", maa("session-2", "user-2")));

        assertFalse(first.isDone(), "unrelated exchange must stay pending");
        assertTrue(second.isDone());
        assertEquals("user-2", second.get(0, TimeUnit.MILLISECONDS).getUsername());
        assertEquals(1, correlator.size());
    }

    @Test
    void unmatchedSessionIdIsReportedFalse() throws Exception {
        SwxExchangeCorrelator correlator = new SwxExchangeCorrelator();
        correlator.register("session-1");

        assertFalse(correlator.complete(null, maa("x", "u")));
        assertFalse(correlator.complete("unknown", maa("unknown", "u")));
    }

    @Test
    void hopByHopFallbackCompletesAfterBinding() throws Exception {
        SwxExchangeCorrelator correlator = new SwxExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer> future =
                correlator.register("session-1");
        correlator.bindHopByHop("session-1", 42L);

        assertFalse(correlator.complete("mangled-by-proxy", maa("other", "u")));
        assertTrue(correlator.completeByHopByHop(42L, maa("session-1", "u")));
        assertTrue(future.isDone());
        assertEquals(0, correlator.size());
    }

    @Test
    void removeCleansBothIndexes() throws Exception {
        SwxExchangeCorrelator correlator = new SwxExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer> future =
                correlator.register("session-1");
        correlator.bindHopByHop("session-1", 7L);

        correlator.remove("session-1");

        assertFalse(correlator.complete("session-1", maa("session-1", "u")));
        assertFalse(correlator.completeByHopByHop(7L, maa("session-1", "u")));
        assertEquals(0, correlator.size());
        assertFalse(future.isDone());
        assertDoesNotThrow(() -> correlator.remove("never-registered"));
    }

    @Test
    void failCompletesExceptionallyExactlyOnce() throws Exception {
        SwxExchangeCorrelator correlator = new SwxExchangeCorrelator();
        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer> future =
                correlator.register("session-1");

        assertTrue(correlator.fail("session-1", new java.util.concurrent.TimeoutException()));
        assertFalse(correlator.complete("session-1", maa("session-1", "late")));
        assertTrue(future.isCompletedExceptionally());

        CompletableFuture<com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer> other =
                correlator.register("session-2");
        correlator.failAll(new IllegalStateException("stop"));
        assertTrue(other.isCompletedExceptionally());
        assertEquals(0, correlator.size());
    }

    private static MultimediaAuthAnswer maa(String sessionId, String username) throws Exception {
        MultimediaAuthAnswerImpl answer = new MultimediaAuthAnswerImpl(
                "hss.restlink.et", "restlink.et", false,
                ResultCodes.DIAMETER_SUCCESS, sessionId,
                AuthSessionStateEnum.NO_STATE_MAINTAINED, "aaa.restlink.et");
        answer.setUsername(username);
        return answer;
    }
}
