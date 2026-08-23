/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.resolver.command.ResolveCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port contract adapter for {@link ResolverResourceAdaptor} — same wrapper
 * shape as {@code DiameterRaEndpoint} / {@code HttpServerRaEndpoint}.
 */
public final class ResolverRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(ResolverRaEndpoint.class);

    private final ResolverResourceAdaptor delegate;
    private RaBootstrapPort bootstrapPort;

    public ResolverRaEndpoint(ResolverResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public void setBackend(ResolverBackend backend) {
        delegate.setBackend(backend);
    }

    public ResolverBackend backend() {
        return delegate.backend();
    }

    @Override
    public String getRaName() {
        return "resolver-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrapPort = bootstrap;
        delegate.setBootstrapPort(bootstrap);
        delegate.raConfigure();
        delegate.raActive();
        LOG.info("Resolver RA endpoint activated");
    }

    @Override
    public void deactivate() {
        try {
            delegate.raInactive();
        } catch (RuntimeException e) {
            LOG.warn("Error during raInactive", e);
        }
        try {
            delegate.raUnconfigure();
        } catch (RuntimeException e) {
            LOG.warn("Error during raUnconfigure", e);
        }
        this.bootstrapPort = null;
        LOG.info("Resolver RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof ResolveCommand rc) {
            delegate.resolve(rc);
        } else {
            LOG.warn("Resolver RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    /** Expose the underlying RA for wiring/tests. */
    public ResolverResourceAdaptor delegate() {
        return delegate;
    }
}