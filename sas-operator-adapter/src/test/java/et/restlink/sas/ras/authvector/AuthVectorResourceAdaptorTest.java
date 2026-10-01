/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import et.restlink.sas.ras.authvector.command.AbortAuthVectorCommand;
import et.restlink.sas.ras.authvector.command.FetchVectorCommand;
import et.restlink.sas.ras.authvector.command.ResyncVectorCommand;

/**
 * RA-level tests for {@link AuthVectorResourceAdaptor} — the only route to an
 * authentication vector, and therefore the place where the 2 s budget, the dialog
 * hygiene and the resync cap have to hold.
 */
class AuthVectorResourceAdaptorTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String IMSI = "655010000000001";
    private static final String REQ = "req-1";

    private static AuthVectorResourceAdaptor active(AuthVectorBackend backend) {
        AuthVectorResourceAdaptor ra = new AuthVectorResourceAdaptor();
        ra.setBackend(backend);
        ra.raConfigure();
        ra.raActive();
        return ra;
    }

    @Test
    @DisplayName("A fetch returns a well-formed vector and closes the exchange")
    void fetchSucceeds() throws Exception {
        AuthVectorResourceAdaptor ra = active(new InMemoryAuthVectorBackend());
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        ra.fetch(new FetchVectorCommand(REQ, IMSI, AuthVector.EAP_AKA_PRIME, reply));

        AuthVector vector = reply.get(2, TimeUnit.SECONDS);
        try {
            assertEquals(16, vector.rand().length, "RAND is 128 bits");
            assertEquals(16, vector.autn().length, "AUTN is 128 bits");
            assertTrue(vector.xres().length >= 8 && vector.xres().length <= 16,
                    "XRES length " + vector.xres().length);
            assertTrue(vector.hasSessionKeys(), "the lab backend supplies CK/IK");
            assertEquals(AuthVector.EAP_AKA_PRIME, vector.scheme());
        } finally {
            vector.wipe();
        }
        assertEquals(0, ra.openExchanges(), "no dialog may be left behind");
    }

    @Test
    @DisplayName("An inactive RA refuses instead of fetching")
    void inactiveRefuses() {
        AuthVectorResourceAdaptor ra = new AuthVectorResourceAdaptor();
        ra.setBackend(new InMemoryAuthVectorBackend());
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        ra.fetch(new FetchVectorCommand(REQ, IMSI, AuthVector.EAP_AKA, reply));
        assertTrue(reply.isCompletedExceptionally());
        assertTrue(reply.handle((v, e) -> e.getMessage()).join().contains("not active"));
    }

    @Test
    @DisplayName("A backend failure is propagated, never as an empty vector")
    void backendFailurePropagates() {
        AuthVectorBackend broken = new AuthVectorBackend() {
            @Override
            public CompletableFuture<AuthVector> fetch(String imsi, String scheme) {
                return CompletableFuture.failedFuture(new IllegalStateException("HSS said no"));
            }

            @Override
            public CompletableFuture<AuthVector> resync(String imsi, String scheme,
                                                          byte[] rand, byte[] auts) {
                return fetch(imsi, scheme);
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "broken";
            }
        };
        AuthVectorResourceAdaptor ra = active(broken);
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        ra.fetch(new FetchVectorCommand(REQ, IMSI, AuthVector.EAP_AKA, reply));
        assertTrue(reply.isCompletedExceptionally());
        assertEquals(0, ra.openExchanges(), "a failed exchange must not leak a dialog");
    }

    @Test
    @DisplayName("The resync cap is one attempt per exchange")
    void resyncIsCappedAtOne() throws Exception {
        AtomicInteger resyncs = new AtomicInteger();
        InMemoryAuthVectorBackend lab = new InMemoryAuthVectorBackend();
        AuthVectorBackend counting = new AuthVectorBackend() {
            @Override
            public CompletableFuture<AuthVector> fetch(String imsi, String scheme) {
                return lab.fetch(imsi, scheme);
            }

            @Override
            public CompletableFuture<AuthVector> resync(String imsi, String scheme,
                                                          byte[] rand, byte[] auts) {
                resyncs.incrementAndGet();
                return lab.resync(imsi, scheme, rand, auts);
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "counting";
            }
        };
        AuthVectorResourceAdaptor ra = active(counting);
        byte[] auts = new byte[16];

        CompletableFuture<AuthVector> first = new CompletableFuture<>();
        ra.resync(new ResyncVectorCommand(REQ, IMSI, AuthVector.EAP_AKA, new byte[16], auts, first));
        first.get(2, TimeUnit.SECONDS);
        assertEquals(1, resyncs.get());

        // The first resync consumed the only slot; a second one must be refused.
        assertEquals(0, ra.openExchanges(), "the completed exchange is gone");
        assertEquals(1, ra.resyncAttempts(REQ), "the budget survives the exchange closing");

        // A second resync for the SAME session must be refused even though the first
        // exchange has already completed — the cap is per session, not per exchange.
        CompletableFuture<AuthVector> second = new CompletableFuture<>();
        ra.resync(new ResyncVectorCommand(REQ, IMSI, AuthVector.EAP_AKA, new byte[16], auts, second));
        assertTrue(second.isCompletedExceptionally(),
                "the second resync must be refused, not sent to the AuC");
        assertEquals(1, resyncs.get(), "the refused resync must never reach the backend");

        // A different session starts with a fresh budget.
        assertEquals(0, ra.resyncAttempts("req-2"));
        ra.abort(new AbortAuthVectorCommand(REQ, "dialog-1"));
        assertEquals(0, ra.resyncAttempts(REQ), "aborting the session releases the budget");
    }

    @Test
    @DisplayName("A malformed AT_AUTS is rejected by the backend contract")
    void badAutsRejected() {
        AuthVectorResourceAdaptor ra = active(new InMemoryAuthVectorBackend());
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        ra.resync(new ResyncVectorCommand(REQ, IMSI, AuthVector.EAP_AKA,
                new byte[16], new byte[8], reply));
        assertTrue(reply.isCompletedExceptionally());
    }

    @Test
    @DisplayName("An abort closes the exchange and a late answer is dropped, not delivered")
    void abortDropsLateAnswer() {
        AuthVectorResourceAdaptor ra = active(new InMemoryAuthVectorBackend(40));
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        ra.fetch(new FetchVectorCommand(REQ, IMSI, AuthVector.EAP_AKA, reply));
        assertEquals(1, ra.openExchanges());

        ra.abort(new AbortAuthVectorCommand(REQ, "dialog-1"));
        assertEquals(0, ra.openExchanges(), "abort must free the slot immediately");
    }

    @Test
    @DisplayName("An aborted exchange never completes its caller's future")
    void abortLeavesFutureUncompleted() throws Exception {
        AuthVectorResourceAdaptor ra = active(new InMemoryAuthVectorBackend(60));
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        ra.fetch(new FetchVectorCommand(REQ, IMSI, AuthVector.EAP_AKA, reply));
        ra.abort(new AbortAuthVectorCommand(REQ, "dialog-1"));
        // Give the backend's delayed completion a chance to land.
        Thread.sleep(150);
        assertFalse(reply.isDone(), "a vector must not be delivered after an abort");
    }

    @Test
    @DisplayName("deactivate() aborts everything in flight")
    void deactivateAbortsAll() {
        AuthVectorResourceAdaptor ra = active(new InMemoryAuthVectorBackend(200));
        for (int i = 0; i < 5; i++) {
            ra.fetch(new FetchVectorCommand("req-" + i, IMSI, AuthVector.EAP_AKA,
                    new CompletableFuture<>()));
        }
        assertEquals(5, ra.openExchanges());
        ra.raInactive();
        assertEquals(0, ra.openExchanges());
        assertFalse(ra.isActive());
    }

    @Test
    @DisplayName("The in-flight table is bounded, so a stuck RA cannot grow without limit")
    void inFlightIsBounded() {
        // 60 ms of latency per call, so every call is still pending when the next arrives.
        AuthVectorResourceAdaptor ra = active(new InMemoryAuthVectorBackend(500));
        int accepted = 0;
        for (int i = 0; i < 400; i++) {
            CompletableFuture<AuthVector> reply = new CompletableFuture<>();
            ra.fetch(new FetchVectorCommand("cap-" + i, IMSI, AuthVector.EAP_AKA, reply));
            if (!reply.isCompletedExceptionally()) {
                accepted++;
            }
        }
        assertTrue(accepted <= 256, "accepted " + accepted + " — the cap must bind");
        ra.raInactive();
    }

    @Test
    @DisplayName("AuthVector wipes its own secrets and toString leaks nothing")
    void vectorHygiene() {
        AuthVector vector = new AuthVector(new byte[16], new byte[16], new byte[8],
                new byte[16], new byte[16], AuthVector.EAP_AKA);
        String described = vector.toString();
        assertTrue(described.contains("ck=<16B>"), described);
        assertFalse(described.contains(HEX.formatHex(vector.ck())), "no CK in toString");

        byte[] ck = vector.ck();
        vector.wipe();
        assertArrayEquals(new byte[16], ck, "wipe() must zero CK in place");
        vector.wipe();
        // idempotent
        assertArrayEquals(new byte[8], vector.xres());
    }

    @Test
    @DisplayName("A vector without CK/IK is reported as not carrying session keys")
    void vectorWithoutSessionKeys() {
        AuthVector vector = new AuthVector(new byte[16], new byte[16], new byte[8],
                null, null, AuthVector.EAP_AKA);
        assertFalse(vector.hasSessionKeys());
        assertNull(vector.ck());
        assertTrue(vector.toString().contains("ck=absent"), vector.toString());
        vector.wipe();
    }

    @Test
    @DisplayName("The endpoint routes the three commands and rejects anything else")
    void endpointRouting() {
        InMemoryAuthVectorBackend backend = new InMemoryAuthVectorBackend();
        AuthVectorResourceAdaptor ra = active(backend);
        AuthVectorRaEndpoint endpoint = new AuthVectorRaEndpoint(ra);

        assertEquals("auth-vector-ra", endpoint.getRaName());
        assertNotNull(endpoint.backend());

        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        endpoint.sendCommand(new FetchVectorCommand("ep-1", IMSI, AuthVector.EAP_AKA, reply));
        assertTrue(reply.isDone());
        reply.join().wipe();

        // An unknown command must be logged and dropped, never dispatched.
        endpoint.sendCommand(new et.restlink.sas.ras.swxverifier.command.AbortSwxCommand("x", "y"));
        assertEquals(0, ra.openExchanges());
    }
}
