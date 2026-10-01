/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import com.microjainslee.api.RaBootstrapPort;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.ras.swxverifier.command.SwxVerifyCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SWx verifier delegate — one EAP-AKA session per request, abort on
 * timeout, zero leaks. Clone of the S6a delegate shape, scoped to the
 * SWx application (TS 29.273 / TS 33.402).
 */
public final class SwxVerifierResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SwxVerifierResourceAdaptor.class);

    private RaBootstrapPort bootstrapPort;
    private SwxVerifierBackend backend = new InMemorySwxVerifierBackend();
    private final Map<String, SwxDialog> dialogs = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    public void setBackend(SwxVerifierBackend backend) {
        this.backend = backend;
    }

    public SwxVerifierBackend backend() {
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
    public void verify(SwxVerifyCommand cmd) {
        if (!isActive()) {
            cmd.reply().complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX"));
            return;
        }

        SwxDialog dialog = new SwxDialog(cmd.reqId(), System.currentTimeMillis());
        SwxDialog existing = dialogs.putIfAbsent(cmd.reqId(), dialog);
        final SwxDialog activeDialog = existing != null ? existing : dialog;

        backend.verify(cmd.msisdn(), cmd.imsi(), cmd.accessTech(), System.currentTimeMillis())
                .completeOnTimeout(VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "SWX"),
                        SasTimeouts.DIAMETER_MS, TimeUnit.MILLISECONDS)
                .whenComplete((ev, ex) -> {
                    VerificationEvidence out = ev != null
                            ? ev
                            : VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX");
                    if (out.failed()) {
                        activeDialog.abort();
                    } else {
                        activeDialog.close(false);
                    }
                    dialogs.remove(cmd.reqId(), activeDialog); // no session leak
                    cmd.reply().complete(out);
                });
        LOG.debug("SWx verify reqId={} msisdn={} session={}",
                cmd.reqId(), cmd.msisdn(), activeDialog.dialogId());
    }

    /** Explicit abort from the SBB when its local budget expires first. */
    public void abort(String reqId, String dialogId) {
        SwxDialog d = dialogs.remove(reqId);
        if (d != null) {
            d.abort();
        }
        LOG.warn("SWx session aborted reqId={} dialogId={}", reqId, dialogId);
    }

    private void abortAll() {
        dialogs.values().forEach(SwxDialog::abort);
        dialogs.clear();
    }

    public int openDialogs() {
        return dialogs.size();
    }
}
