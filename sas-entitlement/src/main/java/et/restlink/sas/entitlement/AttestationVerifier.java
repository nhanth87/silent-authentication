/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AAA/EAP attestation for {@code POST /entitlement/issue} (audit gap B1):
 * proves the caller is the operator 3GPP AAA that just completed EAP-AKA,
 * not merely a holder of the API key.
 *
 * <p>Contract (only enforced when
 * {@code sas.entitlement.issue-attestation-required=true}): the caller sends</p>
 * <ul>
 *   <li>{@code X-Sas-Attestation-Ts} — epoch milliseconds, accepted within
 *       ±60 s of server time, single-use;</li>
 *   <li>{@code X-Sas-Attestation-Mac} — lowercase-hex
 *       HMAC-SHA256(secret, msisdn|imsi|eapMethod|ts) with empty-string slots
 *       for absent fields and the canonical decimal ts.</li>
 * </ul>
 *
 * <p>Fail-closed: missing/mismatched MAC → {@code ATTESTATION_INVALID},
 * stale timestamp → {@code ATTESTATION_EXPIRED}, reuse →
 * {@code ATTESTATION_REPLAY} (all 401), and required-but-unconfigured secret →
 * {@code ATTESTATION_MISCONFIGURED} (503, rejects everything). With
 * attestation disabled the verifier is pass-through. The compare is
 * constant-time; the replay set is TTL-evicting and size-bounded.</p>
 */
@ApplicationScoped
public class AttestationVerifier {

    private static final Logger LOG = LogManager.getLogger(AttestationVerifier.class);

    private static final String HMAC_ALGO = "HmacSHA256";

    /** Accepted |client-ts − server-now| window. */
    public static final long CLOCK_SKEW_MS = 60_000L;

    /** Replay memory outlives the skew window so an expired ts cannot be reused. */
    private static final long REPLAY_TTL_MS = 2 * CLOCK_SKEW_MS;

    /** Hard cap on remembered attestations (bounded memory under flooding). */
    private static final int MAX_SEEN = 10_000;

    @Inject
    EntitlementConfig config;

    /** Attestation rejection mapped straight onto the error response. */
    public record Rejection(int status, String code, String message) {}

    /** Canonical single-use ledger: mac → first-seen epoch ms. */
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * Validate the issue attestation headers.
     *
     * @return null when acceptable (or attestation is disabled), else the
     *         rejection to answer with
     */
    public Rejection verify(String msisdn, String imsi, String eapMethod,
                            String tsHeader, String macHeader) {
        if (!config.issueAttestationRequired()) {
            return null;
        }
        String secret = config.issueAttestationSecret();
        if (secret.isBlank()) {
            LOG.error("issue attestation required but "
                    + "sas.entitlement.issue-attestation-secret is blank — rejecting all");
            return new Rejection(503, "ATTESTATION_MISCONFIGURED",
                    "attestation is required but its secret is not configured");
        }
        if (tsHeader == null || tsHeader.isBlank() || macHeader == null || macHeader.isBlank()) {
            return invalid("X-Sas-Attestation-Ts and X-Sas-Attestation-Mac are required");
        }
        long ts;
        try {
            ts = Long.parseLong(tsHeader.trim());
        } catch (NumberFormatException e) {
            return invalid("X-Sas-Attestation-Ts must be epoch milliseconds");
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > CLOCK_SKEW_MS) {
            LOG.warn("Issue attestation timestamp outside the ±{}ms window", CLOCK_SKEW_MS);
            return new Rejection(401, "ATTESTATION_EXPIRED",
                    "attestation timestamp outside the ±60s window");
        }
        String expected = hmacSha256Hex(secret,
                inputString(msisdn, imsi, eapMethod, ts));
        String presented = macHeader.trim();
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            return invalid("attestation MAC mismatch");
        }
        // Single-use: exact MAC covers identity+ts, so it keys the ledger.
        evict(now);
        if (seen.putIfAbsent(presented, now) != null) {
            LOG.warn("Issue attestation replay detected");
            return new Rejection(401, "ATTESTATION_REPLAY",
                    "attestation already used (single-use)");
        }
        return null;
    }

    /** Canonical MAC input: msisdn|imsi(or empty)|eapMethod|ts(decimal). */
    public static String inputString(String msisdn, String imsi, String eapMethod,
                                     long tsEpochMs) {
        return orEmpty(msisdn) + "|" + orEmpty(imsi) + "|" + orEmpty(eapMethod)
                + "|" + Long.toString(tsEpochMs);
    }

    static String hmacSha256Hex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Rejection invalid(String message) {
        return new Rejection(401, "ATTESTATION_INVALID", message);
    }

    /** Drop aged entries; shed oldest when still at the hard cap. */
    private void evict(long now) {
        seen.values().removeIf(seenAt -> now - seenAt > REPLAY_TTL_MS);
        while (seen.size() >= MAX_SEEN) {
            Map.Entry<String, Long> eldest = null;
            for (Map.Entry<String, Long> e : seen.entrySet()) {
                if (eldest == null || e.getValue() < eldest.getValue()) {
                    eldest = e;
                }
            }
            if (eldest == null) {
                return;
            }
            seen.remove(eldest.getKey());
        }
    }
}
