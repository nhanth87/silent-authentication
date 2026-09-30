/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.binding.command.AbortBindingCommand;
import et.restlink.sas.ras.binding.command.LookupBindingCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Three-port contract adapter for {@link SubscriberBindingResourceAdaptor} — same
 * wrapper shape as the other RAs, so the container sees one uniform contract.
 */
public final class SubscriberBindingRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(SubscriberBindingRaEndpoint.class);

    private final SubscriberBindingResourceAdaptor delegate;
    private RaBootstrapPort bootstrapPort;

    public SubscriberBindingRaEndpoint(SubscriberBindingResourceAdaptor delegate) {
        this.delegate = delegate;
    }

    /** Wire the source order from {@code sas.binding.source-order}. */
    public java.util.List<String> configureSources(String order) {
        return delegate.setSourceOrder(order);
    }

    public SubscriberBindingResourceAdaptor delegate() {
        return delegate;
    }

    @Override
    public String getRaName() {
        return "subscriber-binding-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrapPort = bootstrap;
        delegate.setBootstrapPort(bootstrap);
        delegate.raConfigure();
        delegate.raActive();
        LOG.info("Subscriber binding RA endpoint activated sources={}", delegate.sourceOrder());
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
        LOG.info("Subscriber binding RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof LookupBindingCommand lc) {
            delegate.lookup(lc);
        } else if (command instanceof AbortBindingCommand ac) {
            delegate.abort(ac);
        } else {
            LOG.warn("Subscriber binding RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }
}
