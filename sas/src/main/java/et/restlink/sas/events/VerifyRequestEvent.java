/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

import et.restlink.sas.model.AccessTech;

/**
 * Initial event: a bank backend posts a CAMARA-style {@code /verify} request.
 *
 * <p>Carries the cellular session tuple ({@code srcIp}, {@code srcPort},
 * {@code ts}) — point-in-time, CGNAT-safe — plus an optional claimed MSISDN.
 * When {@code claimedMsisdn} is {@code null} the SAS runs in number-discovery
 * mode (returns the verified MSISDN to the backend only).</p>
 */
@EventType(name = "VerifyRequest", vendor = "et.restlink.sas", version = "1.0")
public final class VerifyRequestEvent implements SleeEvent {

    private final String reqId;
    private final String srcIp;
    private final int srcPort;
    private final long tsEpochMs;
    private final String claimedMsisdn;
    private final AccessTech accessTech;

    public VerifyRequestEvent(String reqId,
                              String srcIp,
                              int srcPort,
                              long tsEpochMs,
                              String claimedMsisdn,
                              AccessTech accessTech) {
        this.reqId = reqId;
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.tsEpochMs = tsEpochMs;
        this.claimedMsisdn = claimedMsisdn;
        this.accessTech = accessTech;
    }

    public String reqId() {
        return reqId;
    }

    public String srcIp() {
        return srcIp;
    }

    public int srcPort() {
        return srcPort;
    }

    public long tsEpochMs() {
        return tsEpochMs;
    }

    public String claimedMsisdn() {
        return claimedMsisdn;
    }

    public AccessTech accessTech() {
        return accessTech;
    }
}