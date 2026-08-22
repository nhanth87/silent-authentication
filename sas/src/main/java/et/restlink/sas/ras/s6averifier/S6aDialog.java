/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded Diameter-session abstraction for one S6a probe. One session per
 * request stage; both the success ({@link #close(boolean)}) and failure
 * ({@link #abort()}) paths are terminal, so a hung HSS query can never leak a
 * Diameter session (gate H7).
 */
public final class S6aDialog {

    private final String dialogId;
    private final long openedAtMs;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private volatile boolean aborted;

    public S6aDialog(String dialogId, long openedAtMs) {
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