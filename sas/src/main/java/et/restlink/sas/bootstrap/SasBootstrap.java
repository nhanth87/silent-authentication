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

import et.restlink.sas.config.SasAdminRuntimeConfig;
import et.restlink.sas.config.SasTransportConfig;
import et.restlink.sas.coordinator.VerifyCoordinator;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.fsm.VerificationFsm;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.ras.mapverifier.InMemoryMapVerifierBackend;
import et.restlink.sas.ras.mapverifier.Jss7MapVerifierBackend;
import et.restlink.sas.ras.mapverifier.MapVerifierBackend;
import et.restlink.sas.ras.mapverifier.MapVerifierRaEndpoint;
import et.restlink.sas.ras.mapverifier.MapVerifierResourceAdaptor;
import et.restlink.sas.ras.resolver.CgnatLogResolverBackend;
import et.restlink.sas.ras.resolver.InMemoryResolverBackend;
import et.restlink.sas.ras.resolver.RadiusAccountingListenerBackend;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.ras.resolver.ResolverRaEndpoint;
import et.restlink.sas.ras.resolver.ResolverResourceAdaptor;
import et.restlink.sas.ras.s6averifier.CorsacS6aVerifierBackend;
import et.restlink.sas.ras.s6averifier.InMemoryS6aVerifierBackend;
import et.restlink.sas.ras.s6averifier.S6aVerifierBackend;
import et.restlink.sas.ras.s6averifier.S6aVerifierRaEndpoint;
import et.restlink.sas.ras.s6averifier.S6aVerifierResourceAdaptor;
import et.restlink.sas.ras.swxverifier.CorsacSwxVerifierBackend;
import et.restlink.sas.ras.swxverifier.InMemorySwxVerifierBackend;
import et.restlink.sas.ras.swxverifier.SwxVerifierBackend;
import et.restlink.sas.ras.swxverifier.SwxVerifierRaEndpoint;
import et.restlink.sas.ras.swxverifier.SwxVerifierResourceAdaptor;
import et.restlink.sas.sbbs.VerifySbb;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Clone of the Elisa {@code ElisaBootstrap} wiring pattern, specialized for the
 * Silent Auth SAS: start the micro-container, register the Resolver + MAP
 * Verifier RAs, register the {@link VerifySbb} type, map the request event,
 * and expose a synchronous {@link #submit} bridge for the CAMARA REST surface.
 */
@ApplicationScoped
public class SasBootstrap {

    private static final Logger LOG = LogManager.getLogger(SasBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    VerifyCoordinator coordinator;

    @Inject
    SasTransportConfig transportConfig;

    @Inject
    SasAdminRuntimeConfig adminRuntimeConfig;

    private volatile AssurancePolicy policy;
    private volatile VerificationFsm fsm;

    private volatile ResolverRaEndpoint resolverEndpoint;
    private volatile ResolverBackend resolverBackendRef;
    private volatile MapVerifierRaEndpoint mapVerifierEndpoint;
    private volatile S6aVerifierRaEndpoint s6aVerifierEndpoint;
    private volatile SwxVerifierRaEndpoint swxVerifierEndpoint;
    private volatile Jss7MapVerifierBackend jss7MapBackend;
    private volatile CorsacS6aVerifierBackend corsacS6aBackend;
    private volatile CorsacSwxVerifierBackend corsacSwxBackend;
    private volatile RadiusAccountingListenerBackend radiusListenerBackend;
    private volatile CgnatLogResolverBackend cgnatLogBackend;
    private volatile boolean started;

    void onStart(@Observes StartupEvent ev) {
        if (started) {
            return;
        }
        started = true;
        policy = assurancePolicy();
        fsm = new VerificationFsm(policy);
        LOG.info("Silent Auth SAS bootstrap starting (fail-closed)");
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        wireResolverRa();
        wireMapVerifierRa();
        wireS6aVerifierRa();
        wireSwxVerifierRa();
        registerSbbTypes();
        mapEventToSbb();
        LOG.info("=== Silent Auth SAS ready — resolver={}ms map={}ms s6a={}ms swx={}ms total={}ms ===",
                SasTimeouts.RESOLVER_MS, SasTimeouts.MAP_MS,
                SasTimeouts.DIAMETER_MS, SasTimeouts.DIAMETER_MS, SasTimeouts.TOTAL_MS);
    }

    private void wireResolverRa() {
        ResolverBackend backend;
        if (transportConfig.useCgnatResolver()) {
            String logPath = transportConfig.cgnatLogPath();
            if (logPath == null || logPath.isBlank()) {
                LOG.warn("[SAS] sas.transport.resolver=cgnat but cgnat-log is empty — "
                        + "falling back to in-memory resolver (fail-closed)");
                backend = inMemoryResolver();
            } else {
                CgnatLogResolverBackend cgnat = new CgnatLogResolverBackend(
                        java.nio.file.Path.of(logPath),
                        transportConfig.cgnatRefreshMs(),
                        transportConfig.cgnatStaleMs());
                cgnat.reload();
                cgnat.start();
                cgnatLogBackend = cgnat;
                backend = cgnat;
                LOG.info("[SAS] Resolver transport = CGNAT log tail ({}, refresh={}ms, stale={}ms)",
                        logPath, transportConfig.cgnatRefreshMs(), transportConfig.cgnatStaleMs());
            }
        } else if (transportConfig.useRadiusResolver()) {
            RadiusAccountingListenerBackend radius = new RadiusAccountingListenerBackend(
                    transportConfig.radiusPort(),
                    transportConfig.radiusSecret(),
                    transportConfig.radiusStaleAfterMs());
            radius.start();
            radiusListenerBackend = radius;
            backend = radius;
            LOG.info("[SAS] Resolver transport = RADIUS accounting listener (udp/{}, stale={}ms)",
                    radius.localPort(), transportConfig.radiusStaleAfterMs());
        } else {
            backend = inMemoryResolver();
        }
        ResolverResourceAdaptor ra = new ResolverResourceAdaptor();
        ra.setBackend(backend);
        resolverBackendRef = backend;
        resolverEndpoint = new ResolverRaEndpoint(ra);
        container.registerRa(resolverEndpoint, resolverEndpoint);
        LOG.info("Resolver RA wired (CGNAT-safe IP:port:ts)");
    }

    private void wireMapVerifierRa() {
        MapVerifierBackend backend;
        if (transportConfig.useJss7Map()) {
            String cfgPath = transportConfig.jss7ConfigPath();
            if (cfgPath == null || cfgPath.isBlank()) {
                LOG.warn("[SAS] sas.transport.map=jss7 but sas.transport.jss7.config is empty — "
                        + "falling back to in-memory MAP backend (fail-closed)");
                backend = mapBackend();
            } else {
                jss7MapBackend = new Jss7MapVerifierBackend(
                        java.nio.file.Path.of(cfgPath),
                        transportConfig.jss7HlrGt(),
                        transportConfig.jss7LocalGt());
                jss7MapBackend.start();
                backend = jss7MapBackend;
                LOG.info("[SAS] MAP verifier transport = jSS7 (HLR GT={})",
                        transportConfig.jss7HlrGt());
            }
        } else {
            backend = mapBackend();
        }
        MapVerifierResourceAdaptor ra = new MapVerifierResourceAdaptor();
        ra.setBackend(backend);
        mapVerifierEndpoint = new MapVerifierRaEndpoint(ra);
        container.registerRa(mapVerifierEndpoint, mapVerifierEndpoint);
        LOG.info("MAP verifier RA wired (PSI+SAI, no interconnect ATI)");
    }

    private void wireS6aVerifierRa() {
        S6aVerifierBackend backend;
        if (transportConfig.useCorsacS6a()) {
            corsacS6aBackend = new CorsacS6aVerifierBackend(transportConfig);
            try {
                corsacS6aBackend.start();
                backend = corsacS6aBackend;
                LOG.info("[SAS] S6a transport = corsac-diameter");
            } catch (Exception e) {
                LOG.warn("[SAS] S6a corsac start failed — falling back to in-memory", e);
                backend = s6aBackend();
            }
        } else {
            backend = s6aBackend();
        }
        S6aVerifierResourceAdaptor ra = new S6aVerifierResourceAdaptor();
        ra.setBackend(backend);
        s6aVerifierEndpoint = new S6aVerifierRaEndpoint(ra);
        container.registerRa(s6aVerifierEndpoint, s6aVerifierEndpoint);
        LOG.info("S6a verifier RA wired (ULR/ULA+AIR/AIA, own HSS only)");
    }

    private void wireSwxVerifierRa() {
        SwxVerifierBackend backend;
        if (transportConfig.useCorsacSwx()) {
            corsacSwxBackend = new CorsacSwxVerifierBackend(transportConfig);
            try {
                corsacSwxBackend.start();
                backend = corsacSwxBackend;
                LOG.info("[SAS] SWx transport = corsac-diameter");
            } catch (Exception e) {
                LOG.warn("[SAS] SWx corsac start failed — falling back to in-memory", e);
                backend = swxBackend();
            }
        } else {
            backend = swxBackend();
        }
        SwxVerifierResourceAdaptor ra = new SwxVerifierResourceAdaptor();
        ra.setBackend(backend);
        swxVerifierEndpoint = new SwxVerifierRaEndpoint(ra);
        container.registerRa(swxVerifierEndpoint, swxVerifierEndpoint);
        LOG.info("SWx verifier RA wired (EAP-AKA, TS 29.273, own AAA/HSS only)");
    }

    private void registerSbbTypes() {
        container.registerSbbType(VerifySbb.class, () -> new VerifySbb(coordinator, fsm));
        LOG.info("SBB types registered");
    }

    private void mapEventToSbb() {
        container.mapEventToSbb(VerifyRequestEvent.class, "VerifySbb");
        LOG.info("Event→SBB mapping bound: VerifyRequestEvent → VerifySbb");
    }

    /**
     * Synchronous bridge from the Quarkus REST surface into the SLEE event
     * router. Idempotent per {@code reqId}: completed requests return the
     * cached result, in-flight duplicates await the same future.
     */
    public CompletableFuture<VerifyResult> submit(VerifyRequestEvent evt) {
        String reqId = evt.reqId();
        VerifyResult cached = coordinator.cached(reqId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (coordinator.isInFlight(reqId)) {
            return coordinator.register(reqId);
        }
        CompletableFuture<VerifyResult> future = coordinator.register(reqId);
        ActivityContextInterface aci = container.createActivityContext(reqId);
        SimpleSbbLocalObject lo = container.acquireEntity(reqId, VerifySbb.class);
        container.attach(reqId, lo);
        container.routeEvent(evt, aci);
        return future;
    }

    /** Release the per-request SBB entity after the terminal result is read. */
    public void release(String reqId) {
        try {
            container.releaseEntity(reqId);
        } catch (RuntimeException re) {
            LOG.debug("release({}) — {}", reqId, re.toString());
        }
        coordinator.forget(reqId);
    }

    /** Expose the active resolver backend (for the session-tuple collector). */
    public ResolverBackend resolverBackend() {
        return resolverBackendRef;
    }

    @PreDestroy
    void shutdown() {
        if (swxVerifierEndpoint != null) {
            swxVerifierEndpoint.deactivate();
        }
        if (s6aVerifierEndpoint != null) {
            s6aVerifierEndpoint.deactivate();
        }
        if (mapVerifierEndpoint != null) {
            mapVerifierEndpoint.deactivate();
        }
        if (jss7MapBackend != null) {
            jss7MapBackend.stop();
        }
        if (corsacS6aBackend != null) {
            corsacS6aBackend.stop();
        }
        if (corsacSwxBackend != null) {
            corsacSwxBackend.stop();
        }
        if (resolverEndpoint != null) {
            resolverEndpoint.deactivate();
        }
        if (radiusListenerBackend != null) {
            radiusListenerBackend.stop();
        }
        if (cgnatLogBackend != null) {
            cgnatLogBackend.stop();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
        LOG.info("Silent Auth SAS stopped");
    }

    // ---- pilot seeds (replace with operator-side PGW/HLR adapters) ----

    private AssurancePolicy assurancePolicy() {
        try {
            return AssurancePolicy.fromRuntime(adminRuntimeConfig::read);
        } catch (RuntimeException e) {
            LOG.error("[SAS] invalid sas.assurance.* config — using built-in defaults (fail-closed)", e);
            return AssurancePolicy.defaults();
        }
    }

    private InMemoryResolverBackend inMemoryResolver() {
        InMemoryResolverBackend b = new InMemoryResolverBackend();
        // Demo cellular session — the /verify demo headers default to this tuple.
        b.seed("10.20.30.40", 55555, "+251911111111", "655010000000001", 30_000L);
        return b;
    }

    private InMemoryMapVerifierBackend mapBackend() {
        InMemoryMapVerifierBackend b = new InMemoryMapVerifierBackend();
        // attached, last IMSI change 10 days ago (no SIM swap), region "AA".
        b.seed("+251911111111", "655010000000001", true, daysAgo(10), "AA");
        return b;
    }

    private InMemoryS6aVerifierBackend s6aBackend() {
        InMemoryS6aVerifierBackend b = new InMemoryS6aVerifierBackend();
        // registered (ULR/ULA), last IMSI change 10 days ago (AIR/AIA), region "AA".
        b.seed("+251911111111", "655010000000001", true, daysAgo(10), "AA");
        return b;
    }

    private InMemorySwxVerifierBackend swxBackend() {
        InMemorySwxVerifierBackend b = new InMemorySwxVerifierBackend();
        // EAP-AKA registered, last IMSI change 10 days ago, region "AA".
        b.seed("+251911111111", "655010000000001", true, daysAgo(10), "AA");
        return b;
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }
}