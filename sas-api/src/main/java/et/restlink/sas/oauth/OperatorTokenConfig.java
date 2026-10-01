/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Operator-token acceptance on the gateway. Since layered ADR D3 there is no key, issuer
 * list or ledger here — the entitlement server redeems the token — so only the acceptance
 * switches remain.
 */
@ApplicationScoped
public class OperatorTokenConfig {

    @Inject
    @ConfigProperty(name = "sas.operator-token.enabled", defaultValue = "true")
    Optional<Boolean> enabled;

    /** Lab-only {@code X-Sas-Operator-Token} header path; prod keeps it off (D6). */
    @Inject
    @ConfigProperty(name = "sas.operator-token.header-enabled", defaultValue = "false")
    Optional<Boolean> headerEnabled;

    /**
     * Lab-only SAS extension (D6): {@code Authorization: Bearer operatortoken:<tk>} straight
     * on {@code /verify}, skipping the CAMARA AS. Off by default; prod preflight refuses it.
     */
    @Inject
    @ConfigProperty(name = "sas.operator-token.direct-bearer-enabled", defaultValue = "false")
    Optional<Boolean> directBearerEnabled;

    public boolean directBearerEnabled() {
        return directBearerEnabled != null && directBearerEnabled.orElse(false);
    }

    public boolean enabled() {
        return enabled == null || enabled.orElse(true);
    }

    public boolean headerEnabled() {
        return headerEnabled != null && headerEnabled.orElse(false);
    }
}
