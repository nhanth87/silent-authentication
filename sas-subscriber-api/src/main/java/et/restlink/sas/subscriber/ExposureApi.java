/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import java.util.Set;
import java.util.regex.Pattern;

/** Names fixed by the contract: paths, headers, scopes, enumerations, limits. */
public final class ExposureApi {

    public static final String BASE_PATH = "/sas-subscriber-exposure/v1";
    public static final String UE_ID_PATH = "/ue-id/retrieve";
    public static final String MONITORING_PATH = "/monitoring-event/{scsAsId}/subscriptions";
    public static final String SIM_CHANGE_PATH = "/sim-change/retrieve";
    public static final String REDEEM_PATH = "/operator-token/redeem";

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_BUDGET_MS = "X-Sas-Budget-Ms";

    public static final String SCOPE_UE_ID = "exposure:ue-id";
    public static final String SCOPE_MONITORING = "exposure:monitoring";
    public static final String SCOPE_SIM_CHANGE = "exposure:sim-change";
    public static final String SCOPE_OPERATOR_TOKEN = "exposure:operator-token";

    public static final String UE_REACHABILITY = "UE_REACHABILITY";
    public static final String LOCATION_REPORTING = "LOCATION_REPORTING";
    public static final Set<String> MONITORING_TYPES = Set.of(UE_REACHABILITY, LOCATION_REPORTING);
    public static final String LAST_KNOWN_LOCATION = "LAST_KNOWN_LOCATION";

    public static final String AUTH_METHOD_EAP_AKA = "EAP-AKA";

    public static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    public static final Pattern MSISDN = Pattern.compile("^[0-9]{5,15}$");
    public static final Pattern GPSI = Pattern.compile("^msisdn-[0-9]{5,15}$");
    public static final Pattern EVIDENCE_SOURCE = Pattern.compile("^[A-Z0-9_]{2,32}$");
    public static final int MAX_BUDGET_MS = 3000;

    /** Redemption dedup window and the generic dedup window of the contract. */
    public static final long DEDUP_WINDOW_MS = 60_000L;

    private ExposureApi() {
    }
}
