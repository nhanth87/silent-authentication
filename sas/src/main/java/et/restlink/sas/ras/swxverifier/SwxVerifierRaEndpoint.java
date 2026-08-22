/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.swxverifier.command.AbortSwxCommand;
import et.restlink.sas.ras.swxverifier.command.SwxVerifyCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port contract adapter for {@link SwxVerifierResourceAdaptor} — same
 * wrapper shape as {@code S6aVerifierRaEndpoint} / {@code MapVerifierRaEndpoint}.
 */
public final class SwxVerifierRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(SwxVerifierRaEndpoint.class);

    private final SwxVerifierResourceAdaptor delegate;
    private RaBootstrapPort bootstrapPort;

    public SwxVerifierRaEndpoint(SwxVerifierResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public void setBackend(SwxVerifierBackend backend) {
        delegate.setBackend(backend);
    }

    public SwxVerifierBackend backend() {
        return delegate.backend();
    }

    @Override
    public String getRaName() {
        return "swx-verifier-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrapPort = bootstrap;
        delegate.setBootstrapPort(bootstrap);
        delegate.raConfigure();
        delegate.raActive();
        LOG.info("SWx verifier RA endpoint activated");
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
        LOG.info("SWx verifier RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof SwxVerifyCommand vc) {
            delegate.verify(vc);
        } else if (command instanceof AbortSwxCommand ac) {
            delegate.abort(ac.reqId(), ac.sessionId());
        } else {
            LOG.warn("SWx verifier RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    public SwxVerifierResourceAdaptor delegate() {
        return delegate;
    }
}
