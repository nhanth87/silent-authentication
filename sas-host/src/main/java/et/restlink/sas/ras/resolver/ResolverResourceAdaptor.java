/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.ras.resolver.command.ResolveCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolver delegate (transport + lifecycle) — mirror of
 * {@code vendor-ras/ra-diameter} {@code DiameterResourceAdaptor}, but
 * client-side only: the SAS never answers resolver requests, it only asks.
 *
 * <p>Lifecycle: {@code raConfigure → raActive → (resolve…) → raInactive →
 * raUnconfigure}.</p>
 */
public final class ResolverResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(ResolverResourceAdaptor.class);

    private RaBootstrapPort bootstrapPort;
    private ResolverBackend backend = new InMemoryResolverBackend();
    private final Map<String, ActivityHandle> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    public void setBackend(ResolverBackend backend) {
        this.backend = backend;
    }

    public ResolverBackend backend() {
        return backend;
    }

    public void raConfigure() {
        // no-op for the pilot in-memory backend
    }

    public void raActive() {
        active.set(true);
    }

    public void raInactive() {
        active.set(false);
    }

    public void raUnconfigure() {
        sessions.clear();
    }

    public boolean isActive() {
        return active.get();
    }

    /** One resolver lookup per request id — never parallel for the same id. */
    public void resolve(ResolveCommand cmd) {
        if (!isActive()) {
            cmd.reply().complete(ResolverResult.miss(FallbackReason.RESOLVER_ERROR));
            return;
        }
        // Track as a lightweight "session" so an operator can inspect in-flight lookups.
        ActivityHandle handle = bootstrapPort != null
                ? bootstrapPort.createActivityHandle(cmd.reqId()) : null;
        if (handle != null) {
            sessions.put(cmd.reqId(), handle);
        }

        backend.resolve(cmd.srcIp(), cmd.srcPort(), cmd.tsEpochMs())
                .completeOnTimeout(ResolverResult.miss(FallbackReason.RESOLVER_TIMEOUT),
                        SasTimeouts.RESOLVER_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> ResolverResult.miss(FallbackReason.RESOLVER_ERROR))
                .whenComplete((r, ex) -> {
                    sessions.remove(cmd.reqId());
                    cmd.reply().complete(r != null ? r : ResolverResult.miss(FallbackReason.RESOLVER_ERROR));
                });
        LOG.debug("Resolver request reqId={} ip={}:{}", cmd.reqId(), cmd.srcIp(), cmd.srcPort());
    }
}