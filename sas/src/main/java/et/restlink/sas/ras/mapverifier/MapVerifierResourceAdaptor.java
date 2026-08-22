/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import com.microjainslee.api.RaBootstrapPort;

import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MAP Verifier delegate — one TCAP dialog per request, abort on timeout, zero
 * leaks. Client-side mirror of the ra-diameter delegate.
 */
public final class MapVerifierResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(MapVerifierResourceAdaptor.class);

    private RaBootstrapPort bootstrapPort;
    private MapVerifierBackend backend = new InMemoryMapVerifierBackend();
    private final Map<String, MapDialog> dialogs = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    public void setBackend(MapVerifierBackend backend) {
        this.backend = backend;
    }

    public MapVerifierBackend backend() {
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

    /** One dialog per request stage. Timeout ⇒ abort(); never soft-pass. */
    public void verify(MapVerifyCommand cmd) {
        if (!isActive()) {
            cmd.reply().complete(VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP"));
            return;
        }

        MapDialog dialog = new MapDialog(cmd.reqId(), System.currentTimeMillis());
        MapDialog existing = dialogs.putIfAbsent(cmd.reqId(), dialog);
        final MapDialog activeDialog = existing != null ? existing : dialog;

        backend.verify(cmd.msisdn(), cmd.imsi(), cmd.accessTech(), System.currentTimeMillis())
                .completeOnTimeout(VerificationEvidence.fail(FallbackReason.VERIFY_TIMEOUT, "MAP"),
                        SasTimeouts.MAP_MS, TimeUnit.MILLISECONDS)
                .whenComplete((ev, ex) -> {
                    VerificationEvidence out = ev != null
                            ? ev
                            : VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "MAP");
                    if (out.failed()) {
                        activeDialog.abort();
                    } else {
                        activeDialog.close(false);
                    }
                    dialogs.remove(cmd.reqId(), activeDialog); // no dialog leak
                    cmd.reply().complete(out);
                });
        LOG.debug("MAP verify reqId={} msisdn={} dialog={}",
                cmd.reqId(), cmd.msisdn(), activeDialog.dialogId());
    }

    /** Explicit abort from the SBB when its local budget expires first. */
    public void abort(String reqId, String dialogId) {
        MapDialog d = dialogs.remove(reqId);
        if (d != null) {
            d.abort();
        }
        LOG.warn("MAP dialog aborted reqId={} dialogId={}", reqId, dialogId);
    }

    private void abortAll() {
        dialogs.values().forEach(MapDialog::abort);
        dialogs.clear();
    }

    public int openDialogs() {
        return dialogs.size();
    }
}