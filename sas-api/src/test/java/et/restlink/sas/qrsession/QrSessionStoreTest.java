/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.qrsession;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrSessionStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    private static QrSessionStore.QrSession session(String id) {
        return new QrSessionStore.QrSession(id, "+251911111111", "corr-1", "tenant-a", T0);
    }

    @Test
    void newSessionIsPendingAndNotApproved() {
        QrSessionStore store = new QrSessionStore();
        store.create(session("s1"));

        QrSessionStore.QrSession found = store.get("s1", T0).orElseThrow();
        assertEquals(QrSessionStore.State.PENDING, store.state(found, T0));
        assertTrue(store.verdict("s1").isEmpty(),
                "a session with no FSM verdict must carry none");
    }

    @Test
    void expiryOutranksAStoredVerdict() {
        QrSessionStore store = new QrSessionStore();
        store.create(session("s1"));
        store.beginClaim("s1");
        store.resolve("s1", new QrSessionStore.Verdict(true, null, "APPROVED"), T0);

        long later = T0 + QrSessionStore.SESSION_TTL_MS + 1;
        // past budget: the verdict must no longer be readable at all
        assertTrue(store.get("s1", later).isEmpty(),
                "a session past its budget must not be readable");
    }

    @Test
    void onlyTheFirstClaimWins() {
        QrSessionStore store = new QrSessionStore();
        store.create(session("s1"));

        assertTrue(store.beginClaim("s1"), "first claimant wins");
        assertFalse(store.beginClaim("s1"), "second handset must be refused");
        assertTrue(store.claimed("s1"));
    }

    @Test
    void claimOnUnknownSessionIsRefused() {
        QrSessionStore store = new QrSessionStore();
        assertFalse(store.beginClaim("never-created"),
                "a claim against an unknown id must not open a gate");
    }

    @Test
    void verdictIsReadableOnceResolved() {
        QrSessionStore store = new QrSessionStore();
        store.create(session("s1"));
        store.beginClaim("s1");
        store.resolve("s1", new QrSessionStore.Verdict(true, null, "APPROVED"), T0);

        QrSessionStore.QrSession found = store.get("s1", T0 + 1_000).orElseThrow();
        assertEquals(QrSessionStore.State.RESOLVED, store.state(found, T0 + 1_000));
        assertTrue(store.verdict("s1").orElseThrow().verified());
    }

    @Test
    void sweepDropsExpiredSessionsAndTheirGates() {
        QrSessionStore store = new QrSessionStore();
        store.create(session("s1"));

        long later = T0 + QrSessionStore.SESSION_TTL_MS + QrSessionStore.EVICTION_GRACE_MS + 1;
        assertTrue(store.sweep(later) >= 1);
        assertEquals(0, store.size());
        assertFalse(store.beginClaim("s1"), "a swept session must not accept a late claim");
    }

    @Test
    void recreatingAnIdResetsTheClaimGate() {
        QrSessionStore store = new QrSessionStore();
        store.create(session("s1"));
        assertTrue(store.beginClaim("s1"));

        store.create(session("s1"));
        assertTrue(store.beginClaim("s1"),
                "a fresh session under the same id starts unclaimed");
        assertTrue(store.verdict("s1").isEmpty(),
                "recreating must drop the previous verdict");
    }
}
