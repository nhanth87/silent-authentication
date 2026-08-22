/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7Stack;
import org.restcomm.protocols.ss7.config.Ss7StackBuilder;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPAbortSource;
import org.restcomm.protocols.ss7.map.api.dialog.MAPNoticeProblemDiagnostic;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobilityListener;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ForwardCheckSSIndicationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ResetRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.CancelLocationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.CancelLocationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateLocationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateLocationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeRequest_Mobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeResponse_Mobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.DomainType;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.RequestedInfo;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberInfo;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberState;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberStateChoice;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.DeleteSubscriberDataRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.DeleteSubscriberDataResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.InsertSubscriberDataRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.InsertSubscriberDataResponse;
import org.restcomm.protocols.ss7.sccp.impl.parameter.BCDEvenEncodingScheme;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real MAP verifier backend over a jSS7 (coral-valley) stack.
 *
 * <p>Cloned from the {@code map/load} USSD harness pattern: build the stack with
 * {@link Ss7StackBuilder}, register a {@link MAPServiceMobilityListener} +
 * {@link MAPDialogListener}, then drive one TCAP dialog per stage.</p>
 *
 * <p><strong>FS.11 invariants (never regress):</strong></p>
 * <ul>
 *   <li>PSI ({@code provideSubscriberInfo}, Cat 2.1) for reachable + location.</li>
 *   <li>SAI ({@code sendAuthenticationInfo}, Cat 3.2) for SIM-swap freshness.</li>
 *   <li><strong>Never ATI</strong> on interconnect (Cat 1) — this backend only ever
 *       opens {@code subscriberInfoEnquiryContext} and {@code infoRetrievalContext}
 *       dialogs against the operator's OWN HLR/HSS.</li>
 *   <li>Fail-closed: any timeout / reject / abort ⇒ {@link FallbackReason}.</li>
 *   <li>One dialog per stage; abort on timeout (no dialog leaks).</li>
 * </ul>
 */
public final class Jss7MapVerifierBackend
        implements MapVerifierBackend, MAPServiceMobilityListener, MAPDialogListener {

    private static final Logger LOG = LogManager.getLogger(Jss7MapVerifierBackend.class);

    private static final RoutingIndicator ROUTING = RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE;
    private static final int HLR_SSN = 6;

    private final Path configPath;
    private final String hlrGt;
    private final String localGt;

    private volatile Ss7Stack stack;
    private volatile MAPProvider mapProvider;
    private volatile boolean started;

    private final Map<Long, CompletableFuture<VerificationEvidence>> psiPending = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Boolean>> saiPending = new ConcurrentHashMap<>();

    public Jss7MapVerifierBackend(Path configPath, String hlrGt, String localGt) {
        this.configPath = configPath;
        this.hlrGt = hlrGt;
        this.localGt = localGt;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        try {
            stack = Ss7StackBuilder.build(configPath);
            mapProvider = stack.mapProvider();
            if (mapProvider == null) {
                throw new IllegalStateException("jSS7 stack has no MAP provider");
            }
            mapProvider.getMAPServiceMobility().addMAPServiceListener(this);
            mapProvider.addMAPDialogListener(this);
            started = true;
            LOG.info("[map-verifier] jSS7 stack started — HLR GT={} (PSI+SAI, no ATI)", hlrGt);
        } catch (Exception e) {
            LOG.error("[map-verifier] jSS7 stack failed to start — verifier will fail-closed", e);
            started = false;
        }
    }

    public synchronized void stop() {
        started = false;
        psiPending.values().forEach(f ->
                f.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP")));
        psiPending.clear();
        saiPending.values().forEach(f -> f.complete(Boolean.FALSE));
        saiPending.clear();
        if (stack != null) {
            stack.stop();
            stack = null;
        }
        LOG.info("[map-verifier] jSS7 stack stopped");
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public CompletableFuture<VerificationEvidence> verify(String msisdn, String imsi,
                                                          AccessTech accessTech, long nowMs) {
        if (!started || mapProvider == null) {
            return CompletableFuture.completedFuture(
                    VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP"));
        }
        long deadline = nowMs + SasTimeouts.MAP_MS;
        return CompletableFuture.supplyAsync(() -> {
            try {
                VerificationEvidence psi = runPsi(imsi, deadline);
                if (psi.failed()) {
                    return psi;
                }
                boolean fresh = runSai(imsi, deadline);
                return VerificationEvidence.ok(psi.reachable(), fresh,
                        psi.locationPlausible(), "MAP:PSI+SAI");
            } catch (Exception e) {
                LOG.warn("[map-verifier] verify failed msisdn={} — fail-closed", msisdn, e);
                return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP");
            }
        });
    }

    private VerificationEvidence runPsi(String imsi, long deadline) throws Exception {
        long budget = deadline - System.currentTimeMillis();
        if (budget <= 0) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "MAP");
        }
        MAPDialogMobility dialog = openDialog(MAPApplicationContextName.subscriberInfoEnquiryContext);
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<VerificationEvidence> future = new CompletableFuture<>();
        psiPending.put(dialogId, future);
        try {
            IMSI imsiParam = mapProvider.getMAPParameterFactory().createIMSI(imsi);
            RequestedInfo requestedInfo = mapProvider.getMAPParameterFactory().createRequestedInfo(
                    true,               // locationInformation
                    true,               // subscriberState
                    null,               // extensionContainer
                    false,              // currentLocation
                    DomainType.psDomain,
                    false,              // imei
                    false,              // msClassmark
                    false,              // mnpRequestedInfo
                    false);             // locationInformationEPSSupported
            dialog.addProvideSubscriberInfoRequest(imsiParam, null, requestedInfo, null, null);
            dialog.send();
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            abortDialog(dialog);
            return VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "MAP");
        } finally {
            psiPending.remove(dialogId);
        }
    }

    private boolean runSai(String imsi, long deadline) throws Exception {
        long budget = deadline - System.currentTimeMillis();
        if (budget <= 0) {
            return false;
        }
        MAPDialogMobility dialog = openDialog(MAPApplicationContextName.infoRetrievalContext);
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        saiPending.put(dialogId, future);
        try {
            IMSI imsiParam = mapProvider.getMAPParameterFactory().createIMSI(imsi);
            dialog.addSendAuthenticationInfoRequest(imsiParam, 1, false, true, null, null,
                    null, null, null, false, false);
            dialog.send();
            Boolean fresh = future.get(budget, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(fresh);
        } catch (TimeoutException te) {
            abortDialog(dialog);
            return false;
        } finally {
            saiPending.remove(dialogId);
        }
    }

    private MAPDialogMobility openDialog(MAPApplicationContextName ctxName) throws MAPException {
        MAPApplicationContext ctx = MAPApplicationContext.getInstance(ctxName,
                MAPApplicationContextVersion.version3);
        SccpAddress localAddr = gtAddress(localGt);
        SccpAddress hlrAddr = gtAddress(hlrGt);
        return mapProvider.getMAPServiceMobility()
                .createNewDialog(ctx, localAddr, null, hlrAddr, null);
    }

    private SccpAddress gtAddress(String digits) {
        ParameterFactoryImpl pf = new ParameterFactoryImpl();
        GlobalTitle gt = pf.createGlobalTitle(digits, 0, NumberingPlan.ISDN_TELEPHONY,
                BCDEvenEncodingScheme.INSTANCE, NatureOfAddress.INTERNATIONAL);
        return pf.createSccpAddress(ROUTING, gt, 0, HLR_SSN);
    }

    private void abortDialog(MAPDialog dialog) {
        try {
            dialog.abort(null);
        } catch (Exception e) {
            LOG.debug("[map-verifier] abort failed: {}", e.toString());
        }
    }

    // ---- MAPServiceMobilityListener — the two responses we care about ----

    @Override
    public void onProvideSubscriberInfoResponse(ProvideSubscriberInfoResponse resp) {
        MAPDialog dialog = resp.getMAPDialog();
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<VerificationEvidence> future = psiPending.get(dialogId);
        if (future == null) {
            return;
        }
        try {
            SubscriberInfo info = resp.getSubscriberInfo();
            boolean reachable = false;
            boolean locationPlausible = false;
            if (info != null) {
                SubscriberState state = info.getSubscriberState();
                if (state != null && state.getSubscriberStateChoice() != null) {
                    SubscriberStateChoice choice = state.getSubscriberStateChoice();
                    reachable = choice == SubscriberStateChoice.assumedIdle
                            || choice == SubscriberStateChoice.camelBusy;
                }
                locationPlausible = info.getLocationInformation() != null;
            }
            dialog.release();
            future.complete(VerificationEvidence.ok(reachable, false, locationPlausible, "MAP:PSI"));
        } catch (Exception e) {
            future.complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP"));
        }
    }

    @Override
    public void onSendAuthenticationInfoResponse(SendAuthenticationInfoResponse resp) {
        MAPDialog dialog = resp.getMAPDialog();
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<Boolean> future = saiPending.get(dialogId);
        if (future == null) {
            return;
        }
        try {
            boolean fresh = resp.getAuthenticationSetList() != null
                    || resp.getEpsAuthenticationSetList() != null;
            dialog.release();
            future.complete(fresh);
        } catch (Exception e) {
            future.complete(Boolean.FALSE);
        }
    }

    // ---- MAPDialogListener — fail-closed on any abnormal end -------------

    @Override
    public void onDialogTimeout(MAPDialog dialog) {
        failPending(dialog, FallbackReason.VERIFY_TIMEOUT);
        try {
            dialog.abort(null);
        } catch (Exception ignore) {
            // already ending
        }
    }

    @Override
    public void onDialogReject(MAPDialog dialog, MAPRefuseReason reason,
                               org.restcomm.protocols.ss7.tcap.asn.ApplicationContextName acn,
                               MAPExtensionContainer ext) {
        failPending(dialog, FallbackReason.VERIFY_ERROR);
    }

    @Override
    public void onDialogUserAbort(MAPDialog dialog, MAPUserAbortChoice choice,
                                  MAPExtensionContainer ext) {
        failPending(dialog, FallbackReason.VERIFY_ERROR);
    }

    @Override
    public void onDialogProviderAbort(MAPDialog dialog, MAPAbortProviderReason reason,
                                      MAPAbortSource source, MAPExtensionContainer ext) {
        failPending(dialog, FallbackReason.VERIFY_ERROR);
    }

    private void failPending(MAPDialog dialog, FallbackReason why) {
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<VerificationEvidence> psi = psiPending.remove(dialogId);
        if (psi != null) {
            psi.complete(VerificationEvidence.fail(why, "MAP"));
        }
        CompletableFuture<Boolean> sai = saiPending.remove(dialogId);
        if (sai != null) {
            sai.complete(Boolean.FALSE);
        }
    }

    // ---- MAPServiceListener — component-level fail-closed hooks ---------

    @Override
    public void onMAPMessage(MAPMessage message) {
        // Generic per-message hook; individual on*Response handlers above do the work.
    }

    @Override
    public void onErrorComponent(MAPDialog dialog, Long invokeId, MAPErrorMessage error) {
        // MAP error returned by the HLR/HSS — fail closed.
        LOG.warn("[map-verifier] error component dialogId={} invokeId={} err={}",
                dialog.getLocalDialogId(), invokeId, error);
        failPending(dialog, FallbackReason.VERIFY_ERROR);
    }

    @Override
    public void onRejectComponent(MAPDialog dialog, Long invokeId, Problem problem,
                                  boolean isLocalOriginated) {
        LOG.warn("[map-verifier] reject component dialogId={} invokeId={} problem={}",
                dialog.getLocalDialogId(), invokeId, problem);
        failPending(dialog, FallbackReason.VERIFY_ERROR);
    }

    @Override
    public void onInvokeTimeout(MAPDialog dialog, Long invokeId) {
        // TC invoke timed out inside the dialog — fail closed and abort (no leak).
        LOG.warn("[map-verifier] invoke timeout dialogId={} invokeId={} — aborting",
                dialog.getLocalDialogId(), invokeId);
        failPending(dialog, FallbackReason.VERIFY_TIMEOUT);
        abortDialog(dialog);
    }

    // ---- no-op MAPDialogListener methods --------------------------------

    @Override public void onDialogDelimiter(MAPDialog d) {}
    @Override public void onDialogRequest(MAPDialog d, AddressString dest, AddressString orig,
                                          MAPExtensionContainer ext) {}
    @Override public void onDialogRequestEricsson(MAPDialog d, AddressString dest, AddressString orig,
                                                  AddressString msisdn, AddressString vlrNo) {}
    @Override public void onDialogAccept(MAPDialog d, MAPExtensionContainer ext) {}
    @Override public void onDialogClose(MAPDialog d) {}
    @Override public void onDialogNotice(MAPDialog d, MAPNoticeProblemDiagnostic diag) {}
    @Override public void onDialogRelease(MAPDialog d) {}

    // ---- no-op MAPServiceMobilityListener methods -----------------------

    @Override public void onUpdateLocationRequest(UpdateLocationRequest r) {}
    @Override public void onUpdateLocationResponse(UpdateLocationResponse r) {}
    @Override public void onCancelLocationRequest(CancelLocationRequest r) {}
    @Override public void onCancelLocationResponse(CancelLocationResponse r) {}
    @Override public void onSendIdentificationRequest(SendIdentificationRequest r) {}
    @Override public void onSendIdentificationResponse(SendIdentificationResponse r) {}
    @Override public void onUpdateGprsLocationRequest(UpdateGprsLocationRequest r) {}
    @Override public void onUpdateGprsLocationResponse(UpdateGprsLocationResponse r) {}
    @Override public void onPurgeMSRequest(PurgeMSRequest r) {}
    @Override public void onPurgeMSResponse(PurgeMSResponse r) {}
    @Override public void onSendAuthenticationInfoRequest(SendAuthenticationInfoRequest r) {}
    @Override public void onAuthenticationFailureReportRequest(AuthenticationFailureReportRequest r) {}
    @Override public void onAuthenticationFailureReportResponse(AuthenticationFailureReportResponse r) {}
    @Override public void onResetRequest(ResetRequest r) {}
    @Override public void onForwardCheckSSIndicationRequest(ForwardCheckSSIndicationRequest r) {}
    @Override public void onRestoreDataRequest(RestoreDataRequest r) {}
    @Override public void onRestoreDataResponse(RestoreDataResponse r) {}
    @Override public void onAnyTimeInterrogationRequest(AnyTimeInterrogationRequest r) {
        // FS.11 Cat 1 — never answer / never originate ATI on interconnect.
        LOG.warn("[map-verifier] inbound ATI rejected (FS.11 Cat 1)");
    }
    @Override public void onAnyTimeInterrogationResponse(AnyTimeInterrogationResponse r) {}
    @Override public void onAnyTimeSubscriptionInterrogationRequest(AnyTimeSubscriptionInterrogationRequest r) {}
    @Override public void onAnyTimeSubscriptionInterrogationResponse(AnyTimeSubscriptionInterrogationResponse r) {}
    @Override public void onAnyTimeModificationRequest(AnyTimeModificationRequest r) {}
    @Override public void onAnyTimeModificationResponse(AnyTimeModificationResponse r) {}
    @Override public void onProvideSubscriberInfoRequest(ProvideSubscriberInfoRequest r) {}
    @Override public void onInsertSubscriberDataRequest(InsertSubscriberDataRequest r) {}
    @Override public void onInsertSubscriberDataResponse(InsertSubscriberDataResponse r) {}
    @Override public void onDeleteSubscriberDataRequest(DeleteSubscriberDataRequest r) {}
    @Override public void onDeleteSubscriberDataResponse(DeleteSubscriberDataResponse r) {}
    @Override public void onCheckImeiRequest(CheckImeiRequest r) {}
    @Override public void onCheckImeiResponse(CheckImeiResponse r) {}
    @Override public void onActivateTraceModeRequest_Mobility(ActivateTraceModeRequest_Mobility r) {}
    @Override public void onActivateTraceModeResponse_Mobility(ActivateTraceModeResponse_Mobility r) {}
}
