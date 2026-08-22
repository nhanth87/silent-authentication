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
 */
public final class AssurancePolicy {

    /** Bearer binding considered "fresh" below this age. */
    public static final long FRESH_BEARER_MS = 60_000L;

    /** Binding considered "stale" (half weight) below this age. */
    public static final long STALE_BEARER_MS = 300_000L;

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
        double sum = wIpFresh + wReachable + wNotSimSwap + wLocation;
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("weights must sum to 1.0 (was " + sum + ")");
        }
        this.wIpFresh = wIpFresh;
        this.wReachable = wReachable;
        this.wNotSimSwap = wNotSimSwap;
        this.wLocation = wLocation;
        this.thresholdLogin = thresholdLogin;
        this.thresholdTransfer = thresholdTransfer;
        this.thresholdHighValue = thresholdHighValue;
    }

    /** Design defaults (silent-auth-flow.md §6 sketch). */
    public static AssurancePolicy defaults() {
        return new AssurancePolicy(0.25, 0.30, 0.30, 0.15, 0.70, 0.80, 0.90);
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
