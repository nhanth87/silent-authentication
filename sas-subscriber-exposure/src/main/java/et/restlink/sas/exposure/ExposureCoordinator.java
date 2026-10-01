/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.exposure;

import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureOutcome;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedup for the exposure API (contract: {@code X-Request-Id} + operation + body for 60 s).
 * In-flight duplicates share one future; settled answers are replayed without signalling.
 *
 * <p>Only definitive answers are recorded — a 200 and the 4xx outcomes. A 5xx/504 is a
 * transient failure: replaying it for a minute would turn one lost dialog into a minute of
 * refusals, so a retry is allowed to signal again.</p>
 */
@ApplicationScoped
public class ExposureCoordinator {

    private record Settled(ExposureOutcome<?> outcome, long atMs) {
    }

    private final Map<String, CompletableFuture<ExposureOutcome<?>>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Settled> settled = new ConcurrentHashMap<>();

    /** Dedup key: operation, request id and a digest of the canonical request. */
    public static String answerKey(String operation, String requestId, String canonicalRequest) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return operation + '|' + requestId + '|' + HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A recorded answer for this key, or null. */
    public ExposureOutcome<?> cached(String key, long nowMs) {
        settled.values().removeIf(s -> nowMs - s.atMs() > ExposureApi.DEDUP_WINDOW_MS);
        Settled s = settled.get(key);
        return s == null ? null : s.outcome();
    }

    /**
     * Join an in-flight execution of this key, or start one.
     *
     * @return the shared future, and whether the caller must start the execution
     */
    public Registration register(String key) {
        CompletableFuture<ExposureOutcome<?>> fresh = new CompletableFuture<>();
        CompletableFuture<ExposureOutcome<?>> existing = inFlight.putIfAbsent(key, fresh);
        return existing != null ? new Registration(existing, false) : new Registration(fresh, true);
    }

    public record Registration(CompletableFuture<ExposureOutcome<?>> future, boolean owner) {
    }

    /** Settle the execution of this key. */
    public void complete(String key, ExposureOutcome<?> outcome) {
        if (definitive(outcome)) {
            settled.put(key, new Settled(outcome, System.currentTimeMillis()));
        }
        CompletableFuture<ExposureOutcome<?>> f = inFlight.remove(key);
        if (f != null) {
            f.complete(outcome);
        }
    }

    /** Drop an execution nobody will answer any more (caller gave up). */
    public void abandon(String key) {
        inFlight.remove(key);
    }

    static boolean definitive(ExposureOutcome<?> outcome) {
        return outcome.ok() || (outcome.problem().status() != null && outcome.problem().status() < 500);
    }

    int inFlightCount() {
        return inFlight.size();
    }
}
