/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.exposure;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;

import et.restlink.sas.ras.exposure.command.SubscriberCallCommand;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code exposure-ra}: the gateway's only resource adaptor to the subscriber (layered ADR
 * §2) — same 3-port contract as the former resolver/verifier RAs, over the exposure client.
 */
public final class ExposureRaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(ExposureRaEndpoint.class);

    private final SubscriberService client;
    private final AtomicBoolean active = new AtomicBoolean(false);

    public ExposureRaEndpoint(SubscriberService client) {
        this.client = client;
    }

    @Override
    public String getRaName() {
        return "exposure-ra";
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        active.set(true);
        LOG.info("Exposure RA endpoint activated");
    }

    @Override
    public void deactivate() {
        active.set(false);
        LOG.info("Exposure RA endpoint deactivated");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof SubscriberCallCommand<?> call) {
            dispatch(call);
        } else {
            LOG.warn("Exposure RA received unknown command: {}",
                    command == null ? "null" : command.getClass().getName());
        }
    }

    private <T> void dispatch(SubscriberCallCommand<T> cmd) {
        if (!active.get() || client == null) {
            cmd.reply().complete(ExposureOutcome.problem(ExposureCause.TRANSPORT_UNAVAILABLE,
                    "exposure RA inactive"));
            return;
        }
        CompletableFuture<ExposureOutcome<T>> answer;
        try {
            answer = cmd.call().apply(client);
        } catch (RuntimeException e) {
            cmd.reply().complete(ExposureOutcome.problem(ExposureCause.INTERNAL_ERROR, "call failed"));
            return;
        }
        answer.whenComplete((o, e) -> cmd.reply().complete(o != null ? o
                : ExposureOutcome.problem(ExposureCause.INTERNAL_ERROR, "call failed")));
    }

    public SubscriberService client() {
        return client;
    }
}
