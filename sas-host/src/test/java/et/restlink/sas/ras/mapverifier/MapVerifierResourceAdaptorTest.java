/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dialog-hygiene tests for {@link MapVerifierResourceAdaptor}: one TCAP dialog
 * per stage, abort on timeout/error, zero leaks. Mirrors harness gates H7
 * (abort on timeout) and H11 (one dialog per stage).
 */
class MapVerifierResourceAdaptorTest {

    private static final String REQ = "req-1";
    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";

    private MapVerifierResourceAdaptor ra;

    @BeforeEach
    void setUp() {
        ra = new MapVerifierResourceAdaptor();
        InMemoryMapVerifierBackend memory = new InMemoryMapVerifierBackend(0L, "AA");
        memory.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        ra.setBackend(memory);
    }

    private static MapVerifyCommand command(String reqId) {
        return new MapVerifyCommand(reqId, MSISDN, IMSI, AccessTech.GS_2G3G,
                new CompletableFuture<>());
    }

    @Test
    void inactiveRaFailsClosed() throws Exception {
        // Never activated → must never reach the backend.
        MapVerifyCommand cmd = command(REQ);
        ra.verify(cmd);
        VerificationEvidence ev = cmd.reply().get(2, TimeUnit.SECONDS);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void successCompletesAndClosesDialog() throws Exception {
        ra.raActive();
        MapVerifyCommand cmd = command(REQ);
        ra.verify(cmd);
        VerificationEvidence ev = cmd.reply().get(2, TimeUnit.SECONDS);
        assertFalse(ev.failed());
        assertEquals("MAP-PSI+SAI", ev.protocol());
        assertEquals(0, ra.openDialogs()); // H11 — dialog closed, no leak
    }

    @Test
    void backendErrorAbortsDialogNoLeak() throws Exception {
        ra.setBackend((m, i, at, now) -> CompletableFuture.completedFuture(
                VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP")));
        ra.raActive();
        MapVerifyCommand cmd = command(REQ);
        ra.verify(cmd);
        VerificationEvidence ev = cmd.reply().get(2, TimeUnit.SECONDS);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
        assertEquals(0, ra.openDialogs()); // failure path also terminates the dialog
    }

    @Test
    @Timeout(5)
    void timeoutAbortsDialogNoLeak() throws Exception {
        ra.setBackend((m, i, at, now) -> new CompletableFuture<>()); // never answers
        ra.raActive();
        MapVerifyCommand cmd = command(REQ);
        ra.verify(cmd);
        VerificationEvidence ev = cmd.reply().get(4, TimeUnit.SECONDS);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_TIMEOUT, ev.failure()); // H7 — abort on timeout
        assertEquals(0, ra.openDialogs()); // no dialog leak after the abort
    }

    @Test
    void duplicateReqIdReusesOneDialog() {
        ra.setBackend((m, i, at, now) -> new CompletableFuture<>()); // never answers
        ra.raActive();
        ra.verify(command(REQ));
        ra.verify(command(REQ));
        assertEquals(1, ra.openDialogs()); // H11 — one dialog per stage (idempotent)
        ra.abort(REQ, "dialog:" + REQ);
        assertEquals(0, ra.openDialogs()); // explicit abort clears the dialog
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }
}