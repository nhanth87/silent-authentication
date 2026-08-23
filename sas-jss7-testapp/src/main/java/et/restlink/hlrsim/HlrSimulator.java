/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7Stack;
import org.restcomm.protocols.ss7.config.Ss7StackBuilder;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.dialog.MAPNoticeProblemDiagnostic;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.CellGlobalIdOrServiceAreaIdOrLAI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer;
import org.restcomm.protocols.ss7.map.api.primitives.NetworkResource;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobilityListener;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationSetList;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationTriplet;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.TripletList;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.LocationInformation;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberInfo;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberState;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberStateChoice;
import org.restcomm.protocols.ss7.tcap.asn.ApplicationContextName;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Simulated home HLR over a jSS7 SERVER-side stack.
 *
 * <p>Answers exactly what the SAS {@code Jss7MapVerifierBackend} asks:</p>
 * <ul>
 *   <li><b>PSI</b> (provideSubscriberInfo v3): ReturnResultLast carrying
 *       subscriberState=assumedIdle + locationInformation (LAI 636-01-100)
 *       while attached; while detached returnError(systemFailure) — TS 29.002
 *       does not list absentSubscriber among PSI errors, so systemFailure is
 *       the clean mapping.</li>
 *   <li><b>SAI</b> (sendAuthenticationInfo v3): N fabricated triplets
 *       (random RAND/SRES/Kc); with vectors=0 returnError(systemFailure).</li>
 *   <li><b>ATI</b> (anyTimeInterrogation): logged then dropped silently —
 *       FS.11 Cat 1 demo, never answered.</li>
 * </ul>
 *
 * <p>Server wiring mirrors the coral-valley map/load harness ss7-server.json:
 * SCTP link type=server listening on the configured port, M3UA AS in IPSP
 * server role (routing context 0, matching sas ss7-sas.json), SCCP point code
 * 2 serving SSN 6 (HLR), bounded TCAP dialog timers like the SAS side.</p>
 */
public final class HlrSimulator implements MAPServiceMobilityListener, MAPDialogListener {

    private static final Logger LOG = LogManager.getLogger(HlrSimulator.class);

    public static final String STACK_NAME = "hlr-sim";
    public static final int LOCAL_PC = 2;
    public static final int REMOTE_PC = 1;
    public static final int HLR_SSN = 6;

    private final String host;
    private final int listenPort;
    private final int peerPort;
    private final SimState state = new SimState();
    private final MessageLog log = new MessageLog();
    private final SecureRandom random = new SecureRandom();

    private volatile Ss7Stack stack;
    private volatile MAPProvider mapProvider;
    private volatile boolean started;

    public HlrSimulator(String host, int listenPort, int peerPort) {
        this.host = host;
        this.listenPort = listenPort;
        this.peerPort = peerPort;
    }

    /** Server-side stack JSON — same document shape as ss7-sas.json / map-load ss7-server.json. */
    public String configJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"stackName\": \"").append(STACK_NAME).append("\",\n");
        b.append("  \"protocols\": { \"map\": true, \"cap\": false },\n");
        b.append("  \"sctp\": {\n");
        b.append("    \"backend\": \"netty_kernel\",\n");
        b.append("    \"connectDelay\": 1000,\n");
        b.append("    \"workerThreads\": 4,\n");
        b.append("    \"links\": [\n");
        b.append("      { \"name\": \"hlrLink\", \"type\": \"server\", \"local\": \"")
                .append(host).append(':').append(listenPort)
                .append("\", \"peer\": \"").append(host).append(':').append(peerPort).append("\" }\n");
        b.append("    ]\n");
        b.append("  },\n");
        b.append("  \"m3ua\": {\n");
        b.append("    \"as\": [\n");
        b.append("      { \"name\": \"HLR-AS\", \"mode\": \"loadshare\", \"functionality\": \"ipsp\", \"ipsp\": \"server\",\n");
        b.append("        \"routingContext\": 0, \"links\": [\"hlrLink\"] }\n");
        b.append("    ],\n");
        b.append("    \"routes\": [\n");
        b.append("      { \"to\": { \"dpc\": ").append(REMOTE_PC).append(", \"opc\": ").append(LOCAL_PC)
                .append(" }, \"via\": \"HLR-AS\" }\n");
        b.append("    ]\n");
        b.append("  },\n");
        b.append("  \"sccp\": {\n");
        b.append("    \"localPoints\": [\n");
        b.append("      { \"pc\": ").append(LOCAL_PC).append(", \"networkIndicator\": \"national\", \"networkId\": 0,\n");
        b.append("        \"reachablePointCodes\": [").append(REMOTE_PC).append("] }\n");
        b.append("    ],\n");
        b.append("    \"routing\": [\n");
        b.append("      { \"from\": \"remote\", \"match\": { \"gt\": \"*\" }, \"to\": { \"pc\": ")
                .append(LOCAL_PC).append(" } },\n");
        b.append("      { \"from\": \"local\",  \"match\": { \"gt\": \"*\" }, \"to\": { \"pc\": ")
                .append(REMOTE_PC).append(" } }\n");
        b.append("    ]\n");
        b.append("  },\n");
        b.append("  \"tcap\": { \"dialogIdleTimeout\": 5000, \"invokeTimeout\": 2500, \"maxDialogs\": 1000 },\n");
        b.append("  \"services\": [ { \"name\": \"hlr\", \"ssn\": ").append(HLR_SSN).append(", \"protocol\": \"map\" } ]\n");
        b.append("}\n");
        return b.toString();
    }

    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        stack = Ss7StackBuilder.buildFromJson(configJson());
        mapProvider = stack.mapProvider();
        if (mapProvider == null) {
            throw new IllegalStateException("jSS7 stack has no MAP provider");
        }
        mapProvider.getMAPServiceMobility().addMAPServiceListener(this);
        // Inbound TC-BEGIN dialogs are delivered only to ACTIVATED services
        // (MAPProviderImpl aborts dialogs for non-activated services).
        mapProvider.getMAPServiceMobility().activate();
        mapProvider.addMAPDialogListener(this);
        started = true;
        LOG.info("[hlr-sim] jSS7 server stack up — SCTP {}:{} (peer {}:{}), PC {}, SSN {}",
                host, listenPort, host, peerPort, LOCAL_PC, HLR_SSN);
    }

    public synchronized void stop() {
        started = false;
        if (stack != null) {
            stack.stop();
            stack = null;
        }
        mapProvider = null;
        LOG.info("[hlr-sim] jSS7 server stack stopped");
    }

    public boolean isStarted() {
        return started;
    }

    /** True once the loopback SCTP association toward the client is ESTABLISHED. */
    public boolean associationConnected() {
        Ss7Stack s = stack;
        if (s == null) {
            return false;
        }
        try {
            var assoc = s.sctpManagement().getAssociation("hlrLink");
            return assoc != null && assoc.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public SimState state() {
        return state;
    }

    public MessageLog log() {
        return log;
    }

    // ---- inbound PSI -------------------------------------------------------

    @Override
    public void onProvideSubscriberInfoRequest(ProvideSubscriberInfoRequest req) {
        MAPDialogMobility dialog = req.getMAPDialog();
        long invokeId = req.getInvokeId();
        try {
            if (!state.attached()) {
                // TS 29.002 PSI error list has no absentSubscriber; systemFailure maps cleanly.
                MAPErrorMessage err = mapProvider.getMAPErrorMessageFactory()
                        .createMAPErrorMessageSystemFailure(3, NetworkResource.hlr, null, null);
                dialog.sendErrorComponent(invokeId, err);
                // close(false) flushes the queued error component inside TC-END;
                // release() would only expunge the dialog locally without sending.
                dialog.close(false);
                log.add(new MessageLog.Entry(Instant.now(), "IN", "provideSubscriberInfo",
                        dialog.getLocalDialogId(), "ERROR systemFailure", "detached"));
                return;
            }
            var pf = mapProvider.getMAPParameterFactory();
            ISDNAddressString vlrNumber = pf.createISDNAddressString(
                    AddressNature.international_number, NumberingPlan.ISDN, "251911000001");
            CellGlobalIdOrServiceAreaIdOrLAI lai = pf.createCellGlobalIdOrServiceAreaIdOrLAI(
                    pf.createLAIFixedLength(636, 1, 100));
            LocationInformation loc = pf.createLocationInformation(1, null, vlrNumber, null,
                    lai, null, null, null, null, false, false, null, null);
            SubscriberState subState = pf.createSubscriberState(SubscriberStateChoice.assumedIdle, null);
            SubscriberInfo info = pf.createSubscriberInfo(loc, subState, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            dialog.addProvideSubscriberInfoResponse(invokeId, info, null);
            dialog.close(false);
            log.add(new MessageLog.Entry(Instant.now(), "OUT", "provideSubscriberInfo",
                    dialog.getLocalDialogId(), "ReturnResultLast", "attached idle+LAI"));
        } catch (MAPException e) {
            LOG.warn("[hlr-sim] failed answering provideSubscriberInfo", e);
        }
    }

    // ---- inbound SAI -------------------------------------------------------

    @Override
    public void onSendAuthenticationInfoRequest(SendAuthenticationInfoRequest req) {
        MAPDialogMobility dialog = req.getMAPDialog();
        long invokeId = req.getInvokeId();
        try {
            int available = state.vectors();
            if (available <= 0) {
                MAPErrorMessage err = mapProvider.getMAPErrorMessageFactory()
                        .createMAPErrorMessageSystemFailure(3, NetworkResource.hlr, null, null);
                dialog.sendErrorComponent(invokeId, err);
                dialog.close(false);
                log.add(new MessageLog.Entry(Instant.now(), "IN", "sendAuthenticationInfo",
                        dialog.getLocalDialogId(), "ERROR systemFailure", "vectors=0"));
                return;
            }
            int requested = Math.max(1, req.getNumberOfRequestedVectors());
            int count = Math.min(requested, available);
            var pf = mapProvider.getMAPParameterFactory();
            ArrayList<AuthenticationTriplet> triplets = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                triplets.add(pf.createAuthenticationTriplet(randomBytes(16), randomBytes(4), randomBytes(8)));
            }
            TripletList list = pf.createTripletList(triplets);
            AuthenticationSetList sets = pf.createAuthenticationSetList(list);
            dialog.addSendAuthenticationInfoResponse(invokeId, sets, null, null, null);
            dialog.close(false);
            log.add(new MessageLog.Entry(Instant.now(), "OUT", "sendAuthenticationInfo",
                    dialog.getLocalDialogId(), "ReturnResultLast", count + " triplet(s)"));
        } catch (MAPException e) {
            LOG.warn("[hlr-sim] failed answering sendAuthenticationInfo", e);
        }
    }

    private byte[] randomBytes(int len) {
        byte[] out = new byte[len];
        random.nextBytes(out);
        return out;
    }

    // ---- inbound ATI — FS.11 Cat 1: logged, NEVER answered ------------------

    @Override
    public void onAnyTimeInterrogationRequest(AnyTimeInterrogationRequest req) {
        MAPDialogMobility dialog = req.getMAPDialog();
        try {
            dialog.processInvokeWithoutAnswer(req.getInvokeId());
        } catch (Exception e) {
            LOG.debug("[hlr-sim] processInvokeWithoutAnswer failed: {}", e.toString());
        }
        LOG.warn("[hlr-sim] inbound ATI dropped silently (FS.11 Cat 1)");
        log.add(new MessageLog.Entry(Instant.now(), "IN", "anyTimeInterrogation",
                dialog.getLocalDialogId(), "DROPPED", "FS.11 Cat 1 — no answer"));
    }

    // ---- MAPServiceListener component hooks ---------------------------------

    @Override
    public void onErrorComponent(MAPDialog dialog, Long invokeId, MAPErrorMessage error) {
        LOG.warn("[hlr-sim] unexpected error component dialog={} invoke={} err={}",
                dialog.getLocalDialogId(), invokeId, error);
    }

    @Override
    public void onRejectComponent(MAPDialog dialog, Long invokeId, Problem problem, boolean local) {
        LOG.warn("[hlr-sim] unexpected reject component dialog={} invoke={} problem={}",
                dialog.getLocalDialogId(), invokeId, problem);
    }

    @Override
    public void onInvokeTimeout(MAPDialog dialog, Long invokeId) {
        LOG.warn("[hlr-sim] invoke timeout dialog={} invoke={}", dialog.getLocalDialogId(), invokeId);
    }

    @Override
    public void onMAPMessage(org.restcomm.protocols.ss7.map.api.MAPMessage message) {
        // generic hook
    }

    // ---- MAPDialogListener — logging only ------------------------------------

    @Override public void onDialogDelimiter(MAPDialog d) {}
    @Override public void onDialogAccept(MAPDialog d, MAPExtensionContainer ext) {}
    @Override public void onDialogClose(MAPDialog d) {}
    @Override public void onDialogNotice(MAPDialog d, MAPNoticeProblemDiagnostic diag) {}

    @Override
    public void onDialogRequest(MAPDialog d, org.restcomm.protocols.ss7.map.api.primitives.AddressString dest,
                                org.restcomm.protocols.ss7.map.api.primitives.AddressString orig,
                                MAPExtensionContainer ext) {
        LOG.info("[hlr-sim] inbound dialog id={} dest={} orig={}", d.getLocalDialogId(), dest, orig);
    }

    @Override
    public void onDialogRequestEricsson(MAPDialog d, org.restcomm.protocols.ss7.map.api.primitives.AddressString dest,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString orig,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString imsi,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString vlrNo) {
        LOG.info("[hlr-sim] inbound Ericsson dialog id={}", d.getLocalDialogId());
    }

    @Override
    public void onDialogReject(MAPDialog d, MAPRefuseReason reason, ApplicationContextName acn,
                               MAPExtensionContainer ext) {
        LOG.warn("[hlr-sim] dialog reject id={} reason={}", d.getLocalDialogId(), reason);
    }

    @Override
    public void onDialogUserAbort(MAPDialog d, MAPUserAbortChoice choice, MAPExtensionContainer ext) {
        LOG.info("[hlr-sim] dialog user abort id={}", d.getLocalDialogId());
    }

    @Override
    public void onDialogProviderAbort(MAPDialog d,
                                      org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason reason,
                                      org.restcomm.protocols.ss7.map.api.dialog.MAPAbortSource source,
                                      MAPExtensionContainer ext) {
        LOG.warn("[hlr-sim] dialog provider abort id={} reason={}", d.getLocalDialogId(), reason);
    }

    @Override
    public void onDialogTimeout(MAPDialog d) {
        LOG.warn("[hlr-sim] dialog timeout id={}", d.getLocalDialogId());
    }

    @Override
    public void onDialogRelease(MAPDialog d) {
        LOG.debug("[hlr-sim] dialog released id={}", d.getLocalDialogId());
    }

    // ---- unused MAPServiceMobilityListener callbacks -------------------------

    @Override public void onUpdateLocationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateLocationRequest r) {}
    @Override public void onUpdateLocationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateLocationResponse r) {}
    @Override public void onCancelLocationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.CancelLocationRequest r) {}
    @Override public void onCancelLocationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.CancelLocationResponse r) {}
    @Override public void onSendIdentificationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationRequest r) {}
    @Override public void onSendIdentificationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationResponse r) {}
    @Override public void onUpdateGprsLocationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationRequest r) {}
    @Override public void onUpdateGprsLocationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationResponse r) {}
    @Override public void onPurgeMSRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSRequest r) {}
    @Override public void onPurgeMSResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSResponse r) {}
    @Override public void onSendAuthenticationInfoResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoResponse r) {}
    @Override public void onAuthenticationFailureReportRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportRequest r) {}
    @Override public void onAuthenticationFailureReportResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportResponse r) {}

    @Override public void onResetRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ResetRequest r) {}
    @Override public void onForwardCheckSSIndicationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ForwardCheckSSIndicationRequest r) {}
    @Override public void onRestoreDataRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataRequest r) {}
    @Override public void onRestoreDataResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataResponse r) {}
    @Override public void onAnyTimeInterrogationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationResponse r) {}
    @Override public void onAnyTimeSubscriptionInterrogationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationRequest r) {}
    @Override public void onAnyTimeSubscriptionInterrogationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationResponse r) {}
    @Override public void onAnyTimeModificationRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationRequest r) {}
    @Override public void onAnyTimeModificationResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationResponse r) {}
    @Override public void onProvideSubscriberInfoResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoResponse r) {}
    @Override public void onInsertSubscriberDataRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.InsertSubscriberDataRequest r) {}
    @Override public void onInsertSubscriberDataResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.InsertSubscriberDataResponse r) {}
    @Override public void onDeleteSubscriberDataRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.DeleteSubscriberDataRequest r) {}
    @Override public void onDeleteSubscriberDataResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.DeleteSubscriberDataResponse r) {}
    @Override public void onCheckImeiRequest(
            org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiRequest r) {}
    @Override public void onCheckImeiResponse(
            org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiResponse r) {}
    @Override public void onActivateTraceModeRequest_Mobility(
            org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeRequest_Mobility r) {}
    @Override public void onActivateTraceModeResponse_Mobility(
            org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeResponse_Mobility r) {}
}
