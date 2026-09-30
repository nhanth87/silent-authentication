/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.binding.backend;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.ras.binding.SubscriberBinding;
import et.restlink.sas.ras.binding.SubscriberBindingBackend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.dialog.MAPNoticeProblemDiagnostic;
import org.restcomm.protocols.ss7.map.api.dialog.MAPRefuseReason;
import org.restcomm.protocols.ss7.map.api.dialog.MAPUserAbortChoice;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobilityListener;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoResponse;
import org.restcomm.protocols.ss7.map.api.service.oam.MAPDialogOam;
import org.restcomm.protocols.ss7.map.api.service.oam.MAPServiceOamListener;
import org.restcomm.protocols.ss7.map.api.service.oam.SendImsiRequest;
import org.restcomm.protocols.ss7.map.api.service.oam.SendImsiResponse;

/**
 * IMSI → MSISDN by the <b>reverse</b> direction: ask the operator HLR which IMSI owns
 * a number, via MAP {@code SendIMSI} (TS 29.002, {@code service.oam}).
 *
 * <p>Why this op and not the obvious alternatives:</p>
 * <ul>
 *   <li><b>Not SRI-SM.</b> With SMS Home Routing enabled (Strategy B) SRI-SM answers
 *       with a correlation ID or a routing proxy, not a number — binding on it produces a
 *       spurious match. This is asserted in {@link SubscriberBindingBackend}.</li>
 *   <li><b>Not ATI.</b> FS.11 Category 1: never interrogate, and never on interconnect.</li>
 *   <li>SendIMSI is a straight number → IMSI lookup against the <b>own</b> HLR, which is
 *       exactly the question "is this claimed number the SIM that just authenticated?"</li>
 * </ul>
 *
 * <p>Fail-closed: an error, a timeout, a released dialog or a number the HLR does not
 * know all produce an <b>unresolved</b> binding, never a guess.</p>
 */
public final class MapSendImsiBinding
        implements SubscriberBindingBackend, MAPServiceOamListener, MAPDialogListener {

    private static final Logger LOG = LogManager.getLogger(MapSendImsiBinding.class);

    /**
     * SSN 3 = OAM (network and management). TS 29.002 puts sendImsi here, not on
     * the HLR's SSN 6 — sending it on 6 is a routing failure.
     */
    private static final int OAM_SSN = 3;

    private final org.restcomm.protocols.ss7.map.api.MAPProvider provider;
    private final String hlrGt;
    private final String localGt;

    private final Map<Long, CompletableFuture<SubscriberBinding>> pending = new ConcurrentHashMap<>();
    private final Map<Long, Claim> claims = new ConcurrentHashMap<>();

    private volatile MAPProvider mapProvider;
    private volatile boolean started;

    /**
     * @param provider the MAP provider of the stack this process already runs. It is
     *                 <b>shared</b>, never built here: two jSS7 stacks in one host
     *                 collide on the same SCTP local port, and the loser never gets an
     *                 association — a failure that only shows up later as
     *                 "No AS found for routing message" on the one op it lost.
     */
    public MapSendImsiBinding(org.restcomm.protocols.ss7.map.api.MAPProvider provider,
                              String hlrGt, String localGt) {
        this.provider = provider;
        this.hlrGt = hlrGt;
        this.localGt = localGt;
    }

    /**
     * Activate the OAM service on the shared stack. The stack itself belongs to
     * {@code Jss7MapVerifierBackend}, so this class never stops it — it only stops
     * using it.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        mapProvider = provider;
        if (mapProvider == null) {
            LOG.error("[binding] no shared MAP provider — SendIMSI stays unresolved");
            started = false;
            return;
        }
        try {
            mapProvider.getMAPServiceOam().addMAPServiceListener(this);
            mapProvider.addMAPDialogListener(this);
            // Explicit service activation — see Jss7MapVerifierBackend.start() for why
            // this fork needs it before any dialog can be created.
            mapProvider.getMAPServiceOam().activate();
            started = true;
            LOG.info("[binding] SendIMSI ready on the shared jSS7 stack — HLR GT={}",
                    hlrGt);
        } catch (RuntimeException e) {
            LOG.error("[binding] SendIMSI could not start — unresolved", e);
            started = false;
        }
    }

    @Override
    public synchronized void stop() {
        started = false;
        pending.values().forEach(f ->
                f.complete(SubscriberBinding.unresolved(null, name() + "-stopped")));
        pending.clear();
        claims.clear();
        // The stack belongs to the MAP verifier: release only this service.
        if (mapProvider != null) {
            try {
                mapProvider.getMAPServiceOam().deactivate();
            } catch (RuntimeException e) {
                LOG.debug("[binding] OAM deactivate failed", e);
            }
        }
        mapProvider = null;
        LOG.info("[binding] SendIMSI released (shared stack left running)");
    }

    @Override
    public String name() {
        return "map-smi";
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * SendIMSI is number-driven, so it cannot discover a number from an IMSI. The
     * honest answer is "unresolved" and the RA moves on to the next source.
     */
    @Override
    public CompletableFuture<SubscriberBinding> lookup(String imsi) {
        return CompletableFuture.completedFuture(
                SubscriberBinding.unresolved(imsi, name() + "-number-driven"));
    }

    @Override
    public boolean supportsClaimVerification() {
        return true;
    }

    /**
     * The question this source exists to answer: does the HLR agree that
     * {@code claimedMsisdn} belongs to {@code provedImsi}?
     */
    @Override
    public CompletableFuture<SubscriberBinding> verifyClaim(String provedImsi,
                                                            String claimedMsisdn) {
        return lookupMsisdn(provedImsi, claimedMsisdn);
    }

    /**
     * Ask the HLR which IMSI owns {@code msisdn} and compare it with the one EAP proved.
     *
     * @return a resolved binding only when the HLR confirms the same IMSI; otherwise
     *         unresolved, which the RA turns into a refusal.
     */
    public CompletableFuture<SubscriberBinding> lookupMsisdn(String provedImsi, String msisdn) {
        Claim claim = new Claim(provedImsi, msisdn);
        if (!started || mapProvider == null) {
            return CompletableFuture.completedFuture(
                    SubscriberBinding.unresolved(provedImsi, name() + "-unavailable"));
        }
        if (msisdn == null || msisdn.isBlank()) {
            // Nothing to ask about: this source can only answer "unresolved" and the RA
            // moves on to the next one.
            return CompletableFuture.completedFuture(
                    SubscriberBinding.unresolved(provedImsi, name() + "-no-claim"));
        }
        CompletableFuture<SubscriberBinding> out = new CompletableFuture<>();
        MAPDialogOam dialog = null;
        try {
            // SendIMSI rides the IMSI-retrieval application context, and that context
            // exists in AC version 2 only (jSS7 9.2.8: version 3 has no instance).
            // Asking for version 3 returns null, which surfaces much later as an NPE
            // inside addSendImsiRequest — so the null is caught here, at the source.
            MAPApplicationContext ctx = MAPApplicationContext.getInstance(
                    MAPApplicationContextName.imsiRetrievalContext,
                    MAPApplicationContextVersion.version2);
            if (ctx == null) {
                throw new MAPException("no MAP application context for IMSI retrieval");
            }
            dialog = mapProvider.getMAPServiceOam()
                    .createNewDialog(ctx, gtAddress(localGt), null, gtAddress(hlrGt), null);
            long dialogId = dialog.getLocalDialogId();
            pending.put(dialogId, out);
            claims.put(dialogId, claim);
            ISDNAddressString number = mapProvider.getMAPParameterFactory()
                    .createISDNAddressString(AddressNature.international_number,
                            NumberingPlan.ISDN, normaliseMsisdn(msisdn));
            dialog.addSendImsiRequest(number);
            dialog.send();
            LOG.info("[binding] SendIMSI sent msisdn={}", msisdn);
        } catch (MAPException e) {
            LOG.warn("[binding] SendIMSI send failed", e);
            if (dialog != null) {
                pending.remove(dialog.getLocalDialogId());
                claims.remove(dialog.getLocalDialogId());
            }
            out.complete(SubscriberBinding.unresolved(provedImsi, name() + "-error"));
            return out;
        }
        out.orTimeout(SasTimeouts.MAP_MS, TimeUnit.MILLISECONDS)
                .exceptionally(error -> {
                    LOG.warn("[binding] SendIMSI did not complete — unresolved", error);
                    return SubscriberBinding.unresolved(provedImsi, name() + "-failed");
                });
        return out;
    }

    @Override
    public void onSendImsiResponse(SendImsiResponse response) {
        long dialogId = dialogId(response.getMAPDialog());
        CompletableFuture<SubscriberBinding> future = pending.remove(dialogId);
        Claim claim = claims.remove(dialogId);
        if (future == null || claim == null) {
            LOG.warn("[binding] SendIMSI answer for unknown dialog {} — dropped", dialogId);
            return;
        }
        String imsi = response.getImsi() == null ? null : response.getImsi().getData();
        if (imsi == null || imsi.isBlank()) {
            future.complete(SubscriberBinding.unresolved(claim.provedImsi(), name() + "-empty"));
            return;
        }
        // The number is resolved only because the HLR confirmed it belongs to the IMSI
        // the EAP exchange proved. A disagreement is unresolved, not a near-miss.
        if (claim.provedImsi() != null && !claim.provedImsi().equals(imsi)) {
            LOG.warn("[binding] SendIMSI says the claimed number belongs to a different SIM "
                    + "({} vs proved {}) — refusing", mask(imsi), mask(claim.provedImsi()));
            future.complete(SubscriberBinding.unresolved(claim.provedImsi(), "mismatch"));
            return;
        }
        future.complete(SubscriberBinding.resolved(claim.provedImsi() == null ? imsi : claim.provedImsi(),
                claim.claimedMsisdn(), name()));
        LOG.info("[binding] SendIMSI confirmed msisdn={} imsi={}",
                claim.claimedMsisdn(), mask(imsi));
    }

    private record Claim(String provedImsi, String claimedMsisdn) {}

    // ---- dialog lifecycle (MAPDialogListener names) ------------------------
    //
    // Every terminal event releases the pending future, so a dialog that dies in an
    // unexpected way can never leave a caller waiting on the RA's 2 s budget.

    @Override
    public void onDialogRelease(MAPDialog dialog) {
        release(dialog, "released");
    }

    @Override
    public void onDialogNotice(MAPDialog dialog, MAPNoticeProblemDiagnostic diag) {
        release(dialog, "notice: " + diag);
    }

    @Override
    public void onDialogReject(MAPDialog dialog, MAPRefuseReason reason,
                               org.restcomm.protocols.ss7.tcap.asn.ApplicationContextName acn,
                               org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer ext) {
        release(dialog, "rejected: " + reason);
    }

    @Override
    public void onDialogUserAbort(MAPDialog dialog, MAPUserAbortChoice choice,
                                  org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer ext) {
        release(dialog, "user abort");
    }

    @Override
    public void onDialogProviderAbort(MAPDialog dialog,
                                      org.restcomm.protocols.ss7.map.api.dialog.MAPAbortProviderReason reason,
                                      org.restcomm.protocols.ss7.map.api.dialog.MAPAbortSource source,
                                      org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer ext) {
        release(dialog, "provider abort");
    }

    @Override
    public void onDialogTimeout(MAPDialog dialog) {
        release(dialog, "timeout");
    }

    @Override
    public void onDialogDelimiter(MAPDialog dialog) {
        // segmentation boundary; nothing to do
    }

    @Override
    public void onDialogRequest(MAPDialog dialog,
                                org.restcomm.protocols.ss7.map.api.primitives.AddressString dest,
                                org.restcomm.protocols.ss7.map.api.primitives.AddressString orig,
                                org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer ext) {
        // this node is a client for SendIMSI
    }

    @Override
    public void onDialogRequestEricsson(MAPDialog dialog,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString dest,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString orig,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString target,
                                        org.restcomm.protocols.ss7.map.api.primitives.AddressString origAddr) {
        // this node is a client for SendIMSI
    }

    @Override
    public void onDialogAccept(MAPDialog dialog,
                               org.restcomm.protocols.ss7.map.api.primitives.MAPExtensionContainer ext) {
        // accepted: the answer arrives through onSendImsiResponse
    }

    @Override
    public void onDialogClose(MAPDialog dialog) {
        // closed after the answer
    }

    private void release(MAPDialog dialog, String why) {
        long dialogId = dialogId(dialog);
        CompletableFuture<SubscriberBinding> future = pending.remove(dialogId);
        claims.remove(dialogId);
        if (future != null) {
            future.completeExceptionally(new IllegalStateException("MAP dialog " + why));
        }
    }

    // ---- MAPServiceListener: the four protocol-level callbacks ---------------
    //
    // Each of them is a way the dialog can die, and each must release the pending
    // future — otherwise a caller would sit on the RA budget instead of getting a
    // refusal immediately.

    @Override
    public void onMAPMessage(org.restcomm.protocols.ss7.map.api.MAPMessage message) {
        // typed callbacks handle the messages this backend cares about
    }

    @Override
    public void onErrorComponent(org.restcomm.protocols.ss7.map.api.MAPDialog dialog,
                                 Long invokeId,
                                 org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage error) {
        release(dialog, "error component: " + error);
    }

    @Override
    public void onRejectComponent(org.restcomm.protocols.ss7.map.api.MAPDialog dialog, Long invokeId,
                                  org.restcomm.protocols.ss7.tcap.asn.comp.Problem problem,
                                  boolean local) {
        release(dialog, "reject component: " + problem);
    }

    @Override
    public void onInvokeTimeout(org.restcomm.protocols.ss7.map.api.MAPDialog dialog, Long invokeId) {
        release(dialog, "invoke timeout");
        try {
            dialog.abort(null);
        } catch (org.restcomm.protocols.ss7.map.api.MAPException e) {
            LOG.debug("[binding] abort after invoke timeout failed", e);
        }
    }

    @Override
    public void onSendImsiRequest(SendImsiRequest req) {
        // this node is a client for SendIMSI; a request is unexpected
        LOG.warn("[binding] inbound SendIMSI request — ignored (client-side node)");
    }

    // ---- unused mobility callbacks (interface completeness) ----------------

    @Override
    public void onActivateTraceModeRequest_Oam(
            org.restcomm.protocols.ss7.map.api.service.oam.ActivateTraceModeRequest_Oam req) {
        // not simulated
    }

    @Override
    public void onActivateTraceModeResponse_Oam(
            org.restcomm.protocols.ss7.map.api.service.oam.ActivateTraceModeResponse_Oam r) {
        // client side
    }

    private static long dialogId(MAPDialog dialog) {
        Long id = dialog == null ? null : dialog.getLocalDialogId();
        return id == null ? -1L : id;
    }

    /** MAP carries a bare national number here, so drop a leading '+'. */
    static String normaliseMsisdn(String msisdn) {
        String value = msisdn.trim();
        return value.startsWith("+") ? value.substring(1) : value;
    }

    /**
     * GT endpoint for the dialog. The SSN is the OAM one (3): sendImsi rides the
     * network-management service selector, not the HLR's (6).
     */
    private org.restcomm.protocols.ss7.sccp.parameter.SccpAddress gtAddress(String digits) {
        return et.restlink.sas.ras.mapverifier.Jss7MapVerifierBackend.gtAddress(digits, OAM_SSN);
    }

    private static String mask(String imsi) {
        if (imsi == null || imsi.length() < 6) {
            return "***";
        }
        return imsi.substring(0, 3) + "****" + imsi.substring(imsi.length() - 2);
    }
}
