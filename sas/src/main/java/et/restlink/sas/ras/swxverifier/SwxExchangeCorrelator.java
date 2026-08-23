/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request correlation of SWx answers (RFC 6733 §8.1 Session-Id, §8.2
 * Hop-by-Hop Identifier). Each in-flight exchange registers its own future
 * under the Diameter Session-Id minted by the message factory; the client
 * listener completes exactly that one future. Answers that match no pending
 * exchange are reported unmatched so callers can drop them fail-closed.
 *
 * <p>Hop-by-Hop Id is a secondary index bound after send for stacks that
 * mangle Session-Id on the return path.</p>
 */
final class SwxExchangeCorrelator {

    private static final class Pending {
        private final CompletableFuture<SwxAnswer> future;
        private volatile Long hopByHopId;

        private Pending(CompletableFuture<SwxAnswer> future) {
            this.future = future;
        }
    }

    private final ConcurrentHashMap<String, Pending> bySessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> sessionByHopByHopId = new ConcurrentHashMap<>();

    /** Register a fresh exchange; returns the future its answer must complete. */
    CompletableFuture<SwxAnswer> register(String sessionId) {
        CompletableFuture<SwxAnswer> future = new CompletableFuture<>();
        bySessionId.put(sessionId, new Pending(future));
        return future;
    }

    /** Bind the Hop-by-Hop fallback index once the stack assigns it at send. */
    void bindHopByHop(String sessionId, Long hopByHopId) {
        if (hopByHopId == null) {
            return;
        }
        Pending pending = bySessionId.get(sessionId);
        if (pending != null) {
            pending.hopByHopId = hopByHopId;
            sessionByHopByHopId.put(hopByHopId, sessionId);
        }
    }

    /**
     * Complete the single exchange owned by {@code sessionId}. Returns false
     * when no pending exchange matches (unmatched ⇒ caller drops it).
     */
    boolean complete(String sessionId, SwxAnswer answer) {
        if (sessionId == null) {
            return false;
        }
        Pending pending = bySessionId.remove(sessionId);
        if (pending == null) {
            return false;
        }
        unbind(pending);
        return pending.future.complete(answer);
    }

    /** Hop-by-Hop fallback completion when Session-Id lookup missed. */
    boolean completeByHopByHop(Long hopByHopId, SwxAnswer answer) {
        if (hopByHopId == null) {
            return false;
        }
        String sessionId = sessionByHopByHopId.remove(hopByHopId);
        return sessionId != null && complete(sessionId, answer);
    }

    /** Fail-closed completion of one exchange (stack timeout / shutdown). */
    boolean fail(String sessionId, Throwable cause) {
        if (sessionId == null) {
            return false;
        }
        Pending pending = bySessionId.remove(sessionId);
        if (pending == null) {
            return false;
        }
        unbind(pending);
        return pending.future.completeExceptionally(cause);
    }

    /** Drop an exchange without completing it (budget already enforced elsewhere). */
    void remove(String sessionId) {
        Pending pending = bySessionId.remove(sessionId);
        if (pending != null) {
            unbind(pending);
        }
    }

    /** Fail every pending exchange (backend stop / reconfigure). */
    void failAll(Throwable cause) {
        for (String sessionId : bySessionId.keySet()) {
            fail(sessionId, cause);
        }
    }

    int size() {
        return bySessionId.size();
    }

    void clear() {
        sessionByHopByHopId.clear();
        bySessionId.clear();
    }

    private void unbind(Pending pending) {
        Long hop = pending.hopByHopId;
        if (hop != null) {
            sessionByHopByHopId.remove(hop);
        }
    }
}
