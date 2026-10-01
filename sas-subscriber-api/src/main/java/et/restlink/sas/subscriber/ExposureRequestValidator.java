/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import et.restlink.sas.subscriber.dto.IpAddr;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.ProblemDetails;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.UeIdReq;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The request rules of the contract that a JSON parser alone does not enforce: required
 * fields, patterns, ranges, the synchronous monitoring profile. Both sides use it — the
 * gateway before sending, the entitlement side on receipt. Empty means valid.
 */
public final class ExposureRequestValidator {

    private ExposureRequestValidator() {
    }

    public static Optional<ProblemDetails> headers(String requestId, String budgetMs) {
        if (requestId == null || !ExposureApi.REQUEST_ID.matcher(requestId).matches()) {
            return bad("X-Request-Id must be 8-64 of [A-Za-z0-9._:-]", ExposureApi.HEADER_REQUEST_ID);
        }
        try {
            int budget = budgetMs == null ? -1 : Integer.parseInt(budgetMs.trim());
            if (budget < 1 || budget > ExposureApi.MAX_BUDGET_MS) {
                return bad("X-Sas-Budget-Ms must be 1-3000", ExposureApi.HEADER_BUDGET_MS);
            }
        } catch (NumberFormatException e) {
            return bad("X-Sas-Budget-Ms must be an integer", ExposureApi.HEADER_BUDGET_MS);
        }
        return Optional.empty();
    }

    public static Optional<ProblemDetails> ueId(UeIdReq req) {
        Optional<ProblemDetails> af = afId(req.afId());
        if (af.isPresent()) {
            return af;
        }
        IpAddr ip = req.ueIpAddr();
        if (ip == null) {
            return bad("ueIpAddr is required", "ueIpAddr");
        }
        boolean v4 = ip.ipv4Addr() != null;
        boolean v6 = ip.ipv6Addr() != null;
        if (v4 == v6) {
            return bad("exactly one of ipv4Addr / ipv6Addr", "ueIpAddr");
        }
        if (v4 ? !isIpv4(ip.ipv4Addr()) : !isIpv6(ip.ipv6Addr())) {
            return bad("not an IP address literal", v4 ? "ueIpAddr.ipv4Addr" : "ueIpAddr.ipv6Addr");
        }
        if (req.portNumber() == null || req.portNumber() < 1 || req.portNumber() > 65535) {
            return bad("portNumber 1-65535 is required (CGNAT: IP + port + time)", "portNumber");
        }
        if (!isDateTime(req.observedAt())) {
            return bad("observedAt must be an RFC 3339 date-time", "observedAt");
        }
        return Optional.empty();
    }

    public static Optional<ProblemDetails> monitoring(String scsAsId, MonitoringEventSubscription req) {
        if (scsAsId == null || scsAsId.isBlank() || scsAsId.length() > 64) {
            return bad("scsAsId must be 1-64 characters", "scsAsId");
        }
        Optional<ProblemDetails> number = msisdn(req.msisdn());
        if (number.isPresent()) {
            return number;
        }
        if (req.monitoringType() == null) {
            return bad("monitoringType is required", "monitoringType");
        }
        Set<String> types = new HashSet<>();
        types.add(req.monitoringType());
        List<String> addn = req.addnMonTypes() == null ? List.of() : req.addnMonTypes();
        if (addn.size() > 1) {
            return bad("at most one additional monitoring type", "addnMonTypes");
        }
        for (String t : addn) {
            if (t == null || !types.add(t)) {
                return bad("monitoring types must be distinct", "addnMonTypes");
            }
        }
        for (String t : types) {
            if (!ExposureApi.MONITORING_TYPES.contains(t)) {
                return Optional.of(ExposureCause.UNSUPPORTED_MONITORING_TYPE.problem(
                        "supported: UE_REACHABILITY, LOCATION_REPORTING", "monitoringType"));
            }
        }
        if (req.locationType() != null && !ExposureApi.LAST_KNOWN_LOCATION.equals(req.locationType())) {
            return bad("only LAST_KNOWN_LOCATION is offered", "locationType");
        }
        if (req.maximumNumberOfReports() == null || req.maximumNumberOfReports() != 1) {
            return bad("maximumNumberOfReports must be 1 (one-time profile)", "maximumNumberOfReports");
        }
        if (!Boolean.TRUE.equals(req.immediateRep())) {
            return bad("immediateRep must be true (immediate report profile)", "immediateRep");
        }
        return Optional.empty();
    }

    public static Optional<ProblemDetails> simChange(SimChangeReq req) {
        Optional<ProblemDetails> af = afId(req.afId());
        return af.isPresent() ? af : msisdn(req.msisdn());
    }

    public static Optional<ProblemDetails> redeem(OperatorTokenRedeemReq req) {
        Optional<ProblemDetails> af = afId(req.afId());
        if (af.isPresent()) {
            return af;
        }
        String token = req.operatorToken();
        if (token == null || token.length() < 16 || token.length() > 8192) {
            return bad("operatorToken must be 16-8192 characters", "operatorToken");
        }
        if (req.clientId() == null || req.clientId().isBlank() || req.clientId().length() > 128) {
            return bad("clientId must be 1-128 characters", "clientId");
        }
        return Optional.empty();
    }

    // ---- helpers ------------------------------------------------------------

    private static Optional<ProblemDetails> afId(String afId) {
        if (afId == null || afId.isBlank() || afId.length() > 64) {
            return bad("afId must be 1-64 characters", "afId");
        }
        return Optional.empty();
    }

    private static Optional<ProblemDetails> msisdn(String msisdn) {
        if (msisdn == null || !ExposureApi.MSISDN.matcher(msisdn).matches()) {
            return bad("msisdn must be 5-15 digits without '+' (TS 23.003)", "msisdn");
        }
        return Optional.empty();
    }

    private static boolean isIpv4(String literal) {
        try {
            Inet4Address.ofLiteral(literal);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isIpv6(String literal) {
        try {
            Inet6Address.ofLiteral(literal);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isDateTime(String value) {
        if (value == null) {
            return false;
        }
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Optional<ProblemDetails> bad(String detail, String param) {
        return Optional.of(ExposureCause.INVALID_ARGUMENT.problem(detail, param));
    }
}
