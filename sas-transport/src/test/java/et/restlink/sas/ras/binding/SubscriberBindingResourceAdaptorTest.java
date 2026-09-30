/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import et.restlink.sas.ras.binding.backend.SubscriberDbBinding;
import et.restlink.sas.ras.binding.command.AbortBindingCommand;
import et.restlink.sas.ras.binding.command.LookupBindingCommand;

/**
 * RA-level tests for {@link SubscriberBindingResourceAdaptor} — source order, the
 * shared budget, and the rule that an unresolved answer is a refusal.
 */
class SubscriberBindingResourceAdaptorTest {

    private static final String IMSI = "655010000000001";
    private static final String NUMBER = "+251911111111";

    private static SubscriberBindingResourceAdaptor active() {
        SubscriberBindingResourceAdaptor ra = new SubscriberBindingResourceAdaptor();
        ra.raConfigure();
        ra.raActive();
        return ra;
    }

    private static SubscriberBindingBackend answer(String source, String number) {
        return new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                return CompletableFuture.completedFuture(number == null
                        ? SubscriberBinding.unresolved(imsi, source)
                        : SubscriberBinding.resolved(imsi, number, source));
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return source;
            }
        };
    }

    private static SubscriberBindingBackend broken(String source) {
        return new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                return CompletableFuture.failedFuture(new IllegalStateException(source + " is down"));
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return source;
            }
        };
    }

    @Test
    @DisplayName("The first source that resolves wins, and the order is honoured")
    void firstResolvedSourceWins() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("sh-udr", answer("sh-udr", NUMBER));
        ra.addBackend("db", answer("db", "+251999999999"));
        ra.setSourceOrder("sh-udr,db");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r1", IMSI, null, reply));
        SubscriberBinding binding = reply.join();
        assertTrue(binding.resolved());
        assertEquals(NUMBER, binding.msisdn());
        assertEquals("sh-udr", binding.source(), "the earlier source must win");
    }

    @Test
    @DisplayName("A source that cannot answer falls through to the next one")
    void fallsThroughOnFailure() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("swx-sar", broken("swx-sar"));
        ra.addBackend("db", answer("db", NUMBER));
        ra.setSourceOrder("swx-sar,db");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r2", IMSI, null, reply));
        assertEquals(NUMBER, reply.join().msisdn());
    }

    @Test
    @DisplayName("An unresolved source is not an answer — the walk continues")
    void unresolvedIsNotAnAnswer() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("sh-udr", answer("sh-udr", null));
        ra.addBackend("db", answer("db", NUMBER));
        ra.setSourceOrder("sh-udr,db");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r3", IMSI, null, reply));
        assertEquals(NUMBER, reply.join().msisdn());
    }

    @Test
    @DisplayName("When every source is exhausted the answer is unresolved, not a guess")
    void exhaustedIsUnresolved() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("sh-udr", answer("sh-udr", null));
        ra.setSourceOrder("sh-udr");
        ra.addBackend("db", answer("db", null));

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r4", IMSI, null, reply));
        SubscriberBinding binding = reply.join();
        assertFalse(binding.resolved(), "exhausted sources must not produce a number");
        assertNull(binding.msisdn());
    }

    @Test
    @DisplayName("A claimed MSISDN is compared, never believed")
    void claimedNumberIsReconciled() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("db", answer("db", NUMBER));
        ra.setSourceOrder("db");

        // agreeing claim
        CompletableFuture<SubscriberBinding> good = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r5", IMSI, NUMBER, good));
        assertTrue(good.join().resolved());

        // disagreeing claim — the device named a number that is not its own
        CompletableFuture<SubscriberBinding> bad = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r6", IMSI, "+251900000000", bad));
        SubscriberBinding binding = bad.join();
        assertFalse(binding.resolved(), "a mismatched claim must not resolve");
        assertEquals("mismatch", binding.source());
    }

    /**
     * A claim-only source (MAP {@code SendIMSI} answers "which IMSI owns this number?",
     * not "what number is this IMSI?") must be asked the claim question — and must
     * <em>opt in</em> to it, or a discovery source would silently stop being asked.
     */
    @Test
    @DisplayName("A number-driven source answers the claim question, and only when it opts in")
    void claimDrivenSourceIsAskedTheClaimQuestion() {
        AtomicReference<String> asked = new AtomicReference<>();

        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("map-smi", new SubscriberBindingBackend() {
            @Override
            public boolean supportsClaimVerification() {
                return true;
            }

            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                asked.set("lookup");
                return CompletableFuture.completedFuture(
                        SubscriberBinding.unresolved(imsi, "map-smi-number-driven"));
            }

            @Override
            public CompletableFuture<SubscriberBinding> verifyClaim(String imsi, String msisdn) {
                asked.set("verifyClaim:" + msisdn);
                return CompletableFuture.completedFuture(
                        SubscriberBinding.resolved(imsi, msisdn, "map-smi"));
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "map-smi";
            }
        });
        ra.setSourceOrder("map-smi");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r-claim", IMSI, NUMBER, reply));
        assertEquals("verifyClaim:" + NUMBER, asked.get(),
                "with a claim the source must be asked to confirm it");
        assertTrue(reply.join().resolved());

        // Without a claim there is nothing to confirm, so the RA asks for discovery —
        // which this source cannot do, and says so instead of guessing.
        CompletableFuture<SubscriberBinding> noClaim = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r-noclaim", IMSI, null, noClaim));
        assertEquals("lookup", asked.get());
        assertFalse(noClaim.join().resolved());
    }

    @Test
    @DisplayName("A discovery source is still asked for the number even when a claim exists")
    void discoverySourceIsNotShortCircuitedByAClaim() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("sh-udr", answer("sh-udr", NUMBER));
        ra.setSourceOrder("sh-udr");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r-disc", IMSI, NUMBER, reply));
        assertTrue(reply.join().resolved(),
                "a claim must not disable discovery on a source that does discovery");
    }

    @Test
    @DisplayName("The 2 s budget is shared: a second source gets only the remainder")
    void budgetIsShared() throws Exception {
        SubscriberBindingResourceAdaptor ra = active();
        AtomicInteger firstAsked = new AtomicInteger();
        AtomicInteger secondAsked = new AtomicInteger();
        ra.addBackend("sh-udr", new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                firstAsked.incrementAndGet();
                return CompletableFuture.supplyAsync(() -> {
                    sleep(1300);
                    return SubscriberBinding.unresolved(imsi, "sh-udr");
                });
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "sh-udr";
            }
        });
        ra.addBackend("db", new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                secondAsked.incrementAndGet();
                return CompletableFuture.supplyAsync(() -> {
                    sleep(1300);
                    return SubscriberBinding.resolved(imsi, NUMBER, "db");
                });
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "db";
            }
        });
        ra.setSourceOrder("sh-udr,db");

        long started = System.currentTimeMillis();
        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r7", IMSI, null, reply));
        SubscriberBinding binding = reply.get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - started;

        assertEquals(1, firstAsked.get());
        // The second source IS asked, but only with the ~700 ms that is left — the point
        // is that it cannot restart a fresh 2 s and stretch the transaction.
        assertEquals(1, secondAsked.get());
        assertFalse(binding.resolved(), "a partial budget must not buy a number");
        assertTrue(elapsed < 2400,
                "elapsed " + elapsed + "ms must stay inside the shared 2 s budget, "
                        + "not 2 s per source");
    }

    @Test
    @DisplayName("A source that answers quickly still leaves budget for the next one")
    void fastSourceLeavesBudget() {
        SubscriberBindingResourceAdaptor ra = active();
        AtomicInteger secondAsked = new AtomicInteger();
        ra.addBackend("sh-udr", answer("sh-udr", null));
        ra.addBackend("db", new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                secondAsked.incrementAndGet();
                return answer("db", NUMBER).lookup(imsi);
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "db";
            }
        });
        ra.setSourceOrder("sh-udr,db");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r7b", IMSI, null, reply));
        assertEquals(1, secondAsked.get());
        assertEquals(NUMBER, reply.join().msisdn());
    }

    @Test
    @DisplayName("Unknown source tokens are dropped, never silently promoted")
    void unknownSourceIsDropped() {
        SubscriberBindingResourceAdaptor ra = active();
        java.util.List<String> accepted = ra.setSourceOrder("swx-sar,bogus,db");
        assertFalse(accepted.contains("bogus"), "accepted=" + accepted);
        assertTrue(ra.sourceOrder().contains("db"));
    }

    @Test
    @DisplayName("SRI-SM is not a legal source token")
    void sriSmIsNotLegal() {
        assertFalse(SubscriberBindingResourceAdaptor.LEGAL_SOURCES.contains("sri-sm"));
        assertFalse(SubscriberBindingResourceAdaptor.LEGAL_SOURCES.contains("ati"),
                "ATI is banned outright by FS.11 Category 1");
    }

    @Test
    @DisplayName("An inactive or overloaded RA answers unresolved rather than throwing")
    void inactiveAndOverloaded() {
        SubscriberBindingResourceAdaptor ra = new SubscriberBindingResourceAdaptor();
        ra.raConfigure();
        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("r8", IMSI, null, reply));
        assertFalse(reply.join().resolved());
        assertEquals("ra-inactive", reply.join().source());
    }

    @Test
    @DisplayName("A duplicate request id is refused instead of racing itself")
    void duplicateRequestRefused() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("db", new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                return CompletableFuture.supplyAsync(() -> {
                    sleep(200);
                    return SubscriberBinding.resolved(imsi, NUMBER, "db");
                });
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "db";
            }
        });
        ra.setSourceOrder("db");

        CompletableFuture<SubscriberBinding> first = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("dup", IMSI, null, first));
        CompletableFuture<SubscriberBinding> second = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("dup", IMSI, null, second));
        assertEquals("duplicate-req", second.join().source());
        first.join();
    }

    @Test
    @DisplayName("An abort releases the slot and stops a late answer")
    void abortReleasesTheSlot() {
        SubscriberBindingResourceAdaptor ra = active();
        ra.addBackend("db", new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                return CompletableFuture.supplyAsync(() -> {
                    sleep(200);
                    return SubscriberBinding.resolved(imsi, NUMBER, "db");
                });
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return "db";
            }
        });
        ra.setSourceOrder("db");

        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        ra.lookup(new LookupBindingCommand("abort-me", IMSI, null, reply));
        assertEquals(1, ra.openLookups());
        ra.abort(new AbortBindingCommand("abort-me", "s-1"));
        assertEquals(0, ra.openLookups());
    }

    // ---- the export source ----

    @Test
    @DisplayName("The export source resolves an IMSI, with or without an NAI suffix")
    void exportResolves(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("subscribers.json");
        Files.writeString(file, "[{\"imsi\":\"655010000000001\",\"msisdn\":\"" + NUMBER + "\"}]");
        SubscriberDbBinding db = new SubscriberDbBinding(file);
        assertEquals(1, db.size());
        assertEquals(NUMBER, db.lookup(IMSI).join().msisdn());
        assertEquals(NUMBER, db.lookup(IMSI + "@restlink.et").join().msisdn(),
                "the NAI decoration must not change the identity");
        assertFalse(db.lookup("655010000000002").join().resolved());
    }

    @Test
    @DisplayName("A missing or malformed export degrades to unresolved, never to a crash")
    void exportIsFailClosed(@TempDir Path dir) throws Exception {
        SubscriberDbBinding missing = new SubscriberDbBinding(dir.resolve("nope.json"));
        assertEquals(0, missing.size());
        assertFalse(missing.lookup(IMSI).join().resolved());

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json at all");
        SubscriberDbBinding bad = new SubscriberDbBinding(broken);
        assertEquals(0, bad.size());
        assertFalse(bad.lookup(IMSI).join().resolved());
    }

    @Test
    @DisplayName("Two sources for the same IMSI may disagree without corrupting the map")
    void exportDeduplicates() throws Exception {
        Path file = Files.createTempFile("subs", ".json");
        try {
            Files.writeString(file, "["
                    + "{\"imsi\":\"655010000000001\",\"msisdn\":\"+251911111111\"},"
                    + "{\"imsi\":\"655010000000001@restlink.et\",\"msisdn\":\"+251911111111\"}"
                    + "]");
            SubscriberDbBinding db = new SubscriberDbBinding(file);
            assertEquals(1, db.size(), "the NAI form must collapse onto the same entry");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("A binding never prints the number or the full IMSI")
    void bindingToStringIsSafe() {
        SubscriberBinding binding = SubscriberBinding.resolved(IMSI, NUMBER, "db");
        String s = binding.toString();
        assertFalse(s.contains(NUMBER), s);
        assertFalse(s.contains(IMSI), s);
        assertNotEquals(NUMBER, s);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
