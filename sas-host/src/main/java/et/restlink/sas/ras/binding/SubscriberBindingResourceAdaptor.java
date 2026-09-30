/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding;

import com.microjainslee.api.RaBootstrapPort;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.ras.binding.backend.SubscriberDbBinding;
import et.restlink.sas.ras.binding.command.AbortBindingCommand;
import et.restlink.sas.ras.binding.command.LookupBindingCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Subscriber-binding RA — the only route from an EAP-proved IMSI to a number
 * (plan Phase 1c).
 *
 * <p>Sources are consulted in the configured {@code sas.binding.source-order}, under a
 * <strong>single shared 2 s budget</strong> ({@link SasTimeouts#DIAMETER_MS}) — the same
 * budget the other RA stages use, so a slow HSS cannot stretch the entitlement
 * transaction. The first source that <em>resolves</em> a number wins; a source that
 * errors is skipped and the next one is tried; if every source is exhausted the answer
 * is an <strong>unresolved</strong> binding, which the caller must treat as a
 * refusal.</p>
 *
 * <p>Ordering is policy, not preference: the live operator sources come before the
 * read-only export, and a claimed MSISDN is only ever <em>compared</em>, never used to
 * short-circuit the lookup — otherwise the device would pick the answer.</p>
 */
public final class SubscriberBindingResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SubscriberBindingResourceAdaptor.class);

    /** Legal {@code sas.binding.source-order} tokens. */
    public static final List<String> LEGAL_SOURCES = List.of("swx-sar", "sh-udr", "map-smi", "db");

    private RaBootstrapPort bootstrapPort;

    /**
     * Source token → backend, in consultation order.
     *
     * <p>A {@link java.util.LinkedHashMap} rather than a bare list because the order is
     * <em>configuration</em>: setting it must re-order the sources that are already
     * wired, never discard them. The earlier list-based version cleared the list on every
     * {@code setSourceOrder} and replaced each entry with a placeholder, which silently
     * threw away a live transport the bootstrap had just registered.</p>
     */
    private final java.util.LinkedHashMap<String, SubscriberBindingBackend> sources =
            new java.util.LinkedHashMap<>();

    private final Map<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);

    private static final int MAX_IN_FLIGHT = 256;

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    /**
     * Set the source order. Unknown tokens are dropped and logged rather than accepted,
     * so a typo cannot silently promote a stale export ahead of a live source.
     *
     * @param order comma-separated tokens from {@link #LEGAL_SOURCES}
     * @return the tokens that were actually accepted
     */
    public List<String> setSourceOrder(String order) {
        String csv = (order == null || order.isBlank()) ? "db" : order;
        List<String> accepted = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String token = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (!LEGAL_SOURCES.contains(token)) {
                LOG.warn("Ignoring unknown binding source {!r}; legal: {}", token, LEGAL_SOURCES);
                continue;
            }
            if (!sources.containsKey(token)) {
                // Configured but not wired in this deployment: it must answer
                // "unresolved", never guess, and never short-circuit the real sources.
                sources.put(token, "db".equals(token)
                        ? new SubscriberDbBinding()
                        : unavailable(token));
            }
            accepted.add(token);
        }
        if (accepted.isEmpty()) {
            sources.putIfAbsent("db", new SubscriberDbBinding());
            return List.of("db");
        }
        // Re-order in place, keeping whatever backend each token already has.
        java.util.LinkedHashMap<String, SubscriberBindingBackend> reordered =
                new java.util.LinkedHashMap<>();
        for (String token : accepted) {
            reordered.put(token, sources.get(token));
        }
        sources.clear();
        sources.putAll(reordered);
        return List.copyOf(accepted);
    }

    /** Register a live backend under a source token (used by the bootstrap wiring). */
    public void addBackend(String source, SubscriberBindingBackend backend) {
        sources.put(source, backend);
    }

    /** The tokens currently consulted, in order. */
    public List<String> sourceOrder() {
        return List.copyOf(sources.keySet());
    }

    public void raConfigure() {
        // transports are owned by their own clients
    }

    public void raActive() {
        active.set(true);
    }

    public void raInactive() {
        active.set(false);
        inFlight.clear();
    }

    public void raUnconfigure() {
        inFlight.clear();
    }

    public boolean isActive() {
        return active.get();
    }

    public int openLookups() {
        return inFlight.size();
    }

    /**
     * Resolve one IMSI, walking the configured sources inside one shared budget.
     *
     * @param claimedMsisdn the number the bank asserted, if any. It is compared against
     *                      the resolved number and never used as the answer: a device
     *                      that names its own number must not be believed.
     */
    public void lookup(LookupBindingCommand cmd) {
        CompletableFuture<SubscriberBinding> reply = cmd.reply();
        if (!isActive()) {
            reply.complete(SubscriberBinding.unresolved(cmd.imsi(), "ra-inactive"));
            return;
        }
        if (inFlight.size() >= MAX_IN_FLIGHT) {
            reply.complete(SubscriberBinding.unresolved(cmd.imsi(), "ra-overloaded"));
            return;
        }
        if (inFlight.putIfAbsent(cmd.reqId(), new AtomicBoolean(false)) != null) {
            reply.complete(SubscriberBinding.unresolved(cmd.imsi(), "duplicate-req"));
            return;
        }
        final AtomicBoolean session = inFlight.get(cmd.reqId());
        try {
            walk(cmd.reqId(), cmd.imsi(), cmd.claimedMsisdn(), reply, session,
                    System.currentTimeMillis() + SasTimeouts.DIAMETER_MS, 0);
        } catch (RuntimeException e) {
            LOG.warn("Binding lookup failed unexpectedly", e);
            finish(cmd.reqId(), session, reply, SubscriberBinding.unresolved(cmd.imsi(), "error"));
        }
    }

    /**
     * Consult the next source. Recursion is bounded by the number of sources, and every
     * hop re-checks the deadline, so a slow source cannot be followed by another slow
     * source inside the same budget.
     */
    private void walk(String reqId, String imsi, String claimedMsisdn,
                      CompletableFuture<SubscriberBinding> reply, AtomicBoolean session,
                      long deadline, int index) {
        if (index >= sources.size()) {
            finish(reqId, session, reply, SubscriberBinding.unresolved(imsi, "exhausted"));
            return;
        }
        if (System.currentTimeMillis() >= deadline) {
            LOG.warn("Binding budget exhausted with {} source(s) left", sources.size() - index);
            finish(reqId, session, reply, SubscriberBinding.unresolved(imsi, "timeout"));
            return;
        }
        SubscriberBindingBackend backend = new java.util.ArrayList<>(sources.values()).get(index);
        long remainingMs = Math.max(1, deadline - System.currentTimeMillis());
        backend.lookup(imsi)
                .completeOnTimeout(SubscriberBinding.unresolved(imsi, backend.name()),
                        remainingMs, TimeUnit.MILLISECONDS)
                .thenAccept(binding -> {
                    if (binding != null && binding.resolved()) {
                        finish(reqId, session, reply, reconcile(imsi, claimedMsisdn, binding));
                        return;
                    }
                    // Unresolved or absent: try the next source, budget permitting.
                    walk(reqId, imsi, claimedMsisdn, reply, session, deadline, index + 1);
                })
                .exceptionally(error -> {
                    LOG.warn("Binding source {} failed; trying the next one", backend.name(), error);
                    walk(reqId, imsi, claimedMsisdn, reply, session, deadline, index + 1);
                    return null;
                });
    }

    /**
     * When the bank also claimed a number, a disagreement is a
     * {@code SIM_SWAP_SUSPECT}-shaped failure, not a quiet success.
     */
    private static SubscriberBinding reconcile(String imsi, String claimed, SubscriberBinding found) {
        if (claimed == null || claimed.isBlank()) {
            return found;
        }
        if (claimed.trim().equals(found.msisdn().trim())) {
            return found;
        }
        LOG.warn("Binding mismatch: the claimed MSISDN is not the one bound to the "
                + "EAP-proved IMSI (source {}) — refusing", found.source());
        return SubscriberBinding.unresolved(imsi, "mismatch");
    }

    /** Terminal step: exactly one completion per request, and the slot is released. */
    private void finish(String reqId, AtomicBoolean session,
                        CompletableFuture<SubscriberBinding> reply, SubscriberBinding binding) {
        if (!session.compareAndSet(false, true)) {
            LOG.warn("Late binding answer for a finished lookup reqId={} — dropped", reqId);
            return;
        }
        inFlight.remove(reqId, session);
        if (!reply.complete(binding)) {
            LOG.warn("Binding answer for reqId={} had no listener — dropped", reqId);
        }
    }

    /** Explicit abort from the SBB when its own budget expires first. */
    public void abort(AbortBindingCommand cmd) {
        AtomicBoolean session = inFlight.remove(cmd.reqId());
        if (session != null) {
            session.set(true);
            LOG.warn("Binding lookup aborted reqId={} session={}", cmd.reqId(), cmd.sessionId());
        } else {
            LOG.warn("Binding abort for unknown reqId={} — already closed", cmd.reqId());
        }
    }

    /** A source that is configured but has no transport wired in this deployment. */
    private static SubscriberBindingBackend unavailable(String source) {
        String token = source;
        return new SubscriberBindingBackend() {
            @Override
            public CompletableFuture<SubscriberBinding> lookup(String imsi) {
                return CompletableFuture.completedFuture(
                        SubscriberBinding.unresolved(imsi, token + "-unavailable"));
            }

            @Override
            public void stop() {
            }

            @Override
            public String name() {
                return token + "-unavailable";
            }
        };
    }

    @Override
    public String toString() {
        return "SubscriberBindingResourceAdaptor[active=" + active.get()
                + ", sources=" + sourceOrder() + ", inFlight=" + inFlight.size() + "]";
    }
}
