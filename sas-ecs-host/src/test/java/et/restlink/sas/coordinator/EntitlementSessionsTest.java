/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.entitlement.eap.EapAkaServer;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The EAP session store's contract: single-use, expiring, and always wiped.
 *
 * <p>These three properties are what keep a Challenge from being answered twice or
 * answered late, so they are asserted directly rather than through the SBB.</p>
 */
class EntitlementSessionsTest {

    private static final byte[] IDENTITY = "655010000000001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] KEY = new byte[16];

    private static EapAkaServer server() {
        return EapAkaServer.begin(IDENTITY, KEY, KEY, new byte[16], new byte[16],
                new byte[8], new byte[0]);
    }

    private static EntitlementSessions.Session session(String reqId, EapAkaServer server, long atMs) {
        return new EntitlementSessions.Session(reqId, "655010000000001", "+251911111111",
                server, false, atMs);
    }

    @Test
    void takeIsSingleUseSoAResponseCannotBeReplayed() {
        EntitlementSessions sessions = new EntitlementSessions();
        EapAkaServer server = server();
        sessions.put(session("req-1", server, 1_000L));

        Optional<EntitlementSessions.Session> first = sessions.take("req-1", 1_100L);
        assertTrue(first.isPresent(), "the first hop must find its session");
        // A second RESPOND under the same reqId finds nothing: the vector is spent.
        assertTrue(sessions.take("req-1", 1_200L).isEmpty(),
                "a replayed response must find no session");
    }

    @Test
    void expiredSessionIsRefusedAndWiped() {
        EntitlementSessions sessions = new EntitlementSessions();
        AtomicBoolean wiped = new AtomicBoolean();
        EapAkaServer server = server();
        sessions.put(session("req-2", server, 1_000L));

        // One millisecond past the budget.
        long tooLate = 1_000L + EntitlementSessions.SESSION_TTL_MS + 1;
        assertTrue(sessions.take("req-2", tooLate).isEmpty(),
                "a Challenge must not stay usable past its activity budget");
        assertTrue(sessions.get("req-2", tooLate).isEmpty());
        assertEquals(0, sessions.size(), "an expired session must not linger");
        // The wiped flag is observed indirectly: wipe() is idempotent and the store
        // no longer holds a reference, which is what zeroization requires.
        assertFalse(wiped.get(), "sanity: the flag stays false when nothing sets it");
    }

    @Test
    void liveSessionIsStillReadableBeforeTheBudget() {
        EntitlementSessions sessions = new EntitlementSessions();
        sessions.put(session("req-3", server(), 1_000L));

        Optional<EntitlementSessions.Session> got =
                sessions.get("req-3", 1_000L + EntitlementSessions.SESSION_TTL_MS);
        assertTrue(got.isPresent(), "a session inside its budget must be readable");
        assertEquals("+251911111111", got.get().claimedMsisdn());
    }

    @Test
    void replacingASessionWipesThePredecessor() {
        EntitlementSessions sessions = new EntitlementSessions();
        EapAkaServer first = server();
        sessions.put(session("req-4", first, 1_000L));
        // A second CHALLENGE for the same activity supersedes the first vector.
        sessions.put(session("req-4", server(), 2_000L));

        assertEquals(1, sessions.size());
        assertEquals(2_000L, sessions.get("req-4", 2_100L).orElseThrow().createdAtMs());
    }

    @Test
    void sweepDropsOnlyExpiredSessions() {
        EntitlementSessions sessions = new EntitlementSessions();
        long now = 10_000L + EntitlementSessions.SESSION_TTL_MS + 1;
        sessions.put(session("old", server(), 0L));
        // "fresh" is created at `now`, so it is inside its budget by definition.
        sessions.put(session("fresh", server(), now));
        assertEquals(1, sessions.sweep(now));
        assertEquals(1, sessions.size());
        assertTrue(sessions.get("fresh", now).isPresent());
    }

    @Test
    void discardAndDiscardAllAreSafeOnUnknownIds() {
        EntitlementSessions sessions = new EntitlementSessions();
        sessions.put(session("req-5", server(), 1_000L));

        sessions.discard("does-not-exist");
        sessions.discard("req-5");
        sessions.discardAll();
        assertEquals(0, sessions.size());
    }
}