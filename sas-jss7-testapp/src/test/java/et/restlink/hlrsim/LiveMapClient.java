/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7Stack;
import org.restcomm.protocols.ss7.config.Ss7StackBuilder;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.dialog.MAPNoticeProblemDiagnostic;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobilityListener;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationSetList;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoResponse;
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
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationResponse;
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
import org.restcomm.protocols.ss7.tcap.asn.ApplicationContextName;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

/**
 * Minimal replica of the SAS {@code Jss7MapVerifierBackend} dialog code path:
 * builds a jSS7 CLIENT stack (same shape as sas ss7-sas.json) and drives one
 * TCAP dialog per stage — PSI v3, SAI v3 and ATI — against the simulated HLR.
 */
public final class LiveMapClient implements MAPServiceMobilityListener, MAPDialogListener {

    private static final Logger LOG = LogManager.getLogger(LiveMapClient.class);

    private static final RoutingIndicator ROUTING = RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE;
    private static final int HLR_SSN = 6;

    public static final String DEFAULT_HLR_GT = "251911000000";
    public static final String DEFAULT_LOCAL_GT = "251911999999";

    /** PSI outcome as the SAS FSM would see it. */
    public record PsiResult(boolean ok, boolean reachable, boolean locationPlausible, String errorText) {
    }

    private final String host;
    private final int localPort;
    private final int hlrPort;

    private volatile Ss7Stack stack;
    private volatile MAPProvider mapProvider;

    private final Map<Long, CompletableFuture<PsiResult>> psiPending = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<SendAuthenticationInfoResponse>> saiPending =
            new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Boolean>> atiAnswered = new ConcurrentHashMap<>();

    public LiveMapClient(String host, int localPort, int hlrPort) {
        this.host = host;
        this.localPort = localPort;
        this.hlrPort = hlrPort;
    }

    /** Client-side stack JSON — mirrors sas/src/main/resources/ss7-sas.json. */
    public String configJson() {
        return "{\n"
                + "  \"stackName\": \"sas-live-client\",\n"
                + "  \"protocols\": { \"map\": true, \"cap\": false },\n"
                + "  \"sctp\": {\n"
                + "    \"backend\": \"netty_kernel\",\n"
                + "    \"connectDelay\": 300,\n"
                + "    \"workerThreads\": 4,\n"
                + "    \"links\": [\n"
                + "      { \"name\": \"SAS-STP-A\", \"local\": \"" + host + ":" + localPort
                + "\", \"peer\": \"" + host + ":" + hlrPort + "\", \"aspId\": 1 }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"m3ua\": {\n"
                + "    \"as\": [\n"
                + "      { \"name\": \"SAS-AS\", \"mode\": \"loadshare\", \"functionality\": \"as\", \"ipsp\": \"client\",\n"
                + "        \"routingContext\": 0, \"links\": [\"SAS-STP-A\"] }\n"
                + "    ],\n"
                + "    \"routes\": [\n"
                + "      { \"to\": { \"dpc\": 2, \"opc\": 1 }, \"via\": \"SAS-AS\" }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"sccp\": {\n"
                + "    \"localPoints\": [\n"
                + "      { \"pc\": 1, \"networkIndicator\": \"national\", \"networkId\": 0,\n"
                + "        \"reachablePointCodes\": [2] }\n"
                + "    ],\n"
                + "    \"routing\": [\n"
                + "      { \"from\": \"local\", \"match\": { \"gt\": \"*\" }, \"to\": { \"pc\": 2, \"ssn\": "
                + HLR_SSN + " }, \"mask\": \"K\" },\n"
                + "      { \"from\": \"remote\", \"match\": { \"gt\": \"*\" }, \"to\": { \"pc\": 1, \"ssn\": "
                + HLR_SSN + " } }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"tcap\": { \"dialogIdleTimeout\": 5000, \"invokeTimeout\": 2500, \"maxDialogs\": 1000 },\n"
                + "  \"services\": [ { \"name\": \"hlr\", \"ssn\": " + HLR_SSN + ", \"protocol\": \"map\" } ]\n"
                + "}\n";
    }

    public void start() throws Exception {
        stack = Ss7StackBuilder.buildFromJson(configJson());
        mapProvider = stack.mapProvider();
        if (mapProvider == null) {
            throw new IllegalStateException("jSS7 stack has no MAP provider");
        }
        mapProvider.getMAPServiceMobility().addMAPServiceListener(this);
        // createNewDialog refuses to run on a non-activated service.
        mapProvider.getMAPServiceMobility().activate();
        mapProvider.addMAPDialogListener(this);
        LOG.info("[live-client] jSS7 client stack up — dialing {}:{} from {}", hlrPort, hlrPort, localPort);
    }

    public void stop() {
        if (stack != null) {
            stack.stop();
            stack = null;
        }
    }

    /** True once the loopback SCTP association toward the simulator is ESTABLISHED. */
    public boolean associationConnected() {
        Ss7Stack s = stack;
        if (s == null) {
            return false;
        }
        try {
            var assoc = s.sctpManagement().getAssociation("SAS-STP-A");
            return assoc != null && assoc.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- dialogs (replicated from Jss7MapVerifierBackend.runPsi/runSai) ------

    public CompletableFuture<PsiResult> runPsi(String imsi) throws Exception {
        MAPDialogMobility dialog = openDialog(MAPApplicationContextName.subscriberInfoEnquiryContext);
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<PsiResult> future = new CompletableFuture<>();
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
            return future;
        } catch (Exception e) {
            psiPending.remove(dialogId);
            abortDialog(dialog);
            throw e;
        }
    }

    public CompletableFuture<SendAuthenticationInfoResponse> runSai(String imsi) throws Exception {
        MAPDialogMobility dialog = openDialog(MAPApplicationContextName.infoRetrievalContext);
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<SendAuthenticationInfoResponse> future = new CompletableFuture<>();
        saiPending.put(dialogId, future);
        try {
            IMSI imsiParam = mapProvider.getMAPParameterFactory().createIMSI(imsi);
            dialog.addSendAuthenticationInfoRequest(imsiParam, 1, false, true, null, null,
                    null, null, null, false, false);
            dialog.send();
            return future;
        } catch (Exception e) {
            saiPending.remove(dialogId);
            abortDialog(dialog);
            throw e;
        }
    }

    /** Handle for the negative-path ATI probe: answer future + live dialog. */
    public record AtiHandle(CompletableFuture<Boolean> answered, MAPDialogMobility dialog) {
    }

    /** ATI is sent ONLY by this negative test to prove the sim never answers it. */
    public AtiHandle runAti(String imsi) throws Exception {
        MAPDialogMobility dialog = openDialog(MAPApplicationContextName.anyTimeEnquiryContext);
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        atiAnswered.put(dialogId, future);
        try {
            var pf = mapProvider.getMAPParameterFactory();
            IMSI imsiParam = pf.createIMSI(imsi);
            RequestedInfo requestedInfo = pf.createRequestedInfo(
                    true, true, null, false, DomainType.csDomain, false, false, false, false);
            ISDNAddressString gsmScf = pf.createISDNAddressString(
                    org.restcomm.protocols.ss7.map.api.primitives.AddressNature.international_number,
                    org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan.ISDN, DEFAULT_LOCAL_GT);
            dialog.addAnyTimeInterrogationRequest(pf.createSubscriberIdentity(imsiParam),
                    requestedInfo, gsmScf, null);
            dialog.send();
            return new AtiHandle(future, dialog);
        } catch (Exception e) {
            atiAnswered.remove(dialogId);
            abortDialog(dialog);
            throw e;
        }
    }

    private MAPDialogMobility openDialog(MAPApplicationContextName ctxName) throws MAPException {
        MAPApplicationContext ctx = MAPApplicationContext.getInstance(ctxName,
                MAPApplicationContextVersion.version3);
        SccpAddress localAddr = gtAddress(DEFAULT_LOCAL_GT);
        SccpAddress hlrAddr = gtAddress(DEFAULT_HLR_GT);
        return mapProvider.getMAPServiceMobility()
                .createNewDialog(ctx, localAddr, null, hlrAddr, null);
    }

    private SccpAddress gtAddress(String digits) {
        ParameterFactoryImpl pf = new ParameterFactoryImpl();
        GlobalTitle gt = pf.createGlobalTitle(digits, 0, NumberingPlan.ISDN_TELEPHONY,
                BCDEvenEncodingScheme.INSTANCE, NatureOfAddress.INTERNATIONAL);
        return pf.createSccpAddress(ROUTING, gt, 0, HLR_SSN);
    }

    /** Abort a dialog with an encodable user-abort choice (no dialog leaks). */
    public void abortDialog(MAPDialog dialog) {
        try {
            // A bare null choice fails to encode ("UserSpecificReason must not be null").
            dialog.abort(new org.restcomm.protocols.ss7.map.dialog.MAPUserAbortChoiceImpl() {{
                setUserSpecificReason();
            }});
        } catch (Exception e) {
            LOG.debug("[live-client] abort failed: {}", e.toString());
        }
    }

    // ---- MAPServiceMobilityListener — responses -------------------------------

    @Override
    public void onProvideSubscriberInfoResponse(ProvideSubscriberInfoResponse resp) {
        MAPDialog dialog = resp.getMAPDialog();
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<PsiResult> future = psiPending.remove(dialogId);
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
            future.complete(new PsiResult(true, reachable, locationPlausible, null));
        } catch (Exception e) {
            future.complete(new PsiResult(false, false, false, e.toString()));
        }
    }

    @Override
    public void onSendAuthenticationInfoResponse(SendAuthenticationInfoResponse resp) {
        MAPDialog dialog = resp.getMAPDialog();
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<SendAuthenticationInfoResponse> future = saiPending.remove(dialogId);
        if (future == null) {
            return;
        }
        try {
            dialog.release();
        } catch (Exception ignore) {
            // completing regardless
        }
        future.complete(resp);
    }

    @Override
    public void onAnyTimeInterrogationRequest(AnyTimeInterrogationRequest req) {
        // The SAS verifier never receives ATI requests; log if one ever arrives.
        LOG.warn("[live-client] unexpected inbound ATI request");
    }

    @Override
    public void onAnyTimeInterrogationResponse(AnyTimeInterrogationResponse resp) {
        MAPDialog dialog = resp.getMAPDialog();
        CompletableFuture<Boolean> future = atiAnswered.remove(dialog.getLocalDialogId());
        LOG.warn("[live-client] UNEXPECTED ATI answer — FS.11 regression in sim?");
        if (future != null) {
            future.complete(Boolean.TRUE);
        }
        try {
            dialog.release();
        } catch (Exception ignore) {
            // already ending
        }
    }

    // ---- MAPServiceListener — fail-closed component hooks ----------------------

    @Override
    public void onErrorComponent(MAPDialog dialog, Long invokeId, MAPErrorMessage error) {
        LOG.info("[live-client] error component dialog={} invoke={} err={}",
                dialog.getLocalDialogId(), invokeId, error);
        failPending(dialog, "error:" + error);
    }

    @Override
    public void onRejectComponent(MAPDialog dialog, Long invokeId, Problem problem, boolean local) {
        LOG.warn("[live-client] reject component dialog={} invoke={} problem={}",
                dialog.getLocalDialogId(), invokeId, problem);
        failPending(dialog, "reject:" + problem);
    }

    @Override
    public void onInvokeTimeout(MAPDialog dialog, Long invokeId) {
        LOG.warn("[live-client] invoke timeout dialog={} invoke={}", dialog.getLocalDialogId(), invokeId);
        failPending(dialog, "invokeTimeout");
        abortDialog(dialog);
    }

    @Override
    public void onMAPMessage(MAPMessage message) {
        // generic hook
    }

    private void failPending(MAPDialog dialog, String why) {
        Long dialogId = dialog.getLocalDialogId();
        CompletableFuture<PsiResult> psi = psiPending.remove(dialogId);
        if (psi != null) {
            psi.complete(new PsiResult(false, false, false, why));
        }
        CompletableFuture<SendAuthenticationInfoResponse> sai = saiPending.remove(dialogId);
        if (sai != null) {
            sai.cancel(false);
            sai.completeExceptionally(new MAPException("SAI failed: " + why));
        }
    }

    // ---- MAPDialogListener — fail-closed on abnormal end ------------------------

    @Override
    public void onDialogTimeout(MAPDialog dialog) {
        failPending(dialog, "dialogTimeout");
        try {
            dialog.abort(null);
        } catch (Exception ignore) {
            // already ending
        }
    }

    @Override
    public void onDialogReject(MAPDialog dialog, MAPRefuseReason reason, ApplicationContextName acn,
                               MAPExtensionContainer ext) {
        failPending(dialog, "dialogReject:" + reason);
    }

    @Override
    public void onDialogUserAbort(MAPDialog dialog, MAPUserAbortChoice choice, MAPExtensionContainer ext) {
        failPending(dialog, "userAbort");
    }

    @Override
    public void onDialogProviderAbort(MAPDialog dialog,
                                      org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason reason,
                                      org.restcomm.protocols.ss7.map.api.dialog.MAPAbortSource source,
                                      MAPExtensionContainer ext) {
        failPending(dialog, "providerAbort");
    }

    // ---- remaining no-ops -------------------------------------------------------

    @Override public void onDialogDelimiter(MAPDialog d) {}
    @Override public void onDialogRequest(MAPDialog d, AddressString dest, AddressString orig,
                                          MAPExtensionContainer ext) {}
    @Override public void onDialogRequestEricsson(MAPDialog d, AddressString dest, AddressString orig,
                                                  AddressString imsi, AddressString vlrNo) {}
    @Override public void onDialogAccept(MAPDialog d, MAPExtensionContainer ext) {}
    @Override public void onDialogClose(MAPDialog d) {}
    @Override public void onDialogNotice(MAPDialog d, MAPNoticeProblemDiagnostic diag) {}
    @Override public void onDialogRelease(MAPDialog d) {}

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
