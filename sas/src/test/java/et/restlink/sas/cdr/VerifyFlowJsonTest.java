/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code evidence_json} builder: factor values + weights + stage notes, with
 * no room for subscriber identifiers.
 */
class VerifyFlowJsonTest {

    @Test
    void buildsFactorsWeightsAndNotes() {
        VerifyResult r = VerifyResult.approved("req-1", "+251911111111",
                AssuranceLevel.HIGH, 85, 70, "LOGIN",
                new VerifyResult.Factor(1.0, 0.25),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(0.5, 0.15));

        String json = VerifyFlowJson.build(r, List.of("resolver:BOUND", "verifier:MAP-PSI+SAI"));

        assertTrue(json.contains("\"score\":85"));
        assertTrue(json.contains("\"threshold\":70"));
        assertTrue(json.contains("\"riskClass\":\"LOGIN\""));
        assertTrue(json.contains("\"ipBindingFresh\":{\"value\":1.0,\"weight\":0.25}"));
        assertTrue(json.contains("\"reachable\":{\"value\":1.0,\"weight\":0.3}"));
        assertTrue(json.contains("\"notSimSwapped\":{\"value\":1.0,\"weight\":0.3}"));
        assertTrue(json.contains("\"locationPlausible\":{\"value\":0.5,\"weight\":0.15}"));
        assertTrue(json.contains("\"notes\":[\"resolver:BOUND\",\"verifier:MAP-PSI+SAI\"]"));

        // privacy: identifiers can never appear
        assertFalse(json.contains("+251911111111"));
        assertFalse(json.toUpperCase().contains("MSISDN"));
        assertFalse(json.toUpperCase().contains("IMSI"));
    }

    @Test
    void fallbackWithoutSnapshotYieldsNotesOnly() {
        VerifyResult r = VerifyResult.fallback("req-2", FallbackReason.NO_BINDING);
        String json = VerifyFlowJson.build(r, List.of("resolver:NO_BINDING"));
        assertEquals("{\"notes\":[\"resolver:NO_BINDING\"]}", json);
    }

    @Test
    void nullInputsAreSafe() {
        assertNull(VerifyFlowJson.build(null, List.of("x")));
        assertNull(VerifyFlowJson.build(VerifyResult.fallback("req-3", FallbackReason.PURGED), null));
    }

    @Test
    void quotesInNotesAreNeutralised() {
        String json = VerifyFlowJson.build(
                VerifyResult.fallback("req-4", FallbackReason.VERIFY_ERROR),
                List.of("we\"ird"));
        assertFalse(json.contains("we\"ird"));
        assertTrue(json.contains("we'ird"));
    }
}
