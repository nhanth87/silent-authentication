/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import com.microjainslee.api.RaBootstrapPort;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.ras.s6averifier.command.S6aVerifyCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diameter S6a verifier delegate — one Diameter session per request, abort on
 * timeout, zero leaks. Client-side mirror of the ra-diameter delegate but
 * scoped to the S6a application (TS 29.272).
 */
public final class S6aVerifierResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(S6aVerifierResourceAdaptor.class);

    private RaBootstrapPort bootstrapPort;
    private S6aVerifierBackend backend = new InMemoryS6aVerifierBackend();
    private final Map<String, S6aDialog> dialogs = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    public void setBackend(S6aVerifierBackend backend) {
        this.backend = backend;
    }

    public S6aVerifierBackend backend() {
        return backend;
    }

    public void raConfigure() {
        // no-op for the pilot backend
    }

    public void raActive() {
        active.set(true);
    }

    public void raInactive() {
        active.set(false);
        abortAll();
    }

    public void raUnconfigure() {
        dialogs.clear();
    }

    public boolean isActive() {
        return active.get();
    }

    /** One session per request stage. Timeout ⇒ abort(); never soft-pass. */
    public void verify(S6aVerifyCommand cmd) {
        if (!isActive()) {
            cmd.reply().complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A"));
            return;
        }

        S6aDialog dialog = new S6aDialog(cmd.reqId(), System.currentTimeMillis());
        S6aDialog existing = dialogs.putIfAbsent(cmd.reqId(), dialog);
        final S6aDialog activeDialog = existing != null ? existing : dialog;

        backend.verify(cmd.msisdn(), cmd.imsi(), cmd.accessTech(), System.currentTimeMillis())
                .completeOnTimeout(VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "S6A"),
                        SasTimeouts.DIAMETER_MS, TimeUnit.MILLISECONDS)
                .whenComplete((ev, ex) -> {
                    VerificationEvidence out = ev != null
                            ? ev
                            : VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A");
                    if (out.failed()) {
                        activeDialog.abort();
                    } else {
                        activeDialog.close(false);
                    }
                    dialogs.remove(cmd.reqId(), activeDialog); // no session leak
                    cmd.reply().complete(out);
                });
        LOG.debug("S6a verify reqId={} msisdn={} session={}",
                cmd.reqId(), cmd.msisdn(), activeDialog.dialogId());
    }

    /** Explicit abort from the SBB when its local budget expires first. */
    public void abort(String reqId, String dialogId) {
        S6aDialog d = dialogs.remove(reqId);
        if (d != null) {
            d.abort();
        }
        LOG.warn("S6a session aborted reqId={} dialogId={}", reqId, dialogId);
    }

    private void abortAll() {
        dialogs.values().forEach(S6aDialog::abort);
        dialogs.clear();
    }

    public int openDialogs() {
        return dialogs.size();
    }
}