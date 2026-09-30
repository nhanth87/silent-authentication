/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector;

import com.microjainslee.api.RaBootstrapPort;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.ras.authvector.command.AbortAuthVectorCommand;
import et.restlink.sas.ras.authvector.command.FetchVectorCommand;
import et.restlink.sas.ras.authvector.command.ResyncVectorCommand;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Auth-vector RA adaptor — the <strong>only</strong> route to an authentication
 * vector in the whole SAS.
 *
 * <p>Gate H24 puts the signalling client behind this seam: the SBB may not call
 * {@link AuthVectorBackend} directly, and a REST or service class may not reach it at
 * all. The RA owns the dialog, the 2 s budget
 * ({@link SasTimeouts#DIAMETER_MS}) and the resync cap; the SBB only ever sees a
 * completed {@link AuthVector} or a failed future.</p>
 *
 * <p>Fail-closed and leak-free: an expired, aborted or failed exchange completes the
 * command's future exceptionally and drops the dialog, so a hung HSS cannot leave a
 * session behind (the same invariant the SWx verifier RA holds).</p>
 */
public final class AuthVectorResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(AuthVectorResourceAdaptor.class);

    private RaBootstrapPort bootstrapPort;
    private AuthVectorBackend backend = new InMemoryAuthVectorBackend();

    /** reqId → in-flight exchange bookkeeping. */
    private final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();

    /**
     * reqId → resync attempts already spent by that <em>session</em>.
     *
     * <p>Deliberately separate from {@link #exchanges}: an EAP session spans several
     * vector exchanges (challenge, resync, new challenge), so a counter that reset when
     * an exchange completed would let a client resync forever — and every resync moves
     * the AuC sequence number. The budget therefore lives as long as the session and is
     * released only by {@link AbortAuthVectorCommand} or {@link #raInactive()}.</p>
     */
    private final Map<String, AtomicInteger> resyncBudget = new ConcurrentHashMap<>();

    private final AtomicBoolean active = new AtomicBoolean(false);

    /** Bounded so a caller cannot turn a stuck RA into a memory leak. */
    private static final int MAX_IN_FLIGHT = 256;

    private record Exchange(AtomicBoolean ended) {
        boolean end() {
            return ended.compareAndSet(false, true);
        }

        boolean isEnded() {
            return ended.get();
        }
    }

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    public void setBackend(AuthVectorBackend backend) {
        this.backend = backend;
    }

    public AuthVectorBackend backend() {
        return backend;
    }

    public void raConfigure() {
        // no-op: the transport is owned by the shared SWx client
    }

    public void raActive() {
        active.set(true);
    }

    public void raInactive() {
        active.set(false);
        abortAll();
    }

    public void raUnconfigure() {
        exchanges.clear();
        resyncBudget.clear();
    }

    public boolean isActive() {
        return active.get();
    }

    /** In-flight exchanges — asserted by the tests and surfaced in the admin view. */
    public int openExchanges() {
        return exchanges.size();
    }

    /** Fetch one vector. */
    public void fetch(FetchVectorCommand cmd) {
        if (!isActive()) {
            refuse(cmd.reply(), "auth-vector RA is not active");
            return;
        }
        Exchange exchange = register(cmd.reqId());
        if (exchange == null) {
            refuse(cmd.reply(), "too many in-flight vector exchanges");
            return;
        }
        LOG.debug("Auth vector fetch reqId={} scheme={}", cmd.reqId(), cmd.scheme());
        backend.fetch(cmd.imsi(), cmd.scheme())
                .completeOnTimeout(null, SasTimeouts.DIAMETER_MS, TimeUnit.MILLISECONDS)
                .whenComplete((vector, error) -> finish(cmd.reqId(), exchange, cmd.reply(), vector, error));
    }

    /**
     * Resynchronise after {@code AT_AUTS}. Refused once the cap is spent — see
     * {@link ResyncVectorCommand#MAX_RESYNC}.
     */
    public void resync(ResyncVectorCommand cmd) {
        if (!isActive()) {
            refuse(cmd.reply(), "auth-vector RA is not active");
            return;
        }
        Exchange current = exchanges.get(cmd.reqId());
        if (current == null) {
            current = register(cmd.reqId());
            if (current == null) {
                refuse(cmd.reply(), "too many in-flight vector exchanges");
                return;
            }
        }
        final Exchange exchange = current;
        if (exchange.isEnded()) {
            refuse(cmd.reply(), "exchange already finished");
            return;
        }
        // One budget per SESSION, not per exchange — see the resyncBudget comment.
        AtomicInteger spent = resyncBudget.computeIfAbsent(cmd.reqId(), k -> new AtomicInteger());
        if (spent.getAndIncrement() >= ResyncVectorCommand.MAX_RESYNC) {
            refuse(cmd.reply(), "resynchronisation refused: the "
                    + ResyncVectorCommand.MAX_RESYNC + "-retry cap stops AuC sequence desync");
            return;
        }
        LOG.debug("Auth vector resync reqId={} attempt={}", cmd.reqId(), spent.get());
        backend.resync(cmd.imsi(), cmd.scheme(), cmd.rand(), cmd.auts())
                .completeOnTimeout(null, SasTimeouts.DIAMETER_MS, TimeUnit.MILLISECONDS)
                .whenComplete((vector, error) -> finish(cmd.reqId(), exchange, cmd.reply(), vector, error));
    }

    /** Explicit abort from the SBB when its own budget expires first. */
    public void abort(AbortAuthVectorCommand cmd) {
        Exchange exchange = exchanges.remove(cmd.reqId());
        resyncBudget.remove(cmd.reqId());
        if (exchange != null) {
            exchange.end();
            LOG.warn("Auth vector exchange aborted reqId={} session={}",
                    cmd.reqId(), cmd.sessionId());
        } else {
            LOG.warn("Auth vector abort for unknown reqId={} — already closed", cmd.reqId());
        }
    }

    /** Resync attempts already spent by a session — asserted by the tests. */
    public int resyncAttempts(String reqId) {
        AtomicInteger spent = resyncBudget.get(reqId);
        return spent == null ? 0 : spent.get();
    }

    private Exchange register(String reqId) {
        if (exchanges.size() >= MAX_IN_FLIGHT) {
            return null;
        }
        Exchange fresh = new Exchange(new AtomicBoolean(false));
        Exchange existing = exchanges.putIfAbsent(reqId, fresh);
        return existing != null ? existing : fresh;
    }

    private void finish(String reqId, Exchange exchange, CompletableFuture<AuthVector> reply,
                        AuthVector vector, Throwable error) {
        if (!exchange.end()) {
            // A late answer after an abort: drop it and wipe the secrets.
            if (vector != null) {
                vector.wipe();
            }
            LOG.warn("Late auth vector for finished exchange reqId={} — dropped", reqId);
            return;
        }
        exchanges.remove(reqId, exchange);
        if (error != null || vector == null) {
            refuse(reply, error == null ? "vector exchange produced nothing" : error.getMessage());
            return;
        }
        if (!reply.complete(vector)) {
            // Nobody is listening any more (SBB timed out) — do not leak the secrets.
            vector.wipe();
            LOG.warn("Auth vector for reqId={} had no listener — wiped", reqId);
        }
    }

    private static void refuse(CompletableFuture<AuthVector> reply, String reason) {
        reply.completeExceptionally(new IllegalStateException(
                "auth vector refused: " + (reason == null ? "unspecified" : reason)));
    }

    private void abortAll() {
        exchanges.values().forEach(Exchange::end);
        exchanges.clear();
        resyncBudget.clear();
    }

    /** Never let a bootstrap port leak into a log line. */
    @Override
    public String toString() {
        return "AuthVectorResourceAdaptor[active=" + active.get()
                + ", backend=" + (backend == null ? "none" : backend.name())
                + ", inFlight=" + exchanges.size()
                + ", sessions=" + resyncBudget.size() + "]";
    }
}
