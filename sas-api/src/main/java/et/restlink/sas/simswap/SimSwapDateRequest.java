/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * CAMARA SimSwap v2.1.0 {@code POST /sim-swap/v2/retrieve-date} request body
 * ({@code CreateSimSwapDate}). Same identifier rules as
 * {@link SimSwapCheckRequest}: 3-legged tokens carry the number, 2-legged
 * callers must supply it. Unknown properties are rejected as
 * {@code 400 INVALID_ARGUMENT}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SimSwapDateRequest(String phoneNumber) {
}
