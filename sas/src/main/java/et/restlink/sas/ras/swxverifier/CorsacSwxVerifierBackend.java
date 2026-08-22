/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.app.swx.ClientListener;
import com.mobius.software.telco.protocols.diameter.app.swx.SwxClientSession;
import com.mobius.software.telco.protocols.diameter.impl.app.swx.SwxProviderImpl;
import com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.SwxRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.common.VendorSpecificApplicationIdImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId;
import com.mobius.software.common.dal.timers.WorkerPool;
import org.restcomm.cluster.IDGenerator;
import org.restcomm.cluster.UUIDGenerator;

import et.restlink.sas.config.SasTransportConfig;
import et.restlink.sas.diameter.DiameterConfig;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real SWx Diameter transport (P2 missing item #5).
 * Drives MAR/MAA against 3GPP AAA/HSS over SWx (TS 29.273 §6.2).
 * Fail-closed: any Diameter error or non-success result-code → FALLBACK.
 */
public final class CorsacSwxVerifierBackend implements SwxVerifierBackend {

    private static final Logger LOG = LogManager.getLogger(CorsacSwxVerifierBackend.class);
    private static final String LINK_ID = "swx-sas";

    private volatile DiameterConfig config;
    private volatile DiameterStack stack;
    private volatile SwxProviderImpl provider;
    private volatile WorkerPool workerPool;
    private final IDGenerator<?> generator = new UUIDGenerator();
    private final Map<String, CompletableFuture<VerificationEvidence>> pending = new ConcurrentHashMap<>();

    public CorsacSwxVerifierBackend(SasTransportConfig transportConfig) {
        this(DiameterConfig.fromTransportConfig(transportConfig));
    }

    public CorsacSwxVerifierBackend(DiameterConfig config) {
        this.config = config;
    }

    public void start() throws Exception {
        DiameterConfig cfg = this.config;
        workerPool = new WorkerPool("SWx-SAS");
        workerPool.start(4);
        stack = new DiameterStackImpl(getClass().getClassLoader(), generator, workerPool, 4,
                cfg.localHost, "SAS SWx", 0L, 10L,
                10_000L, 2_000L, 5_000L, 5_000L, 5_000L);
        stack.getNetworkManager().addLink(LINK_ID,
                InetAddress.getByName("0.0.0.0"), 0,
                InetAddress.getByName(cfg.effectivePeerHost()), cfg.peerPort,
                cfg.isServer, cfg.isSctp, cfg.localHost, cfg.localRealm,
                cfg.effectiveDestinationHost(), cfg.effectiveDestinationRealm(), false);
        registerApplications(stack, LINK_ID, cfg);
        stack.getNetworkManager().startLink(LINK_ID);
        provider = (SwxProviderImpl) stack.getProvider((long) ApplicationIDs.SWX,
                Package.getPackage("com.mobius.software.telco.protocols.diameter.commands.swx"));
        provider.setClientListener(generator.generateID(), new ClientListener() {
            @Override
            public void onInitialAnswer(SwxAnswer answer,
                    com.mobius.software.telco.protocols.diameter.app.ClientAuthSessionStateless<SwxRequest> session,
                    String linkID, com.mobius.software.telco.protocols.diameter.AsyncCallback callback) {
                handleAnswer(answer);
            }
            @Override
            public void onTimeout(com.mobius.software.telco.protocols.diameter.commands.DiameterRequest request,
                    com.mobius.software.telco.protocols.diameter.DiameterSession session) {
                LOG.warn("SWx MAR timeout");
            }
            @Override
            public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {}
        });
        LOG.info("SWx Diameter transport started");
    }

    public void stop() {
        DiameterStack oldStack = stack;
        stack = null;
        provider = null;
        if (oldStack != null) {
            try {
                oldStack.stop();
            } catch (Exception e) {
                LOG.warn("SWx stop", e);
            }
        }
        WorkerPool oldPool = workerPool;
        workerPool = null;
        if (oldPool != null) {
            oldPool.stop();
        }
    }

    public DiameterConfig activeConfig() {
        return config;
    }

    public synchronized void reconfigure(DiameterConfig cfg) throws Exception {
        DiameterConfig oldCfg = this.config;
        DiameterStack oldStack = this.stack;
        SwxProviderImpl oldProvider = this.provider;
        WorkerPool oldPool = this.workerPool;
        try {
            stop();
            this.config = cfg;
            start();
        } catch (Exception e) {
            LOG.warn("SWx reconfigure failed — keeping previous stack reference", e);
            this.config = oldCfg;
            this.stack = oldStack;
            this.provider = oldProvider;
            this.workerPool = oldPool;
            throw e;
        }
    }

    private static void registerApplications(DiameterStack stack, String linkId,
                                             DiameterConfig cfg) throws Exception {
        if (cfg.applications == null) {
            return;
        }
        for (DiameterConfig.Application app : cfg.applications) {
            if (app == null) {
                continue;
            }
            List<VendorSpecificApplicationId> vendorAppIds = new ArrayList<>();
            List<Long> authAppIds = new ArrayList<>();
            List<Long> acctAppIds = new ArrayList<>();
            String type = app.type == null || app.type.isBlank()
                    ? DiameterConfig.DEFAULT_TYPE_AUTH : app.type.toLowerCase();
            if (DiameterConfig.DEFAULT_TYPE_ACCT.equals(type)) {
                acctAppIds.add(app.id);
            } else if (DiameterConfig.DEFAULT_TYPE_VENDOR.equals(type)) {
                vendorAppIds.add(new VendorSpecificApplicationIdImpl(app.vendorId, app.id, null));
            } else {
                authAppIds.add(app.id);
            }
            stack.getNetworkManager().registerApplication(linkId, vendorAppIds, authAppIds, acctAppIds,
                    implPackage(app), commandPackage(app));
        }
    }

    private static Package commandPackage(DiameterConfig.Application app) {
        return Package.getPackage("com.mobius.software.telco.protocols.diameter.commands."
                + appName(app));
    }

    private static Package implPackage(DiameterConfig.Application app) {
        return Package.getPackage("com.mobius.software.telco.protocols.diameter.impl.commands."
                + appName(app));
    }

    private static String appName(DiameterConfig.Application app) {
        return app.name == null ? "swx" : app.name.trim().toLowerCase();
    }

    @Override
    public CompletableFuture<VerificationEvidence> verify(String msisdn, String imsi,
                                                           AccessTech accessTech, long nowMs) {
        if (accessTech != AccessTech.WIFI || provider == null) {
            return CompletableFuture.completedFuture(
                    VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX"));
        }
        CompletableFuture<VerificationEvidence> future = new CompletableFuture<>();
        String sessionId = "swx-" + msisdn + "-" + nowMs;
        pending.put(sessionId, future);
        try {
            DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
            MultimediaAuthRequest mar = provider.getMessageFactory().createMultimediaAuthRequest(
                    link.getLocalHost(), link.getLocalRealm(),
                    link.getDestinationHost(), link.getDestinationRealm(),
                    msisdn, 1L, provider.getAvpFactory().getSIPAuthDataItem());
            SwxClientSession session = (SwxClientSession) provider.getSessionFactory().createClientSession(mar);
            session.sendInitialRequest(mar, new com.mobius.software.telco.protocols.diameter.AsyncCallback() {
                @Override public void onSuccess() {}
                @Override public void onError(DiameterException ex) {
                    pending.remove(sessionId);
                    future.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAR"));
                }
            });
        } catch (Exception e) {
            pending.remove(sessionId);
            future.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAR"));
        }
        return future;
    }

    private void handleAnswer(SwxAnswer answer) {
        long rc = answer.getResultCode() != null ? answer.getResultCode() : -1;
        VerificationEvidence evidence = (rc == ResultCodes.DIAMETER_SUCCESS)
                ? VerificationEvidence.ok(true, true, true, "SWX-MAR/MAA")
                : VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAA");
        pending.values().forEach(f -> f.complete(evidence));
        pending.clear();
    }
}
