/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-subscriber state, keyed by IMSI or MSISDN in {@link HssSimulator}.
 *
 * <p>{@code authVectorsAvailable == 0} models an empty vector set: the SAS must
 * fail closed (S6a AIA-empty / SWx MAR-empty). {@code attached == false} models
 * a detached UE; {@code barred == true} models operator-determined barring.</p>
 */
public final class SubscriberState {

    private final String imsi;
    private final String msisdn;
    private final AtomicLong lastEapAuthSuccess = new AtomicLong(0L);

    private volatile boolean attached = true;
    private volatile boolean barred = false;
    private volatile int authVectorsAvailable = 1;
    private volatile String subscribedRat = "EUTRAN";

    public SubscriberState(String imsi, String msisdn) {
        this.imsi = imsi;
        this.msisdn = msisdn;
    }

    public String imsi() {
        return imsi;
    }

    public String msisdn() {
        return msisdn;
    }

    public boolean attached() {
        return attached;
    }

    public void setAttached(boolean attached) {
        this.attached = attached;
    }

    public boolean barred() {
        return barred;
    }

    public void setBarred(boolean barred) {
        this.barred = barred;
    }

    public int authVectorsAvailable() {
        return authVectorsAvailable;
    }

    public void setAuthVectorsAvailable(int authVectorsAvailable) {
        this.authVectorsAvailable = Math.max(0, authVectorsAvailable);
    }

    public String subscribedRat() {
        return subscribedRat;
    }

    public void setSubscribedRat(String subscribedRat) {
        this.subscribedRat = subscribedRat == null || subscribedRat.isBlank()
                ? "EUTRAN" : subscribedRat.trim().toUpperCase();
    }

    public long lastEapAuthSuccess() {
        return lastEapAuthSuccess.get();
    }

    /** Stamp the EAP-AKA success time (called on a non-empty MAA mint). */
    public void markEapAuthSuccess(long epochMs) {
        lastEapAuthSuccess.set(epochMs);
    }

    /** Restore the default lab state: attached, not barred, one vector. */
    public void resetDefaults() {
        attached = true;
        barred = false;
        authVectorsAvailable = 1;
        subscribedRat = "EUTRAN";
        lastEapAuthSuccess.set(0L);
    }
}
