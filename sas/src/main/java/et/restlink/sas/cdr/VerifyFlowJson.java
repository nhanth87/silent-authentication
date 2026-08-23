/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import et.restlink.sas.model.VerifyResult;

import java.util.List;

/**
 * Builds the {@code evidence_json} CDR payload: per-factor values + weights
 * plus stage notes. Only numeric fields and fixed enum-ish labels are ever
 * embedded — the MSISDN/IMSI is structurally excluded (privacy rule).
 */
public final class VerifyFlowJson {

    private VerifyFlowJson() {
    }

    /**
     * Compact JSON: {@code {"factors":{...},"notes":[...]}}. Returns
     * {@code null} when no factor snapshot exists on the result.
     */
    public static String build(VerifyResult result, List<String> stageNotes) {
        if (result == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        if (result.hasAssurance()) {
            sb.append("\"score\":").append(result.score());
            sb.append(",\"threshold\":").append(result.threshold());
            if (result.riskClass() != null) {
                sb.append(",\"riskClass\":\"").append(result.riskClass()).append('"');
            }
            sb.append(",\"factors\":{");
            factor(sb, "ipBindingFresh", result.ipBindingFresh());
            sb.append(',');
            factor(sb, "reachable", result.reachable());
            sb.append(',');
            factor(sb, "notSimSwapped", result.notSimSwapped());
            sb.append(',');
            factor(sb, "locationPlausible", result.locationPlausible());
            sb.append('}');
        }
        if (stageNotes != null && !stageNotes.isEmpty()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("\"notes\":[");
            for (int i = 0; i < stageNotes.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(safe(stageNotes.get(i))).append('"');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.length() > 2 ? sb.toString() : null;
    }

    private static void factor(StringBuilder sb, String name, VerifyResult.Factor f) {
        sb.append('"').append(name).append("\":{\"value\":")
                .append(f == null ? 0.0 : f.value())
                .append(",\"weight\":").append(f == null ? 0.0 : f.weight()).append('}');
    }

    /** Stage notes are internal enum/protocol tags; quotes are stripped. */
    private static String safe(String note) {
        return note == null ? "" : note.replace("\"", "'");
    }
}
