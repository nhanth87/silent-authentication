/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pilot S6a verifier: a seeded HSS table simulating the ULR/ULA (attachment
 * liveness, op 316) and AIR/AIA (SIM-swap freshness, op 318) behaviours of
 * TS 29.272. Never uses ATI and never maps IP → MSISDN.
 *
 * <p>Fail-closed outcomes exercised by this backend:</p>
 * <ul>
 *   <li>missing / purged record → {@link FallbackReason#PURGED} (PUR/PUA, 321).</li>
 *   <li>resolved IMSI ≠ HSS IMSI → {@link FallbackReason#SIM_SWAP_SUSPECT} (AIR, 318).</li>
 *   <li>fresh IMSI change (&lt; {@link #SWAP_COOLDOWN_MS}) → SIM-swap suspect.</li>
 *   <li>non-LTE/NR access tech → {@link FallbackReason#VERIFY_ERROR} (this backend
 *       only speaks S6a; Wi-Fi rides SWm/SWx per TS 33.402).</li>
 * </ul>
 */
public final class InMemoryS6aVerifierBackend implements S6aVerifierBackend {

    /** SIM-swap cooldown: IMSI change younger than this ⇒ suspect (mirrors MAP). */
    public static final long SWAP_COOLDOWN_MS = 24L * 60L * 60L * 1000L;

    private record HssRecord(String imsi, boolean registered,
                             long lastImsiChangeEpochMs, String mmeRegion) {
    }

    private final Map<String, HssRecord> subscribers = new ConcurrentHashMap<>();
    private final long simulatedLatencyMs;
    private final String expectedRegion;

    public InMemoryS6aVerifierBackend() {
        this(0L, "AA");
    }

    public InMemoryS6aVerifierBackend(long simulatedLatencyMs, String expectedRegion) {
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.expectedRegion = expectedRegion;
    }

    public void seed(String msisdn, String imsi, boolean registered,
                     long lastImsiChangeEpochMs, String mmeRegion) {
        subscribers.put(msisdn, new HssRecord(imsi, registered, lastImsiChangeEpochMs, mmeRegion));
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
            if (!NO_INTERCONNECT_S6A || !NEVER_IP_TO_MSISDN) {
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A");
            }
            if (accessTech != AccessTech.LTE && accessTech != AccessTech.NR) {
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A");
            }

            HssRecord r = subscribers.get(msisdn);
            if (r == null) {
                // TS 29.272 PUR/PUA (321) — purged/unknown ⇒ fail closed.
                return VerificationEvidence.fail(FallbackReason.PURGED, "S6A-ULR");
            }
            if (imsi != null && !imsi.equals(r.imsi())) {
                // Resolved IMSI ≠ HSS IMSI ⇒ SIM-swap suspect (AIR/AIA, 318).
                return VerificationEvidence.fail(FallbackReason.SIM_SWAP_SUSPECT, "S6A-AIR");
            }

            // ULR/ULA (316): attached + registered ⇒ live.
            boolean reachable = r.registered();
            // AIR/AIA (318): EPS identity stable beyond the cooldown ⇒ no swap.
            boolean notSimSwapped = (nowMs - r.lastImsiChangeEpochMs()) >= SWAP_COOLDOWN_MS;
            boolean locationPlausible = expectedRegion.equalsIgnoreCase(r.mmeRegion());
            return VerificationEvidence.ok(reachable, notSimSwapped, locationPlausible,
                    "S6A-ULR+AIR");
        });
    }
}