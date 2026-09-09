/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTP attempt bookkeeping for the CAMARA OneTimePasswordSMS surface:
 * {@code authenticationId} → the <strong>hash</strong> of the issued code, its
 * deadline and the wrong-attempt counter, plus the per-MSISDN send window that
 * backs {@code ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED}.
 *
 * <p>Security properties this store is responsible for:</p>
 * <ul>
 *   <li>the plaintext code is never kept — only {@code sha256(authenticationId|code)},
 *       so a heap dump or a log line cannot replay an OTP;</li>
 *   <li>one {@code authenticationId} validates once: a correct code removes the
 *       attempt (single use);</li>
 *   <li>wrong codes are counted, and at the configured maximum the attempt is
 *       burned — later correct input still fails ({@code VERIFICATION_FAILED});</li>
 *   <li>expiry is evaluated per call and aged out lazily (no timers, no threads:
 *       the container owns scheduling — gate H24).</li>
 * </ul>
 *
 * <p>In-memory and process-local by design (lab). A production deployment needs
 * a persistent store next to the CDR tables; until then the preflight keeps the
 * whole OTP surface off in prod ({@code PRO-29}).</p>
 */
@ApplicationScoped
public class OtpAttemptStore {

    private static final Logger LOG = LogManager.getLogger(OtpAttemptStore.class);

    /** How long an expired/burned attempt stays queryable before lazy eviction. */
    static final long EVICTION_GRACE_SECONDS = 600L;

    /** One OTP verification attempt. */
    public record Attempt(String authenticationId, String msisdn, String codeHash,
                          long expiresEpochSec, int attempts, boolean burned) {

        public boolean expired(long nowEpochSec) {
            return nowEpochSec > expiresEpochSec;
        }
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> sendsByMsisdn = new ConcurrentHashMap<>();

    /**
     * Register a freshly issued OTP.
     *
     * @param authenticationId caller-minted attempt id (UUID, ≤36 chars per the
     *                         spec schema) — it also salts the code hash
     * @param codeHash         {@code sha256(authenticationId|code)} — never the code
     */
    public void create(String authenticationId, String msisdn, String codeHash, long ttlSeconds) {
        long nowSec = System.currentTimeMillis() / 1000L;
        attempts.put(authenticationId, new Attempt(authenticationId, msisdn,
                codeHash, nowSec + Math.max(1L, ttlSeconds), 0, false));
        evictExpired(nowSec);
    }

    /** The attempt behind an {@code authenticationId}, expired ones included. */
    public Optional<Attempt> find(String authenticationId) {
        if (authenticationId == null || authenticationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(attempts.get(authenticationId.trim()));
    }

    /** Visible for testing: register an attempt with an explicit deadline. */
    void createWithDeadline(String authenticationId, String msisdn, String codeHash,
                            long expiresEpochSec) {
        attempts.put(authenticationId, new Attempt(authenticationId, msisdn, codeHash,
                expiresEpochSec, 0, false));
    }

    /**
     * Count one wrong code. At {@code maxAttempts} the attempt is burned so no
     * later guess can succeed.
     *
     * @return the updated attempt, or empty when the id is unknown
     */
    public Optional<Attempt> registerFailure(String authenticationId, int maxAttempts) {
        if (authenticationId == null || authenticationId.isBlank()) {
            return Optional.empty();
        }
        String id = authenticationId.trim();
        Attempt[] updated = new Attempt[1];
        attempts.computeIfPresent(id, (key, current) -> {
            int next = current.attempts() + 1;
            Attempt attempt = new Attempt(current.authenticationId(), current.msisdn(),
                    current.codeHash(), current.expiresEpochSec(), next,
                    current.burned() || next >= Math.max(1, maxAttempts));
            updated[0] = attempt;
            return attempt;
        });
        return Optional.ofNullable(updated[0]);
    }

    /** Consume the attempt (correct code): one OTP validates exactly once. */
    public void remove(String authenticationId) {
        if (authenticationId != null && !authenticationId.isBlank()) {
            attempts.remove(authenticationId.trim());
        }
    }

    /** True when the MSISDN already used its OTP budget inside the window. */
    public boolean sendRateLimited(String msisdn, int maxCodes, long windowSeconds) {
        if (msisdn == null || msisdn.isBlank()) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        long windowMs = Math.max(1L, windowSeconds) * 1000L;
        Deque<Long> sends = sendsByMsisdn.computeIfAbsent(msisdn, k -> new ArrayDeque<>());
        synchronized (sends) {
            while (!sends.isEmpty() && nowMs - sends.peekFirst() > windowMs) {
                sends.pollFirst();
            }
            return sends.size() >= Math.max(1, maxCodes);
        }
    }

    /** Record one accepted send against the per-MSISDN window. */
    public void noteSend(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Deque<Long> sends = sendsByMsisdn.computeIfAbsent(msisdn, k -> new ArrayDeque<>());
        synchronized (sends) {
            sends.addLast(nowMs);
        }
    }

    /** Visible for testing. */
    int attemptCount() {
        return attempts.size();
    }

    private void evictExpired(long nowSec) {
        attempts.values().removeIf(a ->
                nowSec > a.expiresEpochSec() + EVICTION_GRACE_SECONDS);
        if (LOG.isDebugEnabled()) {
            LOG.debug("OTP store: {} live attempts", attempts.size());
        }
    }
}
