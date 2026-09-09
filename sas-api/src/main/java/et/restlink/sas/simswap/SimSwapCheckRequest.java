/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

/**
 * CAMARA SimSwap v2.1.0 {@code POST /sim-swap/v2/check} request body
 * ({@code CreateCheckSimSwap}). {@code phoneNumber} is optional and only
 * allowed on the 2-legged path — with a user-bound (3-legged) access token the
 * identifier comes from the token and an explicit number answers
 * {@code 422 UNNECESSARY_IDENTIFIER}. {@code maxAge} is the look-back window in
 * hours (spec range 1..2400, default 240). Unknown properties are ignored.
 */
public record SimSwapCheckRequest(String phoneNumber, Integer maxAge) {
}
