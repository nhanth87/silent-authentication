/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.coordinator;

import et.restlink.sas.entitlement.eap.EapAkaServer;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-activity EAP state for the TS.43 entitlement service: one
 * {@link EapAkaServer} per {@code reqId}, alive between the {@code CHALLENGE} hop
 * and the {@code RESPOND} hop.
 *
 * <p><strong>Why the state lives here and not in the REST resource.</strong> The
 * Challenge must be verified against the exact vector it was built from, and the
 * REST thread must not hold it: H24 puts all entitlement behaviour inside the
 * micro-jainslee container, with the HTTP layer only submitting events and awaiting
 * the outcome. This store is injected into the SBB, so the keys are wiped by the
 * SBB that owns them.</p>
 *
 * <p><strong>No timer.</strong> Expiry is evaluated lazily on read
 * ({@link #get}) and by {@link #sweep()}, so this class starts no thread and owns
 * no executor — a hand-rolled scheduler outside {@code /ras/} is exactly what H24
 * forbids. A session past its budget is destroyed, not reused: an expired Challenge
 * is an authentication attempt that must be re-started, never one that may still
 * succeed late.</p>
 *
 * <p><strong>Wiping is not optional.</strong> {@link EapAkaServer#wipe()} zeroes
 * CK/IK-derived material. Every removal path — consume, sweep, discard — wipes.</p>
 */
@ApplicationScoped
public class EntitlementSessions {

    /**
     * Whole-activity budget (plan §4.1). A device that cannot answer inside this
     * window re-authenticates from scratch; the vector is never held open for it.
     */
    public static final long SESSION_TTL_MS = 45_000L;

    /** One in-flight TS.43 exchange. */
    public record Session(String reqId, String imsi, String claimedMsisdn,
                          EapAkaServer server, boolean akaPrime, long createdAtMs) {

        boolean expired(long nowMs) {
            return nowMs - createdAtMs > SESSION_TTL_MS;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Store a freshly built session, replacing (and wiping) any predecessor. */
    public void put(Session session) {
        Session previous = sessions.put(session.reqId(), session);
        if (previous != null && previous.server() != session.server()) {
            previous.server().wipe();
        }
    }

    /** The live session for this {@code reqId}, or empty when absent or expired. */
    public Optional<Session> get(String reqId, long nowMs) {
        Session session = sessions.get(reqId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired(nowMs)) {
            discard(reqId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Take the session out of the store and return it, so a second {@code RESPOND}
     * on the same {@code reqId} finds nothing. Replay of a verified answer is
     * therefore impossible: the vector is single-use here too.
     */
    public Optional<Session> take(String reqId, long nowMs) {
        Session session = sessions.remove(reqId);
        if (session == null) {
            return Optional.empty();
        }
        return session.expired(nowMs) ? Optional.empty() : Optional.of(session);
    }

    /** Wipe and forget one session. Safe to call for an unknown id. */
    public void discard(String reqId) {
        Session session = sessions.remove(reqId);
        if (session != null && session.server() != null) {
            session.server().wipe();
        }
    }

    /** Wipe and forget everything (shutdown). */
    public void discardAll() {
        sessions.values().forEach(s -> {
            if (s.server() != null) {
                s.server().wipe();
            }
        });
        sessions.clear();
    }

    /**
     * Drop expired sessions, wiping the key material of each one. The store is small
     * and short-lived, so a plain scan is cheaper than a scheduled task — and it
     * starts no thread (H24).
     */
    public int sweep(long nowMs) {
        int dropped = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().expired(nowMs) && sessions.remove(entry.getKey()) != null) {
                entry.getValue().server().wipe();
                dropped++;
            }
        }
        return dropped;
    }

    public int size() {
        return sessions.size();
    }
}