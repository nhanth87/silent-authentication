/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.app.s6a.ClientListener;
import com.mobius.software.telco.protocols.diameter.app.s6a.S6aClientSession;
import com.mobius.software.telco.protocols.diameter.impl.app.s6a.S6aProviderImpl;
import com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aRequest;
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
 * Real S6a Diameter transport (P2 missing item #6).
 * Drives AIR/AIA (cc 318) against HSS over S6a (TS 29.272 §5.3.2).
 * Fail-closed: any Diameter error or non-success result-code → FALLBACK.
 */
public final class CorsacS6aVerifierBackend implements S6aVerifierBackend {

    private static final Logger LOG = LogManager.getLogger(CorsacS6aVerifierBackend.class);
    private static final String LINK_ID = "s6a-sas";

    private volatile DiameterConfig config;
    private volatile DiameterStack stack;
    private volatile S6aProviderImpl provider;
    private volatile WorkerPool workerPool;
    private final IDGenerator<?> generator = new UUIDGenerator();
    private final Map<String, CompletableFuture<VerificationEvidence>> pending = new ConcurrentHashMap<>();

    public CorsacS6aVerifierBackend(SasTransportConfig transportConfig) {
        this(DiameterConfig.fromTransportConfig(transportConfig));
    }

    public CorsacS6aVerifierBackend(DiameterConfig config) {
        this.config = config;
    }

    public void start() throws Exception {
        DiameterConfig cfg = this.config;
        workerPool = new WorkerPool("S6a-SAS");
        workerPool.start(4);
        stack = new DiameterStackImpl(getClass().getClassLoader(), generator, workerPool, 4,
                cfg.localHost, "SAS S6a", 0L, 10L,
                10_000L, 2_000L, 5_000L, 5_000L, 5_000L);
        stack.getNetworkManager().addLink(LINK_ID,
                InetAddress.getByName("0.0.0.0"), 0,
                InetAddress.getByName(cfg.effectivePeerHost()), cfg.peerPort,
                cfg.isServer, cfg.isSctp, cfg.localHost, cfg.localRealm,
                cfg.effectiveDestinationHost(), cfg.effectiveDestinationRealm(), false);
        registerApplications(stack, LINK_ID, cfg);
        stack.getNetworkManager().startLink(LINK_ID);
        provider = (S6aProviderImpl) stack.getProvider((long) ApplicationIDs.S6A,
                Package.getPackage("com.mobius.software.telco.protocols.diameter.commands.s6a"));
        provider.setClientListener(generator.generateID(), new ClientListener() {
            @Override
            public void onInitialAnswer(S6aAnswer answer,
                    com.mobius.software.telco.protocols.diameter.app.ClientAuthSessionStateless<S6aRequest> session,
                    String linkID, com.mobius.software.telco.protocols.diameter.AsyncCallback callback) {
                handleAnswer(answer);
            }
            @Override
            public void onTimeout(com.mobius.software.telco.protocols.diameter.commands.DiameterRequest request,
                    com.mobius.software.telco.protocols.diameter.DiameterSession session) {
                LOG.warn("S6a AIR timeout");
            }
            @Override
            public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {}
        });
        LOG.info("S6a Diameter transport started");
    }

    public void stop() {
        DiameterStack oldStack = stack;
        stack = null;
        provider = null;
        if (oldStack != null) {
            try {
                oldStack.stop();
            } catch (Exception e) {
                LOG.warn("S6a stop", e);
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
        S6aProviderImpl oldProvider = this.provider;
        WorkerPool oldPool = this.workerPool;
        try {
            stop();
            this.config = cfg;
            start();
        } catch (Exception e) {
            LOG.warn("S6a reconfigure failed — keeping previous stack reference", e);
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
        return app.name == null ? "s6a" : app.name.trim().toLowerCase();
    }

    @Override
    public CompletableFuture<VerificationEvidence> verify(String msisdn, String imsi,
                                                           AccessTech accessTech, long nowMs) {
        if ((accessTech != AccessTech.LTE && accessTech != AccessTech.NR) || provider == null) {
            return CompletableFuture.completedFuture(
                    VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A"));
        }
        CompletableFuture<VerificationEvidence> future = new CompletableFuture<>();
        String sessionId = "s6a-" + msisdn + "-" + nowMs;
        pending.put(sessionId, future);
        try {
            DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
            AuthenticationInformationRequest air = provider.getMessageFactory()
                    .createAuthenticationInformationRequest(
                            link.getLocalHost(), link.getLocalRealm(),
                            link.getDestinationHost(), link.getDestinationRealm(),
                            visitedPlmnId());
            air.setUsername(imsi != null ? imsi : msisdn);
            S6aClientSession session = (S6aClientSession) provider.getSessionFactory().createClientSession(air);
            session.sendInitialRequest(air, new com.mobius.software.telco.protocols.diameter.AsyncCallback() {
                @Override public void onSuccess() {}
                @Override public void onError(DiameterException ex) {
                    pending.remove(sessionId);
                    future.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A-AIR"));
                }
            });
        } catch (Exception e) {
            pending.remove(sessionId);
            future.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A-AIR"));
        }
        return future;
    }

    private void handleAnswer(S6aAnswer answer) {
        long rc = answer.getResultCode() != null ? answer.getResultCode() : -1;
        VerificationEvidence evidence = (rc == ResultCodes.DIAMETER_SUCCESS)
                ? VerificationEvidence.ok(true, true, true, "S6A-AIR/AIA")
                : VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A-AIA");
        pending.values().forEach(f -> f.complete(evidence));
        pending.clear();
    }

    /**
     * Visited-PLMN-Id AVP for the AIR (TS 29.272 §7.3.9). Own HSS only, so this is
     * the home PLMN. MCC 636 (Ethiopia) + MNC 01 (Ethio Telecom), TBCD nibble-swapped.
     */
    private static io.netty.buffer.ByteBuf visitedPlmnId() {
        return io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{0x06, 0x33, 0x16});
    }
}
