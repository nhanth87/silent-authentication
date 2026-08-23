/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.model.AccessTech;

/**
 * Initial event: a bank backend posts a CAMARA-style {@code /verify} request.
 *
 * <p>Carries the cellular session tuple ({@code srcIp}, {@code srcPort},
 * {@code ts}) — point-in-time, CGNAT-safe — plus an optional claimed MSISDN.
 * When {@code claimedMsisdn} is {@code null} the SAS runs in number-discovery
 * mode (returns the verified MSISDN to the backend only).</p>
 *
 * <p>{@code claimedImsi} is optional and nullable: on the Wi-Fi TS.43 path it
 * is the IMSI bound to the operator entitlement token. When present, the SWx
 * verifier treats an IMSI mismatch as SIM-swap suspect (fail-closed); when
 * absent the backend falls back to its own HSS record.</p>
 *
 * <p>{@code riskClass} is optional; {@code null} means LOGIN (the /verify flow
 * is itself a login) and is normalised fail-safe downstream.</p>
 */
@EventType(name = "VerifyRequest", vendor = "et.restlink.sas", version = "1.0")
public final class VerifyRequestEvent implements SleeEvent {

    private final String reqId;
    private final String srcIp;
    private final int srcPort;
    private final long tsEpochMs;
    private final String claimedMsisdn;
    private final String claimedImsi;
    private final AccessTech accessTech;
    private final AssurancePolicy.RiskClass riskClass;
    private final String tenantId;

    public VerifyRequestEvent(String reqId,
                              String srcIp,
                              int srcPort,
                              long tsEpochMs,
                              String claimedMsisdn,
                              AccessTech accessTech) {
        this(reqId, srcIp, srcPort, tsEpochMs, claimedMsisdn, accessTech, null);
    }

    public VerifyRequestEvent(String reqId,
                              String srcIp,
                              int srcPort,
                              long tsEpochMs,
                              String claimedMsisdn,
                              AccessTech accessTech,
                              AssurancePolicy.RiskClass riskClass) {
        this(reqId, srcIp, srcPort, tsEpochMs, claimedMsisdn, null, accessTech, riskClass);
    }

    /** Full overload: carries the optional TS.43 entitlement-token IMSI. */
    public VerifyRequestEvent(String reqId,
                              String srcIp,
                              int srcPort,
                              long tsEpochMs,
                              String claimedMsisdn,
                              String claimedImsi,
                              AccessTech accessTech,
                              AssurancePolicy.RiskClass riskClass) {
        this(reqId, srcIp, srcPort, tsEpochMs, claimedMsisdn, claimedImsi,
                accessTech, riskClass, null);
    }

    /**
     * Full overload with billing tenant: the CAMARA-edge tenant resolved from
     * {@code X-Api-Key}; {@code null} = unattributed (legacy callers).
     */
    public VerifyRequestEvent(String reqId,
                              String srcIp,
                              int srcPort,
                              long tsEpochMs,
                              String claimedMsisdn,
                              String claimedImsi,
                              AccessTech accessTech,
                              AssurancePolicy.RiskClass riskClass,
                              String tenantId) {
        this.reqId = reqId;
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.tsEpochMs = tsEpochMs;
        this.claimedMsisdn = claimedMsisdn;
        this.claimedImsi = claimedImsi;
        this.accessTech = accessTech;
        this.riskClass = riskClass;
        this.tenantId = tenantId;
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

    /** Optional claimed IMSI (TS.43 entitlement token); {@code null} = absent. */
    public String claimedImsi() {
        return claimedImsi;
    }

    public AccessTech accessTech() {
        return accessTech;
    }

    /** Optional transaction risk class; {@code null} = LOGIN. */
    public AssurancePolicy.RiskClass riskClass() {
        return riskClass;
    }

    /** Optional billing tenant (CAMARA edge); {@code null} = unattributed. */
    public String tenantId() {
        return tenantId;
    }
}