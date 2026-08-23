/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pilot SWx verifier: simulates the 3GPP AAA ↔ HSS EAP-AKA exchange
 * (TS 29.273 SWx / TS 33.402). Wi-Fi subscribers are verified via
 * EAP-AKA auth vectors fetched from the own HSS.
 *
 * <p>Fail-closed outcomes:</p>
 * <ul>
 *   <li>missing subscriber → PURGED</li>
 *   <li>IMSI mismatch → SIM_SWAP_SUSPECT</li>
 *   <li>non-WIFI access tech → VERIFY_ERROR</li>
 * </ul>
 */
public final class InMemorySwxVerifierBackend implements SwxVerifierBackend {

    /** SIM-swap cooldown for Wi-Fi EAP-AKA identity. */
    public static final long SWAP_COOLDOWN_MS = 24L * 60L * 60L * 1000L;

    private record SwxRecord(String imsi, boolean eapAkaRegistered,
                              long lastImsiChangeEpochMs, String aaaRegion) {}

    private final Map<String, SwxRecord> subscribers = new ConcurrentHashMap<>();
    private final long simulatedLatencyMs;
    private final String expectedRegion;

    public InMemorySwxVerifierBackend() {
        this(0L, "AA");
    }

    public InMemorySwxVerifierBackend(long simulatedLatencyMs, String expectedRegion) {
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.expectedRegion = expectedRegion;
    }

    public void seed(String msisdn, String imsi, boolean eapAkaRegistered,
                     long lastImsiChangeEpochMs, String aaaRegion) {
        subscribers.put(msisdn, new SwxRecord(imsi, eapAkaRegistered, lastImsiChangeEpochMs, aaaRegion));
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
            if (!NO_INTERCONNECT_SWX || !NEVER_IP_TO_MSISDN) {
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX");
            }
            if (accessTech != AccessTech.WIFI) {
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX");
            }
            SwxRecord r = subscribers.get(msisdn);
            if (r == null) {
                return VerificationEvidence.fail(FallbackReason.PURGED, "SWX-EAP");
            }
            // B2: claimed-IMSI binding is ACTIVE evidence — a present-but-
            // mismatched IMSI (e.g. from the TS.43 entitlement token) means the
            // SIM no longer matches the claimed identity ⇒ fail closed.
            if (imsi != null && !imsi.isBlank() && !imsi.equals(r.imsi())) {
                return VerificationEvidence.fail(FallbackReason.SIM_SWAP_SUSPECT, "SWX-EAP");
            }
            boolean reachable = r.eapAkaRegistered();
            boolean notSimSwapped = (nowMs - r.lastImsiChangeEpochMs()) >= SWAP_COOLDOWN_MS;
            boolean locationPlausible = expectedRegion.equalsIgnoreCase(r.aaaRegion());
            return VerificationEvidence.ok(reachable, notSimSwapped, locationPlausible, "SWX-EAP-AKA");
        });
    }
}
