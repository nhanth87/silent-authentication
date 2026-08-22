/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persistence;

import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.VerifyResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 persistence store tests.
 */
class InMemoryVerifyResultStoreTest {

    @Test
    void storeAndRetrieve() {
        InMemoryVerifyResultStore store = new InMemoryVerifyResultStore(60_000L);
        VerifyResult r = VerifyResult.approved("req-1", "+251911111111", AssuranceLevel.HIGH);
        store.store("req-1", r);
        VerifyResult got = store.retrieve("req-1");
        assertNotNull(got);
        assertEquals("+251911111111", got.msisdn());
    }

    @Test
    void retrieveMissing_returnsNull() {
        InMemoryVerifyResultStore store = new InMemoryVerifyResultStore(60_000L);
        assertNull(store.retrieve("nope"));
    }

    @Test
    void remove_deletes() {
        InMemoryVerifyResultStore store = new InMemoryVerifyResultStore(60_000L);
        store.store("req-2", VerifyResult.fallback("req-2", null));
        store.remove("req-2");
        assertNull(store.retrieve("req-2"));
    }

    @Test
    void expiredEntry_returnsNull() throws Exception {
        InMemoryVerifyResultStore store = new InMemoryVerifyResultStore(1L); // 1ms TTL
        store.store("req-3", VerifyResult.approved("req-3", "+1", AssuranceLevel.LOW));
        Thread.sleep(10L);
        assertNull(store.retrieve("req-3"));
    }

    @Test
    void evictExpired_clearsStale() throws Exception {
        InMemoryVerifyResultStore store = new InMemoryVerifyResultStore(1L);
        store.store("req-4", VerifyResult.approved("req-4", "+1", AssuranceLevel.LOW));
        Thread.sleep(10L);
        store.evictExpired();
        assertEquals(0, store.size());
    }
}
