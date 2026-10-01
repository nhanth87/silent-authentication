/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

/**
 * The two headers every exposure call carries.
 *
 * @param requestId the CAMARA {@code reqId} ({@code X-Request-Id}); dedup key with the
 *                  operation, and with the token {@code jti} for redemption
 * @param budgetMs  what the caller can still wait ({@code X-Sas-Budget-Ms}); the
 *                  entitlement side never signals past it
 */
public record ExposureContext(String requestId, int budgetMs) {
}
