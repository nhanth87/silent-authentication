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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pilot MAP verifier: a seeded subscriber table simulating PSI (reachable +
 * location) and SAI (SIM-swap freshness). Never uses ATI (intra-net HLR only).
 *
 * <p>Access-tech dispatch (fail-closed):</p>
 * <ul>
 *   <li>GS_2G3G → MAP PSI + SAI (this backend).</li>
 *   <li>LTE / NR → Diameter S6a verifier RA (not this backend).</li>
 *   <li>WIFI   → SWx/EAP-AKA TS.43 (P1 — returns {@link FallbackReason#WIFI_NOT_READY}).</li>
 * </ul>
 */
public final class InMemoryMapVerifierBackend implements MapVerifierBackend {

    /** SIM-swap cooldown: IMSI change younger than this ⇒ suspect. */
    public static final long SWAP_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    private record Subscriber(String imsi, boolean attached, long lastImsiChangeEpochMs,
                              String vlrRegion) {
    }

    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final long simulatedLatencyMs;
    private final String expectedRegion;

    public InMemoryMapVerifierBackend() {
        this(0L, "AA");
    }

    public InMemoryMapVerifierBackend(long simulatedLatencyMs, String expectedRegion) {
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.expectedRegion = expectedRegion;
    }

    public void seed(String msisdn, String imsi, boolean attached,
                     long lastImsiChangeEpochMs, String vlrRegion) {
        subscribers.put(msisdn, new Subscriber(imsi, attached, lastImsiChangeEpochMs, vlrRegion));
    }

    @Override
    public CompletableFuture<VerificationEvidence> verify(String msisdn,
                                                          String imsi,
                                                          AccessTech accessTech,
                                                          long nowMs) {
        return CompletableFuture.supplyAsync(() -> {
            if (simulatedLatencyMs > 0L) {
                try {
                    Thread.sleep(simulatedLatencyMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!NO_INTERCONNECT_ATI) {
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP");
            }
            if (accessTech != AccessTech.GS_2G3G) {
                FallbackReason why = switch (accessTech) {
                    case WIFI -> FallbackReason.WIFI_NOT_READY;
                    default -> FallbackReason.VERIFY_ERROR;
                };
                return VerificationEvidence.fail(why, "MAP");
            }

            Subscriber s = subscribers.get(msisdn);
            if (s == null) {
                // PSI on an unknown subscriber returns an error component (fail closed).
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP-PSI");
            }
            if (imsi != null && !imsi.equals(s.imsi())) {
                // Resolved IMSI ≠ HLR IMSI ⇒ SIM-swap suspect (SAI, FS.11 Cat 3.2).
                return VerificationEvidence.fail(FallbackReason.SIM_SWAP_SUSPECT, "MAP-SAI");
            }

            boolean reachable = s.attached();
            boolean notSimSwapped = (nowMs - s.lastImsiChangeEpochMs()) >= SWAP_COOLDOWN_MS;
            boolean locationPlausible = expectedRegion.equalsIgnoreCase(s.vlrRegion());

            // PSI (70) + SAI (56) — never ATI (71).
            return VerificationEvidence.ok(reachable, notSimSwapped, locationPlausible,
                    "MAP-PSI+SAI");
        });
    }
}