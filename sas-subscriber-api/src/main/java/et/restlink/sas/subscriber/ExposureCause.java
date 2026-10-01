/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import et.restlink.sas.subscriber.dto.InvalidParam;
import et.restlink.sas.subscriber.dto.ProblemDetails;

import java.util.List;

/**
 * Every {@code cause} code of the exposure contract, with its HTTP status. The set is
 * closed: the OpenAPI enum and this enum must stay equal (contract test A checks it).
 */
public enum ExposureCause {
    INVALID_ARGUMENT(400),
    UNSUPPORTED_MONITORING_TYPE(400),
    UNSUPPORTED_ADDRESS_TYPE(400),
    CLIENT_UNAUTHENTICATED(401),
    SCOPE_MISSING(403),
    AF_MISMATCH(403),
    NO_BINDING(404),
    AMBIGUOUS_BINDING(409),
    SUBSCRIBER_NOT_FOUND(404),
    NO_EVIDENCE(404),
    INVALID_TOKEN(401),
    TOO_MANY_REQUESTS(429),
    INTERNAL_ERROR(500),
    NETWORK_ERROR(502),
    TRANSPORT_UNAVAILABLE(503),
    NETWORK_TIMEOUT(504),
    BUDGET_EXHAUSTED(504);

    private final int status;

    ExposureCause(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** Problem details for this cause. {@code detail} must never carry an IMSI or a token. */
    public ProblemDetails problem(String detail) {
        return new ProblemDetails(null, name(), status, detail, null, name(), null);
    }

    /** Same, naming the offending parameter. */
    public ProblemDetails problem(String detail, String param) {
        return new ProblemDetails(null, name(), status, detail, null, name(),
                List.of(new InvalidParam(param, detail)));
    }
}
