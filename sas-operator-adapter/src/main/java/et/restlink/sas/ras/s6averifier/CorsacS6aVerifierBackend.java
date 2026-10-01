/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.app.ClientAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.app.s6a.ClientListener;
import com.mobius.software.telco.protocols.diameter.app.s6a.S6aClientSession;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.AvpNotSupportedException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.s6a.S6aProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.common.VendorSpecificApplicationIdImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.ExperimentalResult;
import com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId;
import com.mobius.software.telco.protocols.diameter.primitives.gx.RATTypeEnum;

import et.restlink.sas.config.SasTransportConfig;
import et.restlink.sas.diameter.DiameterConfig;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.cluster.IDGenerator;
import org.restcomm.cluster.UUIDGenerator;

import com.mobius.software.common.dal.timers.WorkerPool;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real S6a Diameter transport (P2 missing item #6), own HSS only.
 *
 * <p>Evidence pipeline (TS 29.272), one Diameter dialog/session per stage,
 * 2 s budget ({@link SasTimeouts#DIAMETER_MS}):</p>
 * <ol>
 *   <li>ULR/ULA (316, §5.2.2.2) — attachment liveness + subscriber status.</li>
 *   <li>Sh UDR/SNR (TS 29.328/29.329, read-only) — SIM-swap freshness (open item,
 *       not wired here). AIR/AIA and IDR/IDA are deliberately absent: AIR consumes
 *       EPS vectors + advances SQN, IDR is HSS→MME push.</li>
 * </ol>
 *
 * <p>Fail-closed everywhere: any timeout, send error, unmatched answer or
 * non-success result-code completes with a {@link FallbackReason}, never a
 * soft pass. Answers are correlated per Diameter Session-Id
 * (RFC 6733 §8.1) with Hop-by-Hop Id (§8.2) as fallback; an answer that
 * matches no pending exchange is dropped and its requester times out.</p>
 */
public final class CorsacS6aVerifierBackend implements S6aVerifierBackend {

    private static final Logger LOG = LogManager.getLogger(CorsacS6aVerifierBackend.class);
    private static final String LINK_ID = "s6a-sas";

    private volatile DiameterConfig config;
    private volatile DiameterStack stack;
    private volatile S6aProviderImpl provider;
    private volatile WorkerPool workerPool;
    private final IDGenerator<?> generator = new UUIDGenerator();
    private final S6aExchangeCorrelator correlator = new S6aExchangeCorrelator();

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
                InetAddress.getByName(cfg.effectivePeerHost()), cfg.peerPort,
                InetAddress.getByName("0.0.0.0"), 0,
                cfg.isServer, cfg.isSctp, cfg.localHost, cfg.localRealm,
                cfg.effectiveDestinationHost(), cfg.effectiveDestinationRealm(), false);
        registerApplications(stack, LINK_ID, cfg);
        stack.getNetworkManager().startLink(LINK_ID);
        provider = (S6aProviderImpl) stack.getProvider((long) ApplicationIDs.S6A,
                Package.getPackage("com.mobius.software.telco.protocols.diameter.commands.s6a"));
        provider.setClientListener(generator.generateID(), new ClientListener() {
            @Override
            public void onInitialAnswer(S6aAnswer answer,
                    ClientAuthSessionStateless<S6aRequest> session,
                    String linkID, AsyncCallback callback) {
                handleAnswer(answer, session);
            }
            @Override
            public void onTimeout(DiameterRequest request,
                    com.mobius.software.telco.protocols.diameter.DiameterSession session) {
                String sessionId = sessionId(request);
                if (session != null && (sessionId == null || sessionId.isBlank())) {
                    sessionId = session.getID();
                }
                LOG.warn("S6a {} timeout session={}",
                        request == null ? "exchange" : request.getClass().getSimpleName(), sessionId);
                if (!correlator.fail(sessionId, new TimeoutException("S6a stack timeout"))) {
                    LOG.warn("S6a timeout for unknown session {} — already failed closed", sessionId);
                }
            }
            @Override
            public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {}
        });
        LOG.info("S6a Diameter transport started");
    }

    public void stop() {
        correlator.failAll(new TimeoutException("S6a transport stopped"));
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
            if (app == null || !"s6a".equals(appName(app))) {
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
                    commandPackage(app), implPackage(app));
        }
    }

    private static Package commandPackage(DiameterConfig.Application app) {
        return loadPackage("com.mobius.software.telco.protocols.diameter.commands.",
                appName(app), "UpdateLocationRequest");
    }

    private static Package implPackage(DiameterConfig.Application app) {
        return loadPackage("com.mobius.software.telco.protocols.diameter.impl.commands.",
                appName(app), "UpdateLocationRequestImpl");
    }

    /**
     * Package lookup with forced anchor-class loading — corsac resolves
     * command packages reflectively and {@link Package#getPackage(String)}
     * returns null until at least one class of the package is loaded.
     */
    private static Package loadPackage(String prefix, String name, String anchorClass) {
        String fqn = prefix + name;
        try {
            Class.forName(fqn + "." + anchorClass, true,
                    CorsacS6aVerifierBackend.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("diameter package missing: " + fqn, e);
        }
        Package pkg = Package.getPackage(fqn);
        if (pkg == null) {
            throw new IllegalStateException("package not loaded: " + fqn);
        }
        return pkg;
    }

    private static String appName(DiameterConfig.Application app) {
        return app.name == null ? "s6a" : app.name.trim().toLowerCase();
    }

    @Override
    public CompletableFuture<VerificationEvidence> verify(String msisdn, String imsi,
                                                           AccessTech accessTech, long nowMs) {
        CompletableFuture<VerificationEvidence> out = new CompletableFuture<>();
        if ((accessTech != AccessTech.LTE && accessTech != AccessTech.NR)
                || provider == null || stack == null) {
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A"));
            return out;
        }
        long deadline = System.currentTimeMillis() + SasTimeouts.DIAMETER_MS;
        try {
            runUlrStage(out, deadline, msisdn, imsi, accessTech);
        } catch (Exception e) {
            LOG.warn("S6a ULR send failed msisdn={}", msisdn, e);
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A-ULR"));
        }
        return out;
    }

    /** Stage 1 — ULR/ULA (316): attachment liveness + subscriber status. */
    private void runUlrStage(CompletableFuture<VerificationEvidence> out, long deadline,
                             String msisdn, String imsi, AccessTech accessTech)
            throws Exception {
        DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
        UpdateLocationRequest ulr = provider.getMessageFactory().createUpdateLocationRequest(
                link.getLocalHost(), link.getLocalRealm(),
                link.getDestinationHost(), link.getDestinationRealm(),
                ratType(accessTech), provider.getAvpFactory().getULRFlags(),
                Unpooled.wrappedBuffer(S6aEvidence.visitedPlmnTbcd(config.effectiveVisitedPlmn())));
        ulr.setUsername(identity(msisdn, imsi));
        S6aClientSession session =
                (S6aClientSession) provider.getSessionFactory().createClientSession(ulr);
        dispatch(session, ulr, deadline)
                .thenAccept(answer -> onUla(out, deadline, msisdn, imsi, answer))
                .exceptionally(ex -> {
                    failStage(out, ex, "S6A-ULR");
                    return null;
                });
    }

    private void onUla(CompletableFuture<VerificationEvidence> out, long deadline,
                       String msisdn, String imsi, S6aAnswer answer) {
        UpdateLocationAnswer ula = as(answer, UpdateLocationAnswer.class);
        VerificationEvidence evidence = S6aEvidence.fromUla(resultCode(answer),
                experimentalCode(answer),
                ula != null && S6aEvidence.subscriberBarred(ula.getSubscriptionData()));
        if (evidence.failed()) {
            out.complete(evidence);
            return;
        }
        // SIM-swap freshness on 4G/5G is NOT sourced from S6a AIR: AIR consumes a
        // real EPS vector set and advances the AuC SQN (MAC-failure re-sync risk),
        // and IDR is an HSS→MME push (wrong direction for a read query). Freshness
        // requires a read-only Sh UDR/SNR backend (TS 29.328/29.329, open item) or
        // the operator's CAMARA SIM Swap. Until then the S6a leg contributes
        // reachable+location only and the FSM fail-closes the swap dimension.
        out.complete(evidence);
    }

    /**
     * One dialog/session per stage: register correlation under the request's
     * minted Session-Id, enforce the remaining shared budget, abort on send
     * error. The stage future never outlives {@code deadline}.
     */
    private CompletableFuture<S6aAnswer> dispatch(S6aClientSession session, S6aRequest request,
                                                  long deadline)
            throws AvpNotSupportedException, TimeoutException {
        String sessionId = sessionId(request);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("S6a request without Session-Id");
        }
        long remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs <= 0) {
            throw new TimeoutException("S6a budget exhausted before send");
        }
        CompletableFuture<S6aAnswer> stage = correlator.register(sessionId);
        stage.whenComplete((answer, throwable) -> correlator.remove(sessionId));
        stage.orTimeout(remainingMs, TimeUnit.MILLISECONDS);
        session.sendInitialRequest(request, new AsyncCallback() {
            @Override
            public void onSuccess() {
                correlator.bindHopByHop(sessionId, request.getHopByHopIdentifier());
            }

            @Override
            public void onError(DiameterException ex) {
                LOG.warn("S6a {} rejected session={}",
                        request.getClass().getSimpleName(), sessionId, ex);
                stage.completeExceptionally(ex);
            }
        });
        return stage;
    }

    /** Correlated answer dispatch — exactly one pending exchange is completed. */
    private void handleAnswer(S6aAnswer answer, ClientAuthSessionStateless<S6aRequest> session) {
        String sessionId = sessionId(answer);
        if ((sessionId == null || sessionId.isBlank()) && session != null) {
            sessionId = session.getID();
        }
        if (correlator.complete(sessionId, answer)) {
            return;
        }
        Long hopByHopId = answer == null ? null : answer.getHopByHopIdentifier();
        if (correlator.completeByHopByHop(hopByHopId, answer)) {
            LOG.debug("S6a answer matched by Hop-by-Hop Id {}", hopByHopId);
            return;
        }
        LOG.warn("S6a unmatched answer session={} hop={} — dropped, fail-closed",
                sessionId, hopByHopId);
    }

    private static void failStage(CompletableFuture<VerificationEvidence> out, Throwable ex,
                                  String protocolTag) {
        Throwable cause = ex == null ? null : (ex.getCause() != null ? ex.getCause() : ex);
        if (cause instanceof TimeoutException) {
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, protocolTag));
        } else {
            LOG.warn("S6a stage {} failed", protocolTag, cause);
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, protocolTag));
        }
    }

    private static RATTypeEnum ratType(AccessTech accessTech) {
        return accessTech == AccessTech.NR ? RATTypeEnum.NR : RATTypeEnum.EUTRAN;
    }

    private static String identity(String msisdn, String imsi) {
        return imsi != null && !imsi.isBlank() ? imsi : msisdn;
    }

    private static String sessionId(DiameterMessage message) {
        if (message == null) {
            return null;
        }
        try {
            return message.getSessionId();
        } catch (DiameterException e) {
            return null;
        }
    }

    private static long resultCode(S6aAnswer answer) {
        Long rc = answer.getResultCode();
        return rc == null ? -1L : rc;
    }

    private static Long experimentalCode(S6aAnswer answer) {
        try {
            ExperimentalResult experimentalResult = answer.getExperimentalResult();
            return experimentalResult == null ? null : experimentalResult.getExperimentalResultCode();
        } catch (DiameterException e) {
            return null;
        }
    }

    private static <A extends S6aAnswer> A as(S6aAnswer answer, Class<A> type) {
        return type.isInstance(answer) ? type.cast(answer) : null;
    }
}
