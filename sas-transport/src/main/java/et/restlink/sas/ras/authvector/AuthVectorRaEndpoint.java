/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.authvector;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.authvector.command.AbortAuthVectorCommand;
import et.restlink.sas.ras.authvector.command.FetchVectorCommand;
import et.restlink.sas.ras.authvector.command.ResyncVectorCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Three-port contract adapter for {@link AuthVectorResourceAdaptor} — the same
 * wrapper shape as {@code SwxVerifierRaEndpoint} / {@code S6aVerifierRaEndpoint}, so
 * the container sees one uniform RA contract.
 */
public final class AuthVectorRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(AuthVectorRaEndpoint.class);

    private final AuthVectorResourceAdaptor delegate;
    private RaBootstrapPort bootstrapPort;

    public AuthVectorRaEndpoint(AuthVectorResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public void setBackend(AuthVectorBackend backend) {
        delegate.setBackend(backend);
    }

    public AuthVectorBackend backend() {
        return delegate.backend();
    }

    @Override
    public String getRaName() {
        return "auth-vector-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrapPort = bootstrap;
        delegate.setBootstrapPort(bootstrap);
        delegate.raConfigure();
        delegate.raActive();
        LOG.info("Auth vector RA endpoint activated");
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
        LOG.info("Auth vector RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof FetchVectorCommand fc) {
            delegate.fetch(fc);
        } else if (command instanceof ResyncVectorCommand rc) {
            delegate.resync(rc);
        } else if (command instanceof AbortAuthVectorCommand ac) {
            delegate.abort(ac);
        } else {
            LOG.warn("Auth vector RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    public AuthVectorResourceAdaptor delegate() {
        return delegate;
    }
}
