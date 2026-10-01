/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.exposure;

import et.restlink.sas.ras.resolver.ResolverBackend;

/**
 * The entitlement server's resolver, for the device-declared session tuple (layered ADR
 * D5). Implemented by the sas-ecs bootstrap seam; {@code null} when the resolver is not
 * wired (subscriber exposure off).
 */
public interface ResolverAccess {

    ResolverBackend resolverBackend();
}
