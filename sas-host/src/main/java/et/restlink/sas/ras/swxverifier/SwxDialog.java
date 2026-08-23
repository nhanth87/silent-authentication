/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded SWx-session abstraction for one EAP-AKA probe (TS 29.273).
 * One session per request stage; both success and failure paths are
 * terminal, so a hung AAA/HSS query can never leak a session (gate H7).
 */
public final class SwxDialog {

    private final String dialogId;
    private final long openedAtMs;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private volatile boolean aborted;

    public SwxDialog(String dialogId, long openedAtMs) {
        this.dialogId = dialogId;
        this.openedAtMs = openedAtMs;
    }

    public String dialogId() {
        return dialogId;
    }

    public long openedAtMs() {
        return openedAtMs;
    }

    /** Abort the session (timeout / error). Idempotent. */
    public void abort() {
        if (ended.compareAndSet(false, true)) {
            aborted = true;
        }
    }

    /** Close normally (one request/answer exchange). Idempotent. */
    public void close(boolean prearrangedEnd) {
        ended.set(true);
    }

    public boolean aborted() {
        return aborted;
    }

    public boolean ended() {
        return ended.get();
    }
}
