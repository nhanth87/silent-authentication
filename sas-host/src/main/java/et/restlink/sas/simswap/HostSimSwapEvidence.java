/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import et.restlink.sas.bootstrap.SasBootstrap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Optional;

/**
 * Host adapter for the CAMARA SimSwap port: reads the SIM-change evidence the
 * SAS already holds (MAP {@code lastUpdateLocation} → read-only Sh UDR → SWx
 * EAP-AKA identity) through the bootstrap seam, never around it.
 *
 * <p>Fail-closed: with no evidence source wired — a live jSS7/corsac transport
 * has no binding-age read yet (Sh UDR/SNR is an open item) — this returns empty
 * and the northbound answers {@code 404 IDENTIFIER_NOT_FOUND} rather than
 * reporting {@code swapped:false}.</p>
 */
@ApplicationScoped
public class HostSimSwapEvidence implements SimSwapQueryPort {

    private static final Logger LOG = LogManager.getLogger(HostSimSwapEvidence.class);

    @Inject
    SasBootstrap bootstrap;

    @Override
    public Optional<Instant> lastSimChange(String msisdn) {
        if (bootstrap == null) {
            LOG.error("[SAS] SimSwap evidence requested with no bootstrap wired — failing closed");
            return Optional.empty();
        }
        return bootstrap.lastSimChange(msisdn);
    }
}
