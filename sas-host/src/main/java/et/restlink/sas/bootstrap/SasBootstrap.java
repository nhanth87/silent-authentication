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

import et.restlink.sas.api.ExposureAdapters;
import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.config.SasAdminRuntimeConfig;
import et.restlink.sas.coordinator.VerifyCoordinator;
import et.restlink.sas.events.VerifyRequestEvent;
import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.fsm.VerificationFsm;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.ras.exposure.ExposureClient;
import et.restlink.sas.ras.exposure.ExposureRaEndpoint;
import et.restlink.sas.sbbs.VerifySbb;
import et.restlink.sas.subscriber.SubscriberService;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.CompletableFuture;

/**
 * The CAMARA gateway's (sas-camara) only seam into micro-jainslee (gate H24): start the
 * container, register {@code exposure-ra} and the {@link VerifySbb} type, map the request
 * event, and bridge the CAMARA REST surface into the event router.
 *
 * <p>Layered ADR (§2): this binary never signals the core. Every fact about a subscriber
 * — who holds an IP:port, reachability, location metadata, SIM change, operator-token
 * redemption — comes from the entitlement server's subscriber exposure API through
 * {@code exposure-ra}. No MAP, Diameter or SCTP stack exists in this process.</p>
 */
@ApplicationScoped
public class SasBootstrap implements SasVerifyEngine {

    private static final Logger LOG = LogManager.getLogger(SasBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    VerifyCoordinator coordinator;

    @Inject
    SasAdminRuntimeConfig adminRuntimeConfig;

    /** Exposure API root on sas-ecs (lab: plain HTTP on loopback; prod: HTTPS, PRO-36). */
    @Inject
    @ConfigProperty(name = "sas.exposure.client.base-url",
            defaultValue = "http://127.0.0.1:8095/sas-subscriber-exposure/v1")
    String exposureBaseUrl;

    /** This gateway's AF identity on the exposure hop. */
    @Inject
    @ConfigProperty(name = "sas.exposure.client.af-id", defaultValue = "sas-camara")
    String exposureAfId;

    /** CAMARA location policy (ADR R8): the home PLMN, MCC+MNC digits. */
    @Inject
    @ConfigProperty(name = "sas.policy.home-plmn", defaultValue = "63601")
    String homePlmn;

    @Inject
    @ConfigProperty(name = "sas.policy.location-max-age-minutes", defaultValue = "1440")
    int locationMaxAgeMinutes;

    private volatile AssurancePolicy policy;
    private volatile VerificationFsm fsm;
    private volatile ExposureClient exposureClient;
    private volatile ExposureRaEndpoint exposureEndpoint;
    private volatile boolean started;

    void onStart(@Observes StartupEvent ev) {
        if (started) {
            return;
        }
        started = true;
        policy = assurancePolicy();
        fsm = new VerificationFsm(policy);
        LOG.info("Silent Auth CAMARA gateway bootstrap starting (fail-closed)");
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        wireExposureRa();
        ExposureAdapters.LocationPolicy location = locationPolicy();
        container.registerSbbType(VerifySbb.class,
                () -> new VerifySbb(coordinator, fsm, afId(), location));
        container.mapEventToSbb(VerifyRequestEvent.class, "VerifySbb");
        LOG.info("=== sas-camara ready — subscriber exposure at {} (af={}), ue-id={}ms evidence={}ms total={}ms ===",
                exposureClient.baseUri(), afId(), ExposureAdapters.UE_ID_BUDGET_MS,
                ExposureAdapters.EVIDENCE_BUDGET_MS, SasTimeouts.TOTAL_MS);
    }

    private void wireExposureRa() {
        exposureClient = new ExposureClient(exposureBaseUrl);
        exposureEndpoint = new ExposureRaEndpoint(exposureClient);
        container.registerRa(exposureEndpoint, exposureEndpoint);
        LOG.info("Exposure RA wired — the gateway's only path to the subscriber");
    }

    private ExposureAdapters.LocationPolicy locationPolicy() {
        try {
            return ExposureAdapters.LocationPolicy.of(homePlmn, locationMaxAgeMinutes);
        } catch (RuntimeException e) {
            LOG.error("[SAS] invalid sas.policy.home-plmn={} — no location counts as plausible", homePlmn);
            return new ExposureAdapters.LocationPolicy("---", "--", -1);
        }
    }

    /**
     * Synchronous bridge from the Quarkus REST surface into the SLEE event
     * router. Idempotent per {@code reqId}: completed requests return the
     * cached result, in-flight duplicates await the same future.
     */
    @Override
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
    @Override
    public void release(String reqId) {
        try {
            container.releaseEntity(reqId);
        } catch (RuntimeException re) {
            LOG.debug("release({}) — {}", reqId, re.toString());
        }
        coordinator.forget(reqId);
    }

    /**
     * The exposure client behind {@code exposure-ra}, for the callers that are not SBBs:
     * the OAuth network anchor, operator-token redemption and SIM Swap (H24 allow-list).
     */
    @Override
    public SubscriberService subscribers() {
        return exposureClient;
    }

    /** Configured exposure API root, for the admin status page. */
    public String exposureBaseUrl() {
        return exposureBaseUrl;
    }

    @Override
    public String afId() {
        return exposureAfId == null || exposureAfId.isBlank() ? "sas-camara" : exposureAfId.trim();
    }

    @PreDestroy
    void shutdown() {
        if (exposureEndpoint != null) {
            exposureEndpoint.deactivate();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
        LOG.info("sas-camara stopped");
    }

    private AssurancePolicy assurancePolicy() {
        try {
            return AssurancePolicy.fromRuntime(adminRuntimeConfig::read);
        } catch (RuntimeException e) {
            LOG.error("[SAS] invalid sas.assurance.* config — using built-in defaults (fail-closed)", e);
            return AssurancePolicy.defaults();
        }
    }
}
