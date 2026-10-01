/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import et.restlink.sas.api.ExposureAdapters;
import et.restlink.sas.api.SasVerifyEngine;
import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Host adapter for the CAMARA SimSwap port: asks the entitlement server's
 * {@code POST /sim-change/retrieve} (layered ADR §4.3), which picks the evidence source.
 *
 * <p>Fail-closed: no evidence, a refusal, a timeout or an unreachable entitlement
 * server all return empty, and the northbound answers {@code 404 IDENTIFIER_NOT_FOUND}
 * rather than reporting {@code swapped:false}.</p>
 */
@ApplicationScoped
public class HostSimSwapEvidence implements SimSwapQueryPort {

    private static final Logger LOG = LogManager.getLogger(HostSimSwapEvidence.class);
    static final int BUDGET_MS = 2000;
    private static final long WAIT_GRACE_MS = 200L;

    @Inject
    SasVerifyEngine bootstrap;

    @Override
    public Optional<Instant> lastSimChange(String msisdn) {
        SubscriberService service = bootstrap == null ? null : bootstrap.subscribers();
        if (service == null) {
            LOG.error("[SAS] SimSwap evidence requested with no subscriber exposure wired — failing closed");
            return Optional.empty();
        }
        String digits = PhoneNumberNormalizer.toDigits(msisdn).orElse(null);
        if (digits == null) {
            return Optional.empty();
        }
        try {
            ExposureOutcome<SimChangeInfo> answer = service.retrieveSimChange(
                            ExposureAdapters.context("simswap", BUDGET_MS),
                            new SimChangeReq(bootstrap.afId(), digits))
                    .get(BUDGET_MS + WAIT_GRACE_MS, TimeUnit.MILLISECONDS);
            if (answer != null && !answer.ok()) {
                LOG.info("[SAS] SimSwap: no evidence ({})", answer.cause());
            }
            return Optional.ofNullable(ExposureAdapters.latestSimChange(answer));
        } catch (TimeoutException e) {
            LOG.warn("[SAS] SimSwap: the entitlement server did not answer — failing closed");
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }
}
