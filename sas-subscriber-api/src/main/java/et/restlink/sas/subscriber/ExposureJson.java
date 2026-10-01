/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Strict JSON for both sides of the exposure hop: the contract says
 * {@code additionalProperties: false} and typed scalars, so unknown fields, trailing
 * tokens and "123"-for-123 coercions are refused instead of silently dropped or converted.
 */
public final class ExposureJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private ExposureJson() {
    }

    /** Parse a request/response body; a malformed body is a 400, never an exception. */
    public static <T> ExposureOutcome<T> read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return ExposureOutcome.problem(ExposureCause.INVALID_ARGUMENT, "request body is required");
        }
        try {
            T value = MAPPER.readValue(body, type);
            return value == null
                    ? ExposureOutcome.problem(ExposureCause.INVALID_ARGUMENT, "request body is required")
                    : ExposureOutcome.ok(value);
        } catch (UnrecognizedPropertyException e) {
            String property = e.getPropertyName();
            if ("ueMacAddr".equals(property)) {
                return ExposureOutcome.problem(ExposureCause.UNSUPPORTED_ADDRESS_TYPE.problem(
                        "MAC-based UE identification is not supported", property));
            }
            return ExposureOutcome.problem(ExposureCause.INVALID_ARGUMENT.problem(
                    "unknown property", property));
        } catch (JsonProcessingException e) {
            // Location only — the parser message may echo request content.
            return ExposureOutcome.problem(ExposureCause.INVALID_ARGUMENT,
                    "malformed JSON at " + e.getLocation());
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
