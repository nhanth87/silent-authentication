/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.mapverifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded TCAP-dialog abstraction for one MAP probe. One dialog per stage;
 * both the success ({@link #close(boolean)}) and failure ({@link #abort()})
 * paths are terminal, so a hung HLR query can never leak a dialog.
 */
public final class MapDialog {

    private final String dialogId;
    private final long openedAtMs;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private volatile boolean aborted;

    public MapDialog(String dialogId, long openedAtMs) {
        this.dialogId = dialogId;
        this.openedAtMs = openedAtMs;
    }

    public String dialogId() {
        return dialogId;
    }

    public long openedAtMs() {
        return openedAtMs;
    }

    /** Abort the dialog (timeout / error). Idempotent. */
    public void abort() {
        if (ended.compareAndSet(false, true)) {
            aborted = true;
        }
    }

    /** Close normally (prearranged end). Idempotent. */
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