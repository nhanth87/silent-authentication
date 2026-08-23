/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.fsm;

import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Weighted assurance scoring with per-risk-class thresholds (P2 missing item #8).
 *
 * <pre>
 * score = w1 * ipBindingFresh(bearerAge)
 *       + w2 * subscriberReachable
 *       + w3 * notSimSwapped
 *       + w4 * locationPlausible
 * </pre>
 *
 * <p>APPROVE iff {@code score >= threshold(riskClass)}. Weights and thresholds
 * are configurable per transaction risk class via {@code sas.assurance.*}.</p>
 *
 * <p><strong>Misconfiguration policy (fail-closed):</strong> {@link #fromMap}
 * throws {@link IllegalArgumentException} on any present-but-invalid value
 * (unparseable number, weight outside [0,1], weights not summing to 1.0,
 * threshold outside 0..100 or unordered). Callers catch it once, log an error
 * and fall back to {@link #defaults()} wholesale — a partially-loaded policy is
 * never used. Absent keys take the matching default (partial overrides allowed).</p>
 */
public final class AssurancePolicy {

    /** Bearer binding considered "fresh" below this age. */
    public static final long FRESH_BEARER_MS = 60_000L;

    /** Binding considered "stale" (half weight) below this age. */
    public static final long STALE_BEARER_MS = 300_000L;

    /** Runtime KV key: IP-binding-freshness weight ({@code double}, 0..1). */
    public static final String KEY_W_IP_FRESH = "sas.assurance.w-ip-fresh";

    /** Runtime KV key: subscriber-reachable weight ({@code double}, 0..1). */
    public static final String KEY_W_REACHABLE = "sas.assurance.w-reachable";

    /** Runtime KV key: not-SIM-swapped weight ({@code double}, 0..1). */
    public static final String KEY_W_NOT_SIM_SWAP = "sas.assurance.w-not-sim-swap";

    /** Runtime KV key: location-plausible weight ({@code double}, 0..1). */
    public static final String KEY_W_LOCATION = "sas.assurance.w-location";

    /** Runtime KV key: LOGIN threshold ({@code int}, 0..100 percent). */
    public static final String KEY_THRESHOLD_LOGIN = "sas.assurance.threshold-login";

    /** Runtime KV key: TRANSFER threshold ({@code int}, 0..100 percent). */
    public static final String KEY_THRESHOLD_TRANSFER = "sas.assurance.threshold-transfer";

    /** Runtime KV key: HIGH_VALUE threshold ({@code int}, 0..100 percent). */
    public static final String KEY_THRESHOLD_HIGH_VALUE = "sas.assurance.threshold-high-value";

    /** Transaction risk classes with distinct thresholds. */
    public enum RiskClass {
        /** Standard login — lowest bar. */
        LOGIN,
        /** Money transfer / payment — medium bar. */
        TRANSFER,
        /** High-value transaction / admin — highest bar. */
        HIGH_VALUE
    }

    private final double wIpFresh;
    private final double wReachable;
    private final double wNotSimSwap;
    private final double wLocation;
    private final double thresholdLogin;
    private final double thresholdTransfer;
    private final double thresholdHighValue;

    public AssurancePolicy(double wIpFresh,
                           double wReachable,
                           double wNotSimSwap,
                           double wLocation,
                           double thresholdLogin,
                           double thresholdTransfer,
                           double thresholdHighValue) {
        if (!isUnitWeight(wIpFresh) || !isUnitWeight(wReachable)
                || !isUnitWeight(wNotSimSwap) || !isUnitWeight(wLocation)) {
            throw new IllegalArgumentException(
                    "each weight must be within [0,1] (got " + wIpFresh + ", " + wReachable
                            + ", " + wNotSimSwap + ", " + wLocation + ")");
        }
        double sum = wIpFresh + wReachable + wNotSimSwap + wLocation;
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("weights must sum to 1.0 (was " + sum + ")");
        }
        if (!(thresholdLogin >= 0 && thresholdLogin <= 1)
                || !(thresholdTransfer >= 0 && thresholdTransfer <= 1)
                || !(thresholdHighValue >= 0 && thresholdHighValue <= 1)) {
            throw new IllegalArgumentException(
                    "thresholds must be within [0,1] (got " + thresholdLogin + ", "
                            + thresholdTransfer + ", " + thresholdHighValue + ")");
        }
        if (!(thresholdLogin <= thresholdTransfer && thresholdTransfer <= thresholdHighValue)) {
            throw new IllegalArgumentException(
                    "thresholds must be ordered LOGIN <= TRANSFER <= HIGH_VALUE (got "
                            + thresholdLogin + ", " + thresholdTransfer + ", "
                            + thresholdHighValue + ")");
        }
        this.wIpFresh = wIpFresh;
        this.wReachable = wReachable;
        this.wNotSimSwap = wNotSimSwap;
        this.wLocation = wLocation;
        this.thresholdLogin = thresholdLogin;
        this.thresholdTransfer = thresholdTransfer;
        this.thresholdHighValue = thresholdHighValue;
    }

    private static boolean isUnitWeight(double w) {
        return !Double.isNaN(w) && w >= 0.0 && w <= 1.0;
    }

    /** Design defaults (silent-auth-flow.md §6 sketch). */
    public static AssurancePolicy defaults() {
        return new AssurancePolicy(0.25, 0.30, 0.30, 0.15, 0.70, 0.80, 0.90);
    }

    /**
     * Read-only runtime KV getter ({@code null} return = key absent). Matches
     * {@code SasAdminRuntimeConfig::read} so callers can pass a method reference.
     */
    @FunctionalInterface
    public interface KvSource {
        String read(String key);
    }

    /**
     * Builds a policy from runtime KV entries; see the class javadoc for the
     * misconfiguration policy. Absent keys take the matching default.
     */
    public static AssurancePolicy fromRuntime(KvSource kv) {
        Map<String, String> kvMap = new LinkedHashMap<>();
        for (String key : ALL_KEYS) {
            String value = kv.read(key);
            if (value != null) {
                kvMap.put(key, value);
            }
        }
        return fromMap(kvMap);
    }

    /**
     * Builds a policy from a KV map ({@code sas.assurance.*} → raw string).
     * Present-but-invalid values throw {@link IllegalArgumentException}; absent
     * keys take the matching default.
     */
    public static AssurancePolicy fromMap(Map<String, String> kv) {
        Map<String, String> config = kv == null ? Map.of() : kv;
        return new AssurancePolicy(
                weight(config, KEY_W_IP_FRESH, 0.25),
                weight(config, KEY_W_REACHABLE, 0.30),
                weight(config, KEY_W_NOT_SIM_SWAP, 0.30),
                weight(config, KEY_W_LOCATION, 0.15),
                thresholdPercent(config, KEY_THRESHOLD_LOGIN, 70),
                thresholdPercent(config, KEY_THRESHOLD_TRANSFER, 80),
                thresholdPercent(config, KEY_THRESHOLD_HIGH_VALUE, 90));
    }

    private static final String[] ALL_KEYS = {
            KEY_W_IP_FRESH, KEY_W_REACHABLE, KEY_W_NOT_SIM_SWAP, KEY_W_LOCATION,
            KEY_THRESHOLD_LOGIN, KEY_THRESHOLD_TRANSFER, KEY_THRESHOLD_HIGH_VALUE,
    };

    private static double weight(Map<String, String> kv, String key, double def) {
        String raw = kv.get(key);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "invalid " + key + "=\"" + raw + "\": expected a double in [0,1]");
        }
        if (!isUnitWeight(value)) {
            throw new IllegalArgumentException(
                    "invalid " + key + "=" + value + ": expected a weight in [0,1]");
        }
        return value;
    }

    private static double thresholdPercent(Map<String, String> kv, String key, int defPercent) {
        String raw = kv.get(key);
        if (raw == null || raw.isBlank()) {
            return defPercent / 100.0;
        }
        int percent;
        try {
            percent = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "invalid " + key + "=\"" + raw + "\": expected an integer percent in [0,100]");
        }
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException(
                    "invalid " + key + "=" + percent + ": expected an integer percent in [0,100]");
        }
        return percent / 100.0;
    }

    /** Raw weighted score in [0, 100]. */
    public int score(ResolverResult resolver, VerificationEvidence evidence) {
        double s = 0.0;
        if (resolver != null && resolver.found()) {
            s += wIpFresh * ipFreshFactor(resolver.bearerAgeMs());
        }
        if (evidence != null) {
            if (evidence.reachable()) {
                s += wReachable;
            }
            if (evidence.notSimSwapped()) {
                s += wNotSimSwap;
            }
            if (evidence.locationPlausible()) {
                s += wLocation;
            }
        }
        return (int) Math.round(s * 100.0);
    }

    /** 0..1 credit for how recently the PGW binding was made. */
    public static double ipFreshFactor(long bearerAgeMs) {
        if (bearerAgeMs <= FRESH_BEARER_MS) {
            return 1.0;
        }
        if (bearerAgeMs <= STALE_BEARER_MS) {
            return 0.5;
        }
        return 0.0;
    }

    /** Threshold score for the default risk class (LOGIN). */
    public int thresholdScore() {
        return thresholdScore(RiskClass.LOGIN);
    }

    /** Threshold score for a specific risk class. */
    public int thresholdScore(RiskClass riskClass) {
        double t = switch (riskClass) {
            case LOGIN -> thresholdLogin;
            case TRANSFER -> thresholdTransfer;
            case HIGH_VALUE -> thresholdHighValue;
        };
        return (int) Math.round(t * 100.0);
    }

    public AssuranceLevel assuranceFor(int score) {
        return assuranceFor(score, RiskClass.LOGIN);
    }

    public AssuranceLevel assuranceFor(int score, RiskClass riskClass) {
        return score >= thresholdScore(riskClass) ? AssuranceLevel.HIGH : AssuranceLevel.LOW;
    }
}
