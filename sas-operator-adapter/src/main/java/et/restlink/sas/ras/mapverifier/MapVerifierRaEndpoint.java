/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.mapverifier.command.AbortMapCommand;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port contract adapter for {@link MapVerifierResourceAdaptor}.
 */
public final class MapVerifierRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(MapVerifierRaEndpoint.class);

    private final MapVerifierResourceAdaptor delegate;
    private RaBootstrapPort bootstrapPort;

    public MapVerifierRaEndpoint(MapVerifierResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    public void setBackend(MapVerifierBackend backend) {
        delegate.setBackend(backend);
    }

    public MapVerifierBackend backend() {
        return delegate.backend();
    }

    @Override
    public String getRaName() {
        return "map-verifier-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrapPort = bootstrap;
        delegate.setBootstrapPort(bootstrap);
        delegate.raConfigure();
        delegate.raActive();
        LOG.info("MAP verifier RA endpoint activated");
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
        LOG.info("MAP verifier RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof MapVerifyCommand mc) {
            delegate.verify(mc);
        } else if (command instanceof AbortMapCommand ac) {
            delegate.abort(ac.reqId(), ac.dialogId());
        } else {
            LOG.warn("MAP verifier RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    public MapVerifierResourceAdaptor delegate() {
        return delegate;
    }
}