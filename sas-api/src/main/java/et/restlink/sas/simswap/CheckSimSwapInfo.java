/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

/**
 * CAMARA SimSwap v2.1.0 {@code POST /sim-swap/v2/check} response body
 * ({@code CheckSimSwapInfo}): {@code swapped} tells whether a SIM change
 * happened inside the requested {@code maxAge} window.
 */
public record CheckSimSwapInfo(boolean swapped) {
}
