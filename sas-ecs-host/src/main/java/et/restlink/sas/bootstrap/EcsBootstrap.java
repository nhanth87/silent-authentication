/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.bootstrap;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;

import et.restlink.sas.api.SasEntitlementEngine;
import et.restlink.sas.config.SasTransportConfig;
import et.restlink.sas.coordinator.EntitlementCoordinator;
import et.restlink.sas.coordinator.EntitlementSessions;
import et.restlink.sas.entitlement.EntitlementConfig;
import et.restlink.sas.entitlement.EntitlementTokenService;
import et.restlink.sas.events.Ts43RequestEvent;
import et.restlink.sas.model.Ts43Result;
import et.restlink.sas.ras.authvector.AuthVectorBackend;
import et.restlink.sas.ras.authvector.AuthVectorRaEndpoint;
import et.restlink.sas.ras.authvector.AuthVectorResourceAdaptor;
import et.restlink.sas.ras.authvector.InMemoryAuthVectorBackend;
import et.restlink.sas.ras.binding.SubscriberBindingRaEndpoint;
import et.restlink.sas.ras.binding.SubscriberBindingResourceAdaptor;
import et.restlink.sas.ras.binding.backend.MapSendImsiBinding;
import et.restlink.sas.ras.binding.backend.SubscriberDbBinding;
import et.restlink.sas.ras.mapverifier.Jss7MapVerifierBackend;
import et.restlink.sas.ras.swxverifier.CorsacSwxVerifierBackend;
import et.restlink.sas.sbbs.EntitlementSbb;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The entitlement server's (sas-ecs) only seam into micro-jainslee (gate H24): start
 * the container, register the auth-vector and subscriber-binding RAs and the
 * {@link EntitlementSbb}, map {@link Ts43RequestEvent}, and bridge the {@code /ts43}
 * REST surface into the event router.
 *
 * <p>This binary owns its own signalling stacks — its own MAP association to the HLR
 * (own GT/SSN from {@code sas.transport.jss7.*}) and its own Diameter SWx peer (own
 * Origin-Host) — independent of the CAMARA server's. The only thing the two binaries
 * share is the operator-token trust (sas-token), published at {@code /jwks.json}.</p>
 */
@ApplicationScoped
public class EcsBootstrap implements SasEntitlementEngine {

    private static final Logger LOG = LogManager.getLogger(EcsBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    SasTransportConfig transportConfig;

    @Inject
    EntitlementCoordinator entitlementCoordinator;

    @Inject
    EntitlementSessions entitlementSessions;

    @Inject
    EntitlementTokenService entitlementTokens;

    @Inject
    EntitlementConfig entitlementConfig;

    /** Read-only IMSI → MSISDN export for the {@code db} binding source (optional). */
    @Inject
    @ConfigProperty(name = "sas.binding.subscriber-db-path")
    Optional<String> subscriberDbPath;

    private volatile AuthVectorRaEndpoint authVectorEndpoint;
    private volatile SubscriberBindingRaEndpoint subscriberBindingEndpoint;
    private volatile MapSendImsiBinding mapSendImsiBinding;
    private volatile Jss7MapVerifierBackend jss7MapBackend;
    private volatile CorsacSwxVerifierBackend corsacSwxBackend;
    private volatile boolean started;

    void onStart(@Observes StartupEvent ev) {
        if (started) {
            return;
        }
        started = true;
        LOG.info("Restlink entitlement server (sas-ecs) bootstrap starting (fail-closed)");
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        wireAuthVectorRa();
        wireSubscriberBindingRa();
        container.registerSbbType(EntitlementSbb.class, () -> new EntitlementSbb(
                entitlementCoordinator, entitlementSessions, entitlementTokens, entitlementConfig));
        container.mapEventToSbb(Ts43RequestEvent.class, "EntitlementSbb");
        LOG.info("=== sas-ecs ready — EntitlementSbb bound to Ts43RequestEvent, token format={} ===",
                entitlementConfig.operatorJweFormat() ? "operator-jwe" : "hmac (legacy)");
    }

    /** Auth vectors: MAP SAI (own HLR) or Diameter SWx MAR (own HSS); lab: in-memory. */
    private void wireAuthVectorRa() {
        AuthVectorBackend backend;
        if (transportConfig.useMapAuthVector()) {
            backend = mapStack();
            LOG.info("[ECS] auth-vector transport = MAP SAI (TS 29.002)");
        } else if (transportConfig.useCorsacAuthVector()) {
            corsacSwxBackend = new CorsacSwxVerifierBackend(transportConfig);
            try {
                corsacSwxBackend.start();
                LOG.info("[ECS] auth-vector transport = Diameter SWx (TS 29.273)");
            } catch (Exception e) {
                LOG.error("[ECS] SWx start failed — no auth vectors (fail-closed)", e);
                corsacSwxBackend = null;
            }
            backend = corsacSwxBackend != null ? corsacSwxBackend : new InMemoryAuthVectorBackend();
        } else {
            backend = new InMemoryAuthVectorBackend();
            LOG.warn("[ECS] LAB auth-vector backend (in-memory) — preflight refuses this in prod");
        }
        AuthVectorResourceAdaptor ra = new AuthVectorResourceAdaptor();
        ra.setBackend(backend);
        authVectorEndpoint = new AuthVectorRaEndpoint(ra);
        container.registerRa(authVectorEndpoint, authVectorEndpoint);
    }

    /** IMSI → MSISDN confirmation: MAP SendIMSI (own HLR) and/or the read-only export. */
    private void wireSubscriberBindingRa() {
        SubscriberBindingResourceAdaptor ra = new SubscriberBindingResourceAdaptor();
        String order = bindingSourceOrder();
        if (order.contains("map-smi") && hasJss7Config()) {
            Jss7MapVerifierBackend stack = mapStack();
            MapSendImsiBinding smi = new MapSendImsiBinding(stack.mapProvider(),
                    transportConfig.jss7HlrGt(), transportConfig.jss7LocalGt());
            smi.start();
            mapSendImsiBinding = smi;
            ra.addBackend("map-smi", smi);
            LOG.info("[ECS] binding source map-smi = MAP SendIMSI (TS 29.002)");
        }
        if (order.contains("db") && subscriberDbPath != null
                && subscriberDbPath.filter(v -> !v.isBlank()).isPresent()) {
            SubscriberDbBinding db = new SubscriberDbBinding(Path.of(subscriberDbPath.get().trim()));
            ra.addBackend("db", db);
            LOG.info("[ECS] binding source db = read-only export ({} entries)", db.size());
        }
        SubscriberBindingRaEndpoint endpoint = new SubscriberBindingRaEndpoint(ra);
        List<String> accepted = endpoint.configureSources(order);
        container.registerRa(endpoint, endpoint);
        subscriberBindingEndpoint = endpoint;
        LOG.info("[ECS] subscriber binding RA wired sources={}", accepted);
    }

    /** One MAP stack per process: SAI and SendIMSI share the association to the HLR. */
    private Jss7MapVerifierBackend mapStack() {
        if (jss7MapBackend == null) {
            jss7MapBackend = new Jss7MapVerifierBackend(Path.of(transportConfig.jss7ConfigPath()),
                    transportConfig.jss7HlrGt(), transportConfig.jss7LocalGt());
            jss7MapBackend.start();
        }
        return jss7MapBackend;
    }

    private boolean hasJss7Config() {
        String p = transportConfig.jss7ConfigPath();
        return p != null && !p.isBlank();
    }

    private String bindingSourceOrder() {
        String order = transportConfig.bindingSourceOrder();
        return (order == null || order.isBlank()) ? "db" : order;
    }

    /**
     * Bridge from {@code /ts43} into the SLEE router. Both TS.43 hops share one
     * activity key (the {@code reqId}), so the RESPOND hop lands on the entity that
     * holds the EAP state; a replayed hop returns the recorded answer.
     */
    @Override
    public CompletableFuture<Ts43Result> submitTs43(Ts43RequestEvent evt) {
        String reqId = evt.reqId();
        Ts43RequestEvent.Phase phase = evt.phase();
        Ts43Result cached = entitlementCoordinator.cached(reqId, phase, System.currentTimeMillis());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (entitlementCoordinator.isInFlight(reqId, phase)) {
            return entitlementCoordinator.register(reqId, phase);
        }
        CompletableFuture<Ts43Result> future = entitlementCoordinator.register(reqId, phase);
        ActivityContextInterface aci = container.createActivityContext(reqId);
        SimpleSbbLocalObject lo = container.acquireEntity(reqId, EntitlementSbb.class);
        container.attach(reqId, lo);
        container.routeEvent(evt, aci);
        return future;
    }

    /** Release the activity; {@link EntitlementSessions#discard} wipes the EAP state. */
    @Override
    public void releaseTs43(String reqId) {
        try {
            container.releaseEntity(reqId);
        } catch (RuntimeException re) {
            LOG.debug("releaseTs43({}) — {}", reqId, re.toString());
        }
        entitlementCoordinator.forgetActivity(reqId);
    }

    @PreDestroy
    void shutdown() {
        if (subscriberBindingEndpoint != null) {
            subscriberBindingEndpoint.deactivate();
        }
        if (authVectorEndpoint != null) {
            authVectorEndpoint.deactivate();
        }
        if (mapSendImsiBinding != null) {
            mapSendImsiBinding.stop();
        }
        if (jss7MapBackend != null) {
            jss7MapBackend.stop();
        }
        if (corsacSwxBackend != null) {
            corsacSwxBackend.stop();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
        LOG.info("sas-ecs stopped");
    }
}
