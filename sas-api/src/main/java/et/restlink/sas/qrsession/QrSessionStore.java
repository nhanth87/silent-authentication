/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.qrsession;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-request state for the desktop → phone QR hand-off: one
 * {@link QrSession} per bank login attempt, alive between
 * {@code POST /qr-session} (the bank renders the code) and the phone's
 * {@code POST /qr-session/{id}/claim}.
 *
 * <p><strong>Why this is not identity state.</strong> The layered split
 * (ADR D3/D7) keeps the gateway free of subscriber facts: a session holds only
 * what the <em>bank</em> supplied (the number it wants proven) plus the verdict
 * the FSM returned. Nothing here is read from the core, and nothing survives the
 * request — the gateway still learns every subscriber fact through
 * {@code SubscriberService}.</p>
 *
 * <p><strong>One claim, one verdict.</strong> {@link #beginClaim} is a
 * compare-and-set: the first phone to claim wins, every later one is refused.
 * Without it two phones racing the same QR code would both get a verdict and the
 * bank could not tell which handset the evidence belonged to.</p>
 *
 * <p><strong>No timer.</strong> Expiry is evaluated lazily on read and by
 * {@link #sweep()} — this class starts no thread and owns no executor, which
 * gate H24 forbids outside {@code /ras/}. An expired session is
 * {@link State#EXPIRED}, never {@link State#APPROVED}: a login the user walked
 * away from must not stay approvable.</p>
 *
 * <p><strong>Fail-closed.</strong> A session with no verdict is
 * {@link State#PENDING}, which the bank must read as "not yet authenticated".
 * There is no default-approve path.</p>
 */
@ApplicationScoped
public class QrSessionStore {

    /** Whole-handshake budget. A bank login older than this is not approvable. */
    public static final long SESSION_TTL_MS = 120_000L;

    /** How long a finished session stays queryable before lazy eviction. */
    static final long EVICTION_GRACE_MS = 120_000L;

    /** Verdict lifecycle of one hand-off. */
    public enum State {
        /** Created; no phone has claimed it yet. */
        PENDING,
        /** A phone claimed it and the FSM returned a verdict. */
        RESOLVED,
        /** Past its budget — not approvable, ever. */
        EXPIRED
    }

    /**
     * One desktop → phone hand-off.
     *
     * @param sessionId    opaque id the bank renders as the QR payload
     * @param claimedMsisdn the number the bank wants proven (E.164), or null when
     *                      the bank deferred discovery to the phone
     * @param correlator   bank {@code x-correlator}, echoed on every response
     * @param tenantId     tenant that created it, for the quota gate
     * @param createdAtMs  epoch millis at creation
     */
    public record QrSession(String sessionId,
                            String claimedMsisdn,
                            String correlator,
                            String tenantId,
                            long createdAtMs) {

        boolean pastBudget(long nowMs) {
            return nowMs - createdAtMs > SESSION_TTL_MS;
        }
    }

    /** The FSM verdict, attached once a claim completed. */
    public record Verdict(boolean verified, String fallbackReason, String decision) {
    }

    private final Map<String, QrSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Verdict> verdicts = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> claimGates = new ConcurrentHashMap<>();
    private final Map<String, Long> finishedAt = new ConcurrentHashMap<>();

    /**
     * Register a new session; any predecessor under the same id is discarded.
     *
     * <p>The claim gate is replaced, not merged: a session created afresh is
     * unclaimed by definition. Leaving the old gate in place would make a reused
     * id permanently unclaimable, and a bank retrying its login would see
     * {@code ALREADY_CLAIMED} for a code nobody ever scanned.</p>
     */
    public void create(QrSession session) {
        sessions.put(session.sessionId(), session);
        verdicts.remove(session.sessionId());
        finishedAt.remove(session.sessionId());
        claimGates.put(session.sessionId(), new AtomicBoolean());
    }

    /** The live session, or empty when absent or past its budget. */
    public Optional<QrSession> get(String sessionId, long nowMs) {
        QrSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.pastBudget(nowMs)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Compare-and-set: {@code true} for the single caller that wins the claim.
     *
     * <p>The gate is per session id and never resets, so a replayed
     * {@code /claim} after a completed hand-off is refused rather than answered
     * a second time.</p>
     */
    public boolean beginClaim(String sessionId) {
        AtomicBoolean gate = claimGates.get(sessionId);
        return gate != null && gate.compareAndSet(false, true);
    }

    /** True when some phone already claimed this session. */
    public boolean claimed(String sessionId) {
        AtomicBoolean gate = claimGates.get(sessionId);
        return gate != null && gate.get();
    }

    /** Attach the FSM verdict and mark the session finished. */
    public void resolve(String sessionId, Verdict verdict, long nowMs) {
        verdicts.put(sessionId, verdict);
        finishedAt.put(sessionId, nowMs);
    }

    /** The verdict attached to this session, if any. */
    public Optional<Verdict> verdict(String sessionId) {
        return Optional.ofNullable(verdicts.get(sessionId));
    }

    /** Current lifecycle state — {@link State#EXPIRED} outranks a stored verdict. */
    public State state(QrSession session, long nowMs) {
        if (session.pastBudget(nowMs)) {
            return State.EXPIRED;
        }
        return verdicts.containsKey(session.sessionId()) ? State.RESOLVED : State.PENDING;
    }

    /**
     * Drop sessions that finished long enough ago to be of no further interest.
     * No scheduling: callers piggy-back this on real traffic.
     */
    public int sweep(long nowMs) {
        int dropped = 0;
        for (Map.Entry<String, QrSession> entry : sessions.entrySet()) {
            String id = entry.getKey();
            Long finished = finishedAt.get(id);
            boolean stale = finished != null && nowMs - finished > EVICTION_GRACE_MS;
            if ((entry.getValue().pastBudget(nowMs) || stale) && sessions.remove(id) != null) {
                verdicts.remove(id);
                finishedAt.remove(id);
                claimGates.remove(id);
                dropped++;
            }
        }
        return dropped;
    }

    public int size() {
        return sessions.size();
    }
}
