/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 6749 §5.2 error body: {@code {"error":..., "error_description":...}}.
 */
public record OAuthErrorResponse(
        String error,
        @JsonProperty("error_description") String errorDescription) {}
