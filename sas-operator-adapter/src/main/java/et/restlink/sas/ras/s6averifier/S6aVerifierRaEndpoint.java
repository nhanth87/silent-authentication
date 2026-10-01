/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.s6averifier.command.AbortS6aCommand;
import et.restlink.sas.ras.s6averifier.command.S6aVerifyCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port contract adapter for {@link S6aVerifierResourceAdaptor} — same wrapper
 * shape as {@code DiameterRaEndpoint} / {@code MapVerifierRaEndpoint}.
 */
public final class S6aVerifierRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(S6aVerifierRaEndpoint.class);

    private final S6aVerifierResourceAdaptor delegate;
    private RaBootstrapPort bootstrapPort;

    public S6aVerifierRaEndpoint(S6aVerifierResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public void setBackend(S6aVerifierBackend backend) {
        delegate.setBackend(backend);
    }

    public S6aVerifierBackend backend() {
        return delegate.backend();
    }

    @Override
    public String getRaName() {
        return "s6a-verifier-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrapPort = bootstrap;
        delegate.setBootstrapPort(bootstrap);
        delegate.raConfigure();
        delegate.raActive();
        LOG.info("S6a verifier RA endpoint activated");
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
        LOG.info("S6a verifier RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof S6aVerifyCommand vc) {
            delegate.verify(vc);
        } else if (command instanceof AbortS6aCommand ac) {
            delegate.abort(ac.reqId(), ac.dialogId());
        } else {
            LOG.warn("S6a verifier RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    public S6aVerifierResourceAdaptor delegate() {
        return delegate;
    }
}