/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.app.ClientAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.app.swx.ClientListener;
import com.mobius.software.telco.protocols.diameter.app.swx.SwxClientSession;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.MultimediaAuthRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.PushProfileAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.PushProfileRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.ServerAssignmentAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.ServerAssignmentRequest;
import com.mobius.software.telco.protocols.diameter.commands.swx.SwxAnswer;
import com.mobius.software.telco.protocols.diameter.commands.swx.SwxRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.AvpNotSupportedException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.swx.SwxProviderImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.common.VendorSpecificApplicationIdImpl;
import com.mobius.software.telco.protocols.diameter.primitives.cxdx.SIPAuthDataItem;
import com.mobius.software.telco.protocols.diameter.primitives.cxdx.ServerAssignmentTypeEnum;
import com.mobius.software.telco.protocols.diameter.primitives.common.ExperimentalResult;
import com.mobius.software.telco.protocols.diameter.primitives.common.VendorSpecificApplicationId;

import et.restlink.sas.config.SasTransportConfig;
import et.restlink.sas.diameter.DiameterConfig;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.common.dal.timers.WorkerPool;
import org.restcomm.cluster.IDGenerator;
import org.restcomm.cluster.UUIDGenerator;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real SWx Diameter transport (P2 missing item #5), own 3GPP AAA/HSS only.
 *
 * <p>Evidence pipeline (TS 29.273), one Diameter dialog/session per stage,
 * 2 s total budget ({@link SasTimeouts#DIAMETER_MS}) shared across stages:</p>
 * <ol>
 *   <li>MAR/MAA (§6.2.2, primary) — EAP-AKA auth vectors ⇒ SIM-swap
 *       freshness; the auth-scheme AVP must stay EAP-AKA for TS.43.</li>
 *   <li>SAR/SAA (§6.3.2) — AAA server-name registration accepted by the HSS
 *       ⇒ reachable (+ Non-3GPP-User-Data). Configurable
 *       ({@code swxSarEnabled}), default on — SAR is genuinely AAA-initiated
 *       per spec.</li>
 *   <li>PPR/PPA probe (§6.6.2) — optional ({@code swxPprProbeEnabled}).
 *       Spec deviation: PPR is HSS-initiated per TS 29.273; corsac exposes a
 *       client-side factory so the lab HSS is probed actively. Default off.</li>
 * </ol>
 *
 * <p>Fail-closed everywhere: any timeout, send error, unmatched answer or
 * non-success result-code completes with a {@link FallbackReason}, never a
 * soft pass. Answers are correlated per Diameter Session-Id
 * (RFC 6733 §8.1) with Hop-by-Hop Id (§8.2) as fallback; an answer that
 * matches no pending exchange is dropped and its requester times out.</p>
 */
public final class CorsacSwxVerifierBackend implements SwxVerifierBackend {

    private static final Logger LOG = LogManager.getLogger(CorsacSwxVerifierBackend.class);
    private static final String LINK_ID = "swx-sas";

    private volatile DiameterConfig config;
    private volatile DiameterStack stack;
    private volatile SwxProviderImpl provider;
    private volatile WorkerPool workerPool;
    private final IDGenerator<?> generator = new UUIDGenerator();
    private final SwxExchangeCorrelator correlator = new SwxExchangeCorrelator();

    public CorsacSwxVerifierBackend(SasTransportConfig transportConfig) {
        this(DiameterConfig.fromTransportConfig(transportConfig));
        if (transportConfig.diameterSwxPeerPort() != transportConfig.diameterPeerPort()) {
            this.config.peerPort = transportConfig.diameterSwxPeerPort();
        }
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
                InetAddress.getByName(cfg.effectivePeerHost()), cfg.peerPort,
                InetAddress.getByName("0.0.0.0"), 0,
                cfg.isServer, cfg.isSctp, cfg.localHost, cfg.localRealm,
                cfg.effectiveDestinationHost(), cfg.effectiveDestinationRealm(), false);
        registerApplications(stack, LINK_ID, cfg);
        stack.getNetworkManager().startLink(LINK_ID);
        provider = (SwxProviderImpl) stack.getProvider((long) ApplicationIDs.SWX,
                Package.getPackage("com.mobius.software.telco.protocols.diameter.commands.swx"));
        provider.setClientListener(generator.generateID(), new ClientListener() {
            @Override
            public void onInitialAnswer(SwxAnswer answer,
                    ClientAuthSessionStateless<SwxRequest> session,
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
                LOG.warn("SWx {} timeout session={}",
                        request == null ? "exchange" : request.getClass().getSimpleName(), sessionId);
                if (!correlator.fail(sessionId, new TimeoutException("SWx stack timeout"))) {
                    LOG.warn("SWx timeout for unknown session {} — already failed closed", sessionId);
                }
            }
            @Override
            public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {}
        });
        LOG.info("SWx Diameter transport started");
    }

    public void stop() {
        correlator.failAll(new TimeoutException("SWx transport stopped"));
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
            if (app == null || !"swx".equals(appName(app))) {
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
                appName(app), "MultimediaAuthRequest");
    }

    private static Package implPackage(DiameterConfig.Application app) {
        return loadPackage("com.mobius.software.telco.protocols.diameter.impl.commands.",
                appName(app), "MultimediaAuthRequestImpl");
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
                    CorsacSwxVerifierBackend.class.getClassLoader());
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
        return app.name == null ? "swx" : app.name.trim().toLowerCase();
    }

    @Override
    public CompletableFuture<VerificationEvidence> verify(String msisdn, String imsi,
                                                           AccessTech accessTech, long nowMs) {
        CompletableFuture<VerificationEvidence> out = new CompletableFuture<>();
        if (accessTech != AccessTech.WIFI || provider == null || stack == null) {
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX"));
            return out;
        }
        long deadline = System.currentTimeMillis() + SasTimeouts.DIAMETER_MS;
        try {
            runMarStage(out, deadline, msisdn, imsi, config.sarOrDefault(), config.pprProbeOrDefault());
        } catch (Exception e) {
            LOG.warn("SWx MAR send failed msisdn={}", msisdn, e);
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAR"));
        }
        return out;
    }

    /** Stage 1 — MAR/MAA (primary): EAP-AKA vector freshness. */
    private void runMarStage(CompletableFuture<VerificationEvidence> out, long deadline,
                             String msisdn, String imsi, boolean sarEnabled, boolean pprProbe)
            throws Exception {
        DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
        MultimediaAuthRequest mar = provider.getMessageFactory().createMultimediaAuthRequest(
                link.getLocalHost(), link.getLocalRealm(),
                link.getDestinationHost(), link.getDestinationRealm(),
                identity(msisdn, imsi), 1L, provider.getAvpFactory().getSIPAuthDataItem());
        mar.getSIPAuthDataItem().setSIPAuthenticationScheme("EAP-AKA");
        SwxClientSession session =
                (SwxClientSession) provider.getSessionFactory().createClientSession(mar);
        dispatch(session, mar, deadline)
                .thenAccept(answer -> onMaa(out, deadline, msisdn, imsi, sarEnabled, pprProbe, answer))
                .exceptionally(ex -> {
                    failStage(out, ex, "SWX-MAR");
                    return null;
                });
    }

    private void onMaa(CompletableFuture<VerificationEvidence> out, long deadline,
                       String msisdn, String imsi, boolean sarEnabled, boolean pprProbe,
                       SwxAnswer answer) {
        MultimediaAuthAnswer maa = as(answer, MultimediaAuthAnswer.class);
        List<SIPAuthDataItem> items = maa == null ? null : maa.getSIPAuthDataItem();
        VerificationEvidence evidence = SwxEvidence.fromMaa(resultCode(answer),
                experimentalCode(answer),
                SwxEvidence.authenticatorCount(items),
                SwxEvidence.firstScheme(items));
        if (evidence.failed()) {
            out.complete(evidence);
            return;
        }
        if (!sarEnabled) {
            out.complete(evidence);
            return;
        }
        try {
            runSarStage(out, deadline, msisdn, imsi, pprProbe, evidence);
        } catch (Exception e) {
            LOG.warn("SWx SAR send failed msisdn={}", msisdn, e);
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-SAR"));
        }
    }

    /** Stage 2 — SAR/SAA: AAA server-name registration at the own HSS. */
    private void runSarStage(CompletableFuture<VerificationEvidence> out, long deadline,
                             String msisdn, String imsi, boolean pprProbe,
                             VerificationEvidence maaEvidence) throws Exception {
        DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
        ServerAssignmentRequest sar = provider.getMessageFactory().createServerAssignmentRequest(
                link.getLocalHost(), link.getLocalRealm(),
                link.getDestinationHost(), link.getDestinationRealm(),
                identity(msisdn, imsi), ServerAssignmentTypeEnum.REGISTRATION);
        SwxClientSession session =
                (SwxClientSession) provider.getSessionFactory().createClientSession(sar);
        dispatch(session, sar, deadline)
                .thenAccept(answer -> onSaa(out, deadline, msisdn, pprProbe, maaEvidence, answer))
                .exceptionally(ex -> {
                    failStage(out, ex, "SWX-SAR");
                    return null;
                });
    }

    private void onSaa(CompletableFuture<VerificationEvidence> out, long deadline,
                       String msisdn, boolean pprProbe, VerificationEvidence maaEvidence,
                       SwxAnswer answer) {
        ServerAssignmentAnswer saa = as(answer, ServerAssignmentAnswer.class);
        VerificationEvidence evidence = SwxEvidence.fromSaa(resultCode(answer),
                experimentalCode(answer),
                saa != null && saa.getNon3GPPUserData() != null,
                saa != null && saa.get3GPPAAAServerName() != null);
        if (evidence.failed()) {
            out.complete(evidence);
            return;
        }
        VerificationEvidence combined = SwxEvidence.combine(maaEvidence, evidence, "SWX-MAR+SAR");
        if (!pprProbe) {
            out.complete(combined);
            return;
        }
        try {
            runPprStage(out, deadline, msisdn, combined);
        } catch (Exception e) {
            LOG.warn("SWx PPR probe send failed msisdn={}", msisdn, e);
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-PPR"));
        }
    }

    /**
     * Optional stage 3 — PPR probe. Lab-only deviation from TS 29.273 §6.6
     * (HSS-initiated): the SAS actively confirms the profile push path of its
     * own HSS/AAA; PPA must succeed or the whole verify fails.
     */
    private void runPprStage(CompletableFuture<VerificationEvidence> out, long deadline,
                             String msisdn, VerificationEvidence combined) throws Exception {
        DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
        PushProfileRequest ppr = provider.getMessageFactory().createPushProfileRequest(
                link.getLocalHost(), link.getLocalRealm(),
                link.getDestinationHost(), link.getDestinationRealm(),
                msisdn);
        SwxClientSession session =
                (SwxClientSession) provider.getSessionFactory().createClientSession(ppr);
        dispatch(session, ppr, deadline)
                .thenAccept(answer -> onPpa(out, combined, answer))
                .exceptionally(ex -> {
                    failStage(out, ex, "SWX-PPR");
                    return null;
                });
    }

    private void onPpa(CompletableFuture<VerificationEvidence> out,
                       VerificationEvidence combined, SwxAnswer answer) {
        VerificationEvidence evidence = SwxEvidence.fromPpa(resultCode(answer),
                experimentalCode(answer));
        if (evidence.failed()) {
            out.complete(evidence);
            return;
        }
        out.complete(SwxEvidence.combine(combined, evidence, "SWX-MAR+SAR+PPR"));
    }

    /**
     * One dialog/session per stage: register correlation under the request's
     * minted Session-Id, enforce the remaining shared budget, abort on send
     * error. The stage future never outlives {@code deadline}.
     */
    private CompletableFuture<SwxAnswer> dispatch(SwxClientSession session, SwxRequest request,
                                                  long deadline)
            throws AvpNotSupportedException, TimeoutException {
        String sessionId = sessionId(request);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("SWx request without Session-Id");
        }
        long remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs <= 0) {
            throw new TimeoutException("SWx budget exhausted before send");
        }
        CompletableFuture<SwxAnswer> stage = correlator.register(sessionId);
        stage.whenComplete((answer, throwable) -> correlator.remove(sessionId));
        stage.orTimeout(remainingMs, TimeUnit.MILLISECONDS);
        session.sendInitialRequest(request, new AsyncCallback() {
            @Override
            public void onSuccess() {
                correlator.bindHopByHop(sessionId, request.getHopByHopIdentifier());
            }

            @Override
            public void onError(DiameterException ex) {
                LOG.warn("SWx {} rejected session={}",
                        request.getClass().getSimpleName(), sessionId, ex);
                stage.completeExceptionally(ex);
            }
        });
        return stage;
    }

    /** Correlated answer dispatch — exactly one pending exchange is completed. */
    private void handleAnswer(SwxAnswer answer, ClientAuthSessionStateless<SwxRequest> session) {
        String sessionId = sessionId(answer);
        if ((sessionId == null || sessionId.isBlank()) && session != null) {
            sessionId = session.getID();
        }
        if (correlator.complete(sessionId, answer)) {
            return;
        }
        Long hopByHopId = answer == null ? null : answer.getHopByHopIdentifier();
        if (correlator.completeByHopByHop(hopByHopId, answer)) {
            LOG.debug("SWx answer matched by Hop-by-Hop Id {}", hopByHopId);
            return;
        }
        LOG.warn("SWx unmatched answer session={} hop={} — dropped, fail-closed",
                sessionId, hopByHopId);
    }

    private static void failStage(CompletableFuture<VerificationEvidence> out, Throwable ex,
                                  String protocolTag) {
        Throwable cause = ex == null ? null : (ex.getCause() != null ? ex.getCause() : ex);
        if (cause instanceof TimeoutException) {
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, protocolTag));
        } else {
            LOG.warn("SWx stage {} failed", protocolTag, cause);
            out.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, protocolTag));
        }
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

    private static long resultCode(SwxAnswer answer) {
        Long rc = answer.getResultCode();
        return rc == null ? -1L : rc;
    }

    private static Long experimentalCode(SwxAnswer answer) {
        try {
            ExperimentalResult experimentalResult = answer.getExperimentalResult();
            return experimentalResult == null ? null : experimentalResult.getExperimentalResultCode();
        } catch (DiameterException e) {
            return null;
        }
    }

    private static <A extends SwxAnswer> A as(SwxAnswer answer, Class<A> type) {
        return type.isInstance(answer) ? type.cast(answer) : null;
    }
}
