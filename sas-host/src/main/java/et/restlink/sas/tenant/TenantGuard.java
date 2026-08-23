/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class TenantGuard {
    @Inject TenantService tenants;
    @Inject AppUserService appUsers;

    public Optional<TenantPrincipal> byHttpApiKey(String key) {
        return tenants.byAdminApiKey(key)
                .map(t -> new TenantPrincipal(t.tenantId, t.networkId, t.enabled));
    }

    public Optional<TenantPrincipal> byAppBearerKey(String key) {
        return appUsers.authenticate(key)
                .map(u -> new TenantPrincipal(u.tenantId, u.networkId, u.enabled));
    }
}