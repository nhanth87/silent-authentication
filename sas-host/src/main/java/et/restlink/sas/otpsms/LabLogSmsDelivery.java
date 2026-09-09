/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lab delivery seam for the OTP SMS fallback: the composed message is written
 * to the log and <strong>nothing is sent</strong>. Restlink does not wholesale
 * SMS — in production this port is an operator adapter (Ethio Telecom SMSC, or
 * SGd per TS 29.338) reached over the operator's own protected route
 * (Home Routing + signalling firewall, SG.22).
 *
 * <p>Fail-closed: the bean only logs while {@code sas.otp.sms-delivery=log}.
 * Any other value — including a real adapter name that is not wired yet —
 * returns {@link SmsDeliveryPort.Outcome#UNAVAILABLE}, so {@code /send-code}
 * answers {@code 500 INTERNAL_ERROR} and never issues an
 * {@code authenticationId} for an SMS that did not leave the building.</p>
 *
 * <p><strong>Lab-only, and loudly so</strong>: the log line carries the OTP in
 * cleartext because there is no handset in the loop. The production preflight
 * refuses this sender ({@code PRO-29}: OTP surface must be off, or on a real
 * operator route) — see {@code harness/preflight_prod.py}.</p>
 */
@ApplicationScoped
public class LabLogSmsDelivery implements SmsDeliveryPort {

    private static final Logger LOG = LogManager.getLogger(LabLogSmsDelivery.class);

    @Inject
    OtpConfig config;

    @Override
    public DeliveryResult deliver(String msisdn, String smsText) {
        if (config == null || !config.labLogDelivery()) {
            String mode = config == null ? "unset" : config.smsDelivery();
            LOG.error("[SAS] OTP SMS delivery requested but sas.otp.sms-delivery={} has no "
                    + "adapter — failing closed (nothing sent)", mode);
            return DeliveryResult.unavailable("no operator SMS adapter for delivery="
                    + (mode == null || mode.isBlank() ? "unset" : mode));
        }
        LOG.warn("[SAS][LAB-SMS — NOT SENT] to={} chars={} text=\"{}\"",
                mask(msisdn), smsText == null ? 0 : smsText.length(), smsText);
        return DeliveryResult.delivered("lab log-only sender (nothing left the SAS)");
    }

    /** Privacy: never log a full MSISDN. */
    private static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) {
            return "***";
        }
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
