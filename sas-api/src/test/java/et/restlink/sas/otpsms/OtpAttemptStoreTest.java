/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OTP attempt bookkeeping: expiry evaluation, the wrong-attempt counter and its
 * burn point, single-use removal, and the per-MSISDN send window that backs
 * {@code ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED}.
 */
class OtpAttemptStoreTest {

    private static final String MSISDN = "+251911111111";
    private static final String OTHER = "+251922222222";
    private static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String HASH = OtpSmsResource.hash("123456", ID);

    private OtpAttemptStore store;

    @BeforeEach
    void setUp() {
        store = new OtpAttemptStore();
    }

    @Test
    void create_storesTheAttemptWithDeadlineAndNoFailures() {
        long before = System.currentTimeMillis() / 1000L;
        store.create(ID, MSISDN, HASH, 300L);

        OtpAttemptStore.Attempt attempt = store.find(ID).orElseThrow();
        assertEquals(MSISDN, attempt.msisdn());
        assertEquals(HASH, attempt.codeHash());
        assertEquals(0, attempt.attempts());
        assertFalse(attempt.burned());
        assertTrue(attempt.expiresEpochSec() >= before + 300L);
        assertFalse(attempt.expired(before));
        assertEquals(1, store.attemptCount());
    }

    @Test
    void find_unknownOrBlank_isEmpty() {
        assertTrue(store.find(ID).isEmpty());
        assertTrue(store.find(null).isEmpty());
        assertTrue(store.find("  ").isEmpty());
    }

    @Test
    void registerFailure_countsAndBurnsAtTheConfiguredMaximum() {
        store.create(ID, MSISDN, HASH, 300L);

        assertEquals(1, store.registerFailure(ID, 3).orElseThrow().attempts());
        assertFalse(store.registerFailure(ID, 3).orElseThrow().burned());
        OtpAttemptStore.Attempt third = store.registerFailure(ID, 3).orElseThrow();
        assertEquals(3, third.attempts());
        assertTrue(third.burned(), "the budget is exhausted at maxAttempts");

        // a burned attempt stays burned and keeps counting
        assertTrue(store.registerFailure(ID, 3).orElseThrow().burned());
    }

    @Test
    void registerFailure_unknownId_isEmpty() {
        assertTrue(store.registerFailure(ID, 3).isEmpty());
        assertTrue(store.registerFailure(null, 3).isEmpty());
    }

    @Test
    void remove_makesTheAttemptSingleUse() {
        store.create(ID, MSISDN, HASH, 300L);
        store.remove(ID);
        assertTrue(store.find(ID).isEmpty());
        assertEquals(0, store.attemptCount());
    }

    @Test
    void expiredAttempt_isReportedExpired() {
        long nowSec = System.currentTimeMillis() / 1000L;
        store.createWithDeadline(ID, MSISDN, HASH, nowSec - 1);
        assertTrue(store.find(ID).orElseThrow().expired(nowSec));
    }

    @Test
    void sendRateLimit_countsPerMsisdnInsideTheWindow() {
        assertFalse(store.sendRateLimited(MSISDN, 3, 3600L));
        store.noteSend(MSISDN);
        store.noteSend(MSISDN);
        assertFalse(store.sendRateLimited(MSISDN, 3, 3600L), "two of three still allowed");
        store.noteSend(MSISDN);
        assertTrue(store.sendRateLimited(MSISDN, 3, 3600L));
        assertFalse(store.sendRateLimited(OTHER, 3, 3600L), "the limit is per subscriber");
    }

    @Test
    void sendRateLimit_blankNumberIsNeverLimited() {
        store.noteSend(null);
        assertFalse(store.sendRateLimited(null, 1, 3600L));
        assertFalse(store.sendRateLimited("  ", 1, 3600L));
    }
}
