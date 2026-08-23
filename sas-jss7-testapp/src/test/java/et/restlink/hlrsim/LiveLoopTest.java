/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Timeout;

/**
 * Live loop over real loopback SCTP: client stack (replica of the SAS MAP
 * verifier dialogs) against the simulated home HLR server stack, both in one
 * JVM but two independent jSS7 stacks talking M3UA/SCCP/TCAP/MAP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(60)
class LiveLoopTest {

    private static final String HOST = "127.0.0.1";
    private static final String IMSI = "636012000000001";

    private static HlrSimulator sim;
    private static LiveMapClient client;

    @BeforeAll
    void startStacks() throws Exception {
        int hlrPort = freePort();
        int clientPort = freePort();

        sim = new HlrSimulator(HOST, hlrPort, clientPort);
        sim.start();

        client = new LiveMapClient(HOST, clientPort, hlrPort);
        client.start();

        // Wait for both SCTP associations to reach ESTABLISHED (max ~15 s).
        long deadline = System.currentTimeMillis() + 15_000;
        while (!(sim.associationConnected() && client.associationConnected())) {
            assertTrue(System.currentTimeMillis() < deadline,
                    () -> "SCTP association not established in time (server=" + sim.associationConnected()
                            + " client=" + client.associationConnected() + ")");
            Thread.sleep(100);
        }
    }

    @AfterAll
    void stopStacks() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (sim != null) {
            sim.stop();
        }
        cleanPersistedState();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** jSS7 layers persist XML state into CWD; tests keep the tree clean. */
    private static void cleanPersistedState() throws Exception {
        try (var files = Files.list(Path.of(""))) {
            files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("hlr-sim-") || name.startsWith("sas-live-");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                            // best effort
                        }
                    });
        }
    }

    @Test
    @Order(1)
    void psiAnswersSubscriberStateAndLocationWithinBudget() throws Exception {
        sim.state().setAttached(true);

        long start = System.currentTimeMillis();
        var future = client.runPsi(IMSI);
        LiveMapClient.PsiResult result = future.get(2_000, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.ok(), () -> "PSI failed: " + result.errorText());
        assertTrue(result.reachable(), "subscriberState should be assumedIdle");
        assertTrue(result.locationPlausible(), "locationInformation should be present");
        assertTrue(elapsed <= 2_000, "PSI took " + elapsed + " ms (budget 2000 ms)");
        assertTrue(elapsed <= 2_000 && elapsed > 0, "sanity: measured round trip");
    }

    @Test
    @Order(2)
    void saiAnswersExactlyOneAuthSetWithinBudget() throws Exception {
        sim.state().setAttached(true);
        sim.state().setVectors(1);

        long start = System.currentTimeMillis();
        var future = client.runSai(IMSI);
        var response = future.get(2_000, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(response.getAuthenticationSetList() != null
                        || response.getEpsAuthenticationSetList() != null,
                "authenticationSetList missing");
        var triplets = response.getAuthenticationSetList().getTripletList();
        assertTrue(triplets != null, "expected GSM triplets (v3 tripletList choice)");
        assertEquals(1, triplets.getAuthenticationTriplets().size(), "exactly one vector requested");
        var triplet = triplets.getAuthenticationTriplets().get(0);
        assertEquals(16, triplet.getRand().length, "RAND must be 16 bytes");
        assertEquals(4, triplet.getSres().length, "SRES must be 4 bytes");
        assertEquals(8, triplet.getKc().length, "Kc must be 8 bytes");
        assertTrue(elapsed <= 2_000, "SAI took " + elapsed + " ms (budget 2000 ms)");
    }

    @Test
    @Order(3)
    void atiIsNeverAnsweredAndDialogAbortsCleanly() throws Exception {
        sim.state().setAttached(true);

        var handle = client.runAti(IMSI);
        assertThrows(TimeoutException.class,
                () -> handle.answered().get(1_500, TimeUnit.MILLISECONDS),
                "ATI must NOT be answered within 1500 ms (FS.11 Cat 1)");

        // No answer arrived; abort the dialog like the fail-closed verifier does.
        client.abortDialog(handle.dialog());

        // Give any stray answer a grace period — it must stay silent.
        assertThrows(TimeoutException.class,
                () -> handle.answered().get(500, TimeUnit.MILLISECONDS),
                "ATI answered late — FS.11 regression");

        boolean droppedLogged = sim.log().snapshot().stream()
                .anyMatch(e -> "anyTimeInterrogation".equals(e.operation())
                        && "DROPPED".equals(e.result()));
        assertTrue(droppedLogged, "sim must log the dropped ATI");
    }

    @Test
    @Order(4)
    void detachedPsiReturnsErrorAndClientFailsClosed() throws Exception {
        sim.state().setAttached(false);
        try {
            var future = client.runPsi(IMSI);
            LiveMapClient.PsiResult result = future.get(2_000, TimeUnit.MILLISECONDS);

            assertFalse(result.ok(), "detached PSI must map to failure");
            assertFalse(result.reachable(), "no reachable evidence on error path");
            assertFalse(result.locationPlausible(), "no location evidence on error path");
            assertTrue(result.errorText() != null && result.errorText().startsWith("error:"),
                    () -> "expected MAP error component, got: " + result.errorText());
        } finally {
            sim.state().setAttached(true);
        }
    }

    @Test
    @Order(5)
    void zeroVectorSaiReturnsErrorAndClientFailsClosed() throws Exception {
        sim.state().setVectors(0);
        try {
            var future = client.runSai(IMSI);
            assertThrows(Exception.class,
                    () -> future.get(2_000, TimeUnit.MILLISECONDS),
                    "vectors=0 must yield an error component, never an empty set");
        } finally {
            sim.state().setVectors(1);
        }
    }
}
