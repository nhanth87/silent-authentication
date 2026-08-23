/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

/**
 * Mutable subscriber state driven via the control web UI: attachment and the
 * number of authentication vectors the simulated HLR is willing to serve.
 */
public final class SimState {

    private volatile boolean attached = true;
    private volatile int vectors = 1;

    public boolean attached() {
        return attached;
    }

    public void setAttached(boolean attached) {
        this.attached = attached;
    }

    public int vectors() {
        return vectors;
    }

    public void setVectors(int vectors) {
        this.vectors = Math.max(0, vectors);
    }
}
