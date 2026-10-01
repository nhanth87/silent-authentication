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
import et.restlink.sas.entitlement.OperatorTokenRedemption;
import et.restlink.sas.events.SubscriberQueryEvent;
import et.restlink.sas.exposure.ExposureConfig;
import et.restlink.sas.exposure.ExposureCoordinator;
import et.restlink.sas.exposure.ResolverAccess;
import et.restlink.sas.ras.mapverifier.InMemoryMapVerifierBackend;
import et.restlink.sas.ras.mapverifier.MapVerifierBackend;
import et.restlink.sas.ras.mapverifier.MapVerifierRaEndpoint;
import et.restlink.sas.ras.mapverifier.MapVerifierResourceAdaptor;
import et.restlink.sas.ras.resolver.CgnatLogResolverBackend;
import et.restlink.sas.ras.resolver.InMemoryResolverBackend;
import et.restlink.sas.ras.resolver.PcrfSdResolverBackend;
import et.restlink.sas.ras.resolver.RadiusAccountingListenerBackend;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.ras.resolver.ResolverRaEndpoint;
import et.restlink.sas.ras.resolver.ResolverResourceAdaptor;
import et.restlink.sas.ras.s6averifier.CorsacS6aVerifierBackend;
import et.restlink.sas.ras.s6averifier.InMemoryS6aVerifierBackend;
import et.restlink.sas.ras.s6averifier.S6aVerifierBackend;
import et.restlink.sas.ras.s6averifier.S6aVerifierRaEndpoint;
import et.restlink.sas.ras.s6averifier.S6aVerifierResourceAdaptor;
import et.restlink.sas.sbbs.SubscriberSbb;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureJson;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.SubscriberVerificationResult;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;
import java.time.Instant;
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
public class EcsBootstrap implements SasEntitlementEngine, SubscriberService, ResolverAccess {

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

    @Inject
    ExposureConfig exposureConfig;

    @Inject
    ExposureCoordinator exposureCoordinator;

    @Inject
    OperatorTokenRedemption redemption;

    /** Read-only IMSI → MSISDN export for the {@code db} binding source (optional). */
    @Inject
    @ConfigProperty(name = "sas.binding.subscriber-db-path")
    Optional<String> subscriberDbPath;

    private volatile AuthVectorRaEndpoint authVectorEndpoint;
    private volatile SubscriberBindingRaEndpoint subscriberBindingEndpoint;
    private volatile MapSendImsiBinding mapSendImsiBinding;
    private volatile Jss7MapVerifierBackend jss7MapBackend;
    private volatile CorsacSwxVerifierBackend corsacSwxBackend;
    private volatile ResolverRaEndpoint resolverEndpoint;
    private volatile MapVerifierRaEndpoint mapVerifierEndpoint;
    private volatile S6aVerifierRaEndpoint s6aVerifierEndpoint;
    private volatile RadiusAccountingListenerBackend radiusListenerBackend;
    private volatile CgnatLogResolverBackend cgnatLogBackend;
    private volatile PcrfSdResolverBackend sdResolverBackend;
    private volatile CorsacS6aVerifierBackend corsacS6aBackend;
    private volatile InMemoryMapVerifierBackend inMemoryMapBackend;
    private volatile InMemoryS6aVerifierBackend inMemoryS6aBackend;
    private volatile String resolverSource = "LAB_RESOLVER";
    private volatile ResolverBackend resolverBackendRef;
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
        if (exposureConfig.enabled()) {
            wireSubscriberExposure();
        } else {
            LOG.info("[ECS] subscriber exposure off (sas.exposure.enabled=false)");
        }
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

    // ---- subscriber exposure (layered ADR §4; M2) ------------------------------

    /**
     * The RAs the Subscriber Services answer from — resolver, MAP verifier, S6a verifier —
     * plus {@link SubscriberSbb}. The MAP verifier rides the same jSS7 stack as SAI and
     * SendIMSI (one stack per host).
     */
    private void wireSubscriberExposure() {
        wireResolverRa();
        boolean liveMap = wireMapVerifierRa();
        boolean liveS6a = wireS6aVerifierRa();
        SubscriberSbb.Sources sources = new SubscriberSbb.Sources(resolverSource,
                exposureConfig.monitoringViaS6a(), liveMap,
                liveMap ? "MAP_PSI" : "LAB_HLR", liveS6a ? "S6A_ULR" : "LAB_HSS",
                this::labSimChange);
        container.registerSbbType(SubscriberSbb.class,
                () -> new SubscriberSbb(exposureCoordinator, sources, redemption));
        container.mapEventToSbb(SubscriberQueryEvent.class, "SubscriberSbb");
        LOG.info("[ECS] subscriber exposure on — resolver={} monitoring={} redeem-at-ecs={} durable-ledger={}",
                resolverSource, exposureConfig.monitoringViaS6a() ? sources.s6aSource() : sources.mapSource(),
                entitlementConfig.redeemAtEcs(), redemption.durableLedger());
    }

    private void wireResolverRa() {
        ResolverBackend backend;
        if (transportConfig.useCgnatResolver() && !blank(transportConfig.cgnatLogPath())) {
            CgnatLogResolverBackend cgnat = new CgnatLogResolverBackend(
                    Path.of(transportConfig.cgnatLogPath()),
                    transportConfig.cgnatRefreshMs(), transportConfig.cgnatStaleMs());
            cgnat.reload();
            cgnat.start();
            cgnatLogBackend = cgnat;
            backend = cgnat;
            resolverSource = "CGNAT_LOG";
        } else if (transportConfig.useRadiusResolver()) {
            RadiusAccountingListenerBackend radius = new RadiusAccountingListenerBackend(
                    transportConfig.radiusPort(), transportConfig.radiusSecret(),
                    transportConfig.radiusStaleAfterMs());
            radius.start();
            radiusListenerBackend = radius;
            backend = radius;
            resolverSource = "RADIUS";
        } else if (transportConfig.useSdResolver()) {
            PcrfSdResolverBackend sd = null;
            try {
                sd = new PcrfSdResolverBackend(transportConfig.diameterPeerHost(),
                        transportConfig.sdPeerPort(), transportConfig.sdSctp(),
                        (int) transportConfig.sdTimeoutMs(), transportConfig.sdStaleAfterMs());
                sd.start();
            } catch (Exception e) {
                LOG.error("[ECS] PCRF Sd resolver start failed — UE ID answers NETWORK_ERROR", e);
                sd = null;
            }
            sdResolverBackend = sd;
            backend = sd != null ? sd : failingResolver();
            resolverSource = "GX";
        } else {
            InMemoryResolverBackend lab = new InMemoryResolverBackend();
            lab.seed("10.20.30.40", 55555, "+251911111111", "655010000000001", 30_000L);
            backend = lab;
            resolverSource = "LAB_RESOLVER";
            LOG.warn("[ECS] LAB resolver (in-memory) — preflight refuses this in prod");
        }
        ResolverResourceAdaptor ra = new ResolverResourceAdaptor();
        ra.setBackend(backend);
        resolverBackendRef = backend;
        resolverEndpoint = new ResolverRaEndpoint(ra);
        container.registerRa(resolverEndpoint, resolverEndpoint);
    }

    /** A resolver whose transport did not start: every lookup is a resolver error. */
    private static ResolverBackend failingResolver() {
        return (ip, port, ts) -> java.util.concurrent.CompletableFuture.completedFuture(
                et.restlink.sas.model.ResolverResult.miss(et.restlink.sas.model.FallbackReason.RESOLVER_ERROR));
    }

    /** @return true when the verifier is the live jSS7 stack (needs the IMSI). */
    private boolean wireMapVerifierRa() {
        MapVerifierBackend backend;
        boolean live = transportConfig.useJss7Map() && hasJss7Config();
        if (live) {
            backend = mapStack();
        } else {
            InMemoryMapVerifierBackend lab = new InMemoryMapVerifierBackend();
            lab.seed("+251911111111", "655010000000001", true, daysAgo(10), "AA");
            inMemoryMapBackend = lab;
            backend = lab;
        }
        MapVerifierResourceAdaptor ra = new MapVerifierResourceAdaptor();
        ra.setBackend(backend);
        mapVerifierEndpoint = new MapVerifierRaEndpoint(ra);
        container.registerRa(mapVerifierEndpoint, mapVerifierEndpoint);
        return live;
    }

    /** @return true when the verifier is the live corsac S6a client. */
    private boolean wireS6aVerifierRa() {
        S6aVerifierBackend backend = null;
        if (transportConfig.useCorsacS6a()) {
            try {
                CorsacS6aVerifierBackend corsac = new CorsacS6aVerifierBackend(transportConfig);
                corsac.start();
                corsacS6aBackend = corsac;
                backend = corsac;
            } catch (Exception e) {
                LOG.error("[ECS] S6a start failed — monitoring via S6a answers NETWORK_ERROR", e);
            }
        }
        boolean live = backend != null;
        if (!live) {
            InMemoryS6aVerifierBackend lab = new InMemoryS6aVerifierBackend();
            lab.seed("+251911111111", "655010000000001", true, daysAgo(10), "AA");
            inMemoryS6aBackend = lab;
            backend = lab;
        }
        S6aVerifierResourceAdaptor ra = new S6aVerifierResourceAdaptor();
        ra.setBackend(backend);
        s6aVerifierEndpoint = new S6aVerifierRaEndpoint(ra);
        container.registerRa(s6aVerifierEndpoint, s6aVerifierEndpoint);
        return live;
    }

    /**
     * SIM-change evidence. Today only the lab backends hold a binding age; live transports
     * answer "no evidence" (fail closed, never "not swapped") until a SIM DB / Sh UDR source
     * is wired — the same position sas-camara held.
     */
    private Optional<SubscriberSbb.SimChange> labSimChange(String msisdnE164) {
        // Follow the transport monitoring uses. A live transport with a lab backend next to
        // it must not borrow the lab's invented binding age: that would be fabricated
        // evidence in a real configuration.
        if (exposureConfig.monitoringViaS6a()) {
            InMemoryS6aVerifierBackend s6a = inMemoryS6aBackend;
            return s6a == null ? Optional.empty() : s6a.lastImsiChangeEpochMs(msisdnE164)
                    .map(at -> new SubscriberSbb.SimChange(Instant.ofEpochMilli(at), "LAB_HSS"));
        }
        InMemoryMapVerifierBackend map = inMemoryMapBackend;
        if (map != null) {
            Optional<Long> at = map.lastImsiChangeEpochMs(msisdnE164);
            if (at.isPresent()) {
                return Optional.of(new SubscriberSbb.SimChange(Instant.ofEpochMilli(at.get()), "LAB_HLR"));
            }
        }
        return Optional.empty();
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    /** The wired resolver, for the device-declared session tuple (D5); null when exposure is off. */
    @Override
    public ResolverBackend resolverBackend() {
        return resolverBackendRef;
    }

    // ---- SubscriberService: the bridge from the exposure REST surface into the SLEE ----

    @Override
    public CompletableFuture<ExposureOutcome<UeIdInfo>> retrieveUeId(ExposureContext ctx, UeIdReq req) {
        return submit(SubscriberQueryEvent.Operation.UE_ID, ctx, req, null);
    }

    @Override
    public CompletableFuture<ExposureOutcome<MonitoringEventReports>> monitor(
            ExposureContext ctx, String scsAsId, MonitoringEventSubscription req) {
        return submit(SubscriberQueryEvent.Operation.MONITORING, ctx, req, scsAsId);
    }

    @Override
    public CompletableFuture<ExposureOutcome<SimChangeInfo>> retrieveSimChange(
            ExposureContext ctx, SimChangeReq req) {
        return submit(SubscriberQueryEvent.Operation.SIM_CHANGE, ctx, req, null);
    }

    @Override
    public CompletableFuture<ExposureOutcome<SubscriberVerificationResult>> redeemOperatorToken(
            ExposureContext ctx, OperatorTokenRedeemReq req) {
        return submit(SubscriberQueryEvent.Operation.REDEEM, ctx, req, null);
    }

    /**
     * Dedup by (operation, request id, body), then route one event to a fresh activity.
     * The activity is released when the answer is in, whoever was waiting for it.
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<ExposureOutcome<T>> submit(SubscriberQueryEvent.Operation op,
                                                             ExposureContext ctx, Object req,
                                                             String scsAsId) {
        if (!exposureConfig.enabled() || !started) {
            return CompletableFuture.completedFuture(ExposureOutcome.problem(
                    ExposureCause.TRANSPORT_UNAVAILABLE, "subscriber exposure is off"));
        }
        String key = ExposureCoordinator.answerKey(op.name(), ctx.requestId(),
                ExposureJson.write(req) + '|' + scsAsId);
        ExposureOutcome<?> cached = exposureCoordinator.cached(key, System.currentTimeMillis());
        if (cached != null) {
            return CompletableFuture.completedFuture((ExposureOutcome<T>) cached);
        }
        ExposureCoordinator.Registration reg = exposureCoordinator.register(key);
        if (reg.owner()) {
            String activity = "exp-" + java.util.UUID.randomUUID();
            ActivityContextInterface aci = container.createActivityContext(activity);
            SimpleSbbLocalObject lo = container.acquireEntity(activity, SubscriberSbb.class);
            container.attach(activity, lo);
            // Never release on the completing thread: that is the container's event thread,
            // still inside SubscriberSbb.onEvent, and releasing the entity from there blocks
            // the dispatcher (every later event would hang). Release after the handler returns.
            reg.future().whenCompleteAsync((o, e) -> releaseQuietly(activity));
            container.routeEvent(new SubscriberQueryEvent(activity, key, op, ctx, req, scsAsId), aci);
        }
        return reg.future().thenApply(o -> (ExposureOutcome<T>) o);
    }

    private void releaseQuietly(String activity) {
        try {
            container.releaseEntity(activity);
        } catch (RuntimeException re) {
            LOG.debug("release({}) — {}", activity, re.toString());
        }
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
        for (var endpoint : new com.microjainslee.api.RaEndpointPort[] {
                s6aVerifierEndpoint, mapVerifierEndpoint, resolverEndpoint}) {
            if (endpoint != null) {
                endpoint.deactivate();
            }
        }
        if (corsacS6aBackend != null) {
            corsacS6aBackend.stop();
        }
        if (radiusListenerBackend != null) {
            radiusListenerBackend.stop();
        }
        if (cgnatLogBackend != null) {
            cgnatLogBackend.stop();
        }
        if (sdResolverBackend != null) {
            sdResolverBackend.stop();
        }
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
