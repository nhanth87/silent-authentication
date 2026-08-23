/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import et.restlink.sas.model.AssuranceLevel;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerifyResult;

import org.junit.jupiter.api.Test;

/**
 * Wire shape of the enriched {@code POST /verify} response: key names,
 * NON_NULL omission and the privacy rule — no MSISDN anywhere in the body.
 */
class VerifyResponseDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String MSISDN = "+251911111111";

    private VerifyResult approveResult() {
        return VerifyResult.approved("req-approve", MSISDN,
                AssuranceLevel.HIGH, 85, 70, "LOGIN",
                new VerifyResult.Factor(1.0, 0.25),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(0.5, 0.15));
    }

    private VerifyResult fallbackResult() {
        return VerifyResult.fallback("req-fallback", FallbackReason.LOW_ASSURANCE,
                60, 70, "LOGIN",
                new VerifyResult.Factor(0.0, 0.25),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(1.0, 0.30),
                new VerifyResult.Factor(0.0, 0.15));
    }

    @Test
    void approveBodyCarriesDecisionAndAssurance() throws Exception {
        String json = mapper.writeValueAsString(
                VerifyResponseDto.from(true, approveResult()));
        JsonNode n = mapper.readTree(json);

        assertTrue(n.get("devicePhoneNumberVerified").isBoolean());
        assertTrue(n.get("devicePhoneNumberVerified").asBoolean());
        assertEquals("req-approve", n.get("reqId").asText());
        assertEquals("APPROVE", n.get("decision").asText());

        JsonNode assurance = n.get("assurance");
        assertEquals(85, assurance.get("score").asInt());
        assertEquals("HIGH", assurance.get("level").asText());
        assertEquals(70, assurance.get("threshold").asInt());
        assertEquals("LOGIN", assurance.get("riskClass").asText());

        JsonNode factors = assurance.get("factors");
        assertEquals(4, factors.size());
        assertEquals(1.0, factors.get("ipBindingFresh").get("value").asDouble(), 1e-9);
        assertEquals(0.25, factors.get("ipBindingFresh").get("weight").asDouble(), 1e-9);
        assertEquals(1.0, factors.get("reachable").get("value").asDouble(), 1e-9);
        assertEquals(0.30, factors.get("reachable").get("weight").asDouble(), 1e-9);
        assertEquals(1.0, factors.get("notSimSwapped").get("value").asDouble(), 1e-9);
        assertEquals(0.30, factors.get("notSimSwapped").get("weight").asDouble(), 1e-9);
        assertEquals(0.5, factors.get("locationPlausible").get("value").asDouble(), 1e-9);
        assertEquals(0.15, factors.get("locationPlausible").get("weight").asDouble(), 1e-9);

        // NON_NULL: approved bodies stay compact — no fallback fields.
        assertFalse(n.has("fallbackReason"));

        // Privacy: the MSISDN never appears in the response body.
        assertFalse(json.contains(MSISDN));
        assertFalse(json.toUpperCase().contains("MSISDN"));
        assertFalse(json.toUpperCase().contains("IMSI"));
    }

    @Test
    void fallbackBodyCarriesReasonAndMeasurableAssurance() throws Exception {
        String json = mapper.writeValueAsString(
                VerifyResponseDto.from(false, fallbackResult()));
        JsonNode n = mapper.readTree(json);

        assertFalse(n.get("devicePhoneNumberVerified").asBoolean());
        assertEquals("req-fallback", n.get("reqId").asText());
        assertEquals("FALLBACK", n.get("decision").asText());
        assertEquals("LOW_ASSURANCE", n.get("fallbackReason").asText());

        JsonNode assurance = n.get("assurance");
        assertEquals(60, assurance.get("score").asInt());
        assertEquals(70, assurance.get("threshold").asInt());
        assertEquals("FALLBACK", assurance.get("level").asText());

        assertFalse(json.contains(MSISDN));
    }

    @Test
    void northboundTimeoutFallbackOmitsAbsentSnapshot() throws Exception {
        // Legacy factory (no snapshot): budget timeout at the resource has no
        // score to report — the assurance block is omitted entirely.
        String json = mapper.writeValueAsString(VerifyResponseDto.from(false,
                VerifyResult.fallback("req-timeout", FallbackReason.SAS_TIMEOUT)));
        JsonNode n = mapper.readTree(json);

        assertFalse(n.get("devicePhoneNumberVerified").asBoolean());
        assertEquals("FALLBACK", n.get("decision").asText());
        assertEquals("SAS_TIMEOUT", n.get("fallbackReason").asText());
        assertFalse(n.has("assurance"));
        assertFalse(json.contains(MSISDN));
    }

    @Test
    void legacyBooleanShapeStaysMinimal() throws Exception {
        String json = mapper.writeValueAsString(new VerifyResponseDto(true));
        assertEquals("{\"devicePhoneNumberVerified\":true}", json);
    }
}
