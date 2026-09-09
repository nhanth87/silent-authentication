/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * OTP SMS fallback policy ({@code sas.otp.*}) — the knobs behind CAMARA
 * OneTimePasswordSMS v1.1.1 ({@code /one-time-password-sms/v1}).
 *
 * <p>Fail-closed by construction: the surface is <strong>off</strong> unless
 * {@code sas.otp.enabled=true}, and every policy value is range-checked —
 * an unusable value falls back to the built-in default rather than widening
 * the window (a 0-second TTL or 99 attempts would be an authentication bypass,
 * not a misconfiguration to honour).</p>
 *
 * <p>{@code sas.otp.sms-delivery} names the delivery seam. Only {@code log}
 * exists today (lab: the composed SMS is written to the log, nothing is sent —
 * Restlink does not wholesale SMS; the operator SMSC does). Any other value
 * makes delivery fail closed until a real operator adapter (SMSC / SGd,
 * TS 29.338) lands; the production preflight refuses a lab sender
 * ({@code PRO-29}).</p>
 *
 * <p>The config values deliberately use {@link Optional} with no
 * {@code defaultValue} — a Quarkus 3.x runtime trap makes
 * {@code defaultValue = ""} fail config loading.</p>
 */
@ApplicationScoped
public class OtpConfig {

    /** Delivery mode that only logs (lab). Anything else fails closed today. */
    public static final String DELIVERY_LOG = "log";

    /** CAMARA {@code Code} schema caps the OTP at 10 characters. */
    static final int MAX_CODE_LENGTH = 10;

    static final int MIN_CODE_LENGTH = 4;

    @ConfigProperty(name = "sas.otp.enabled")
    Optional<String> enabledRaw;

    @ConfigProperty(name = "sas.otp.sms-delivery")
    Optional<String> smsDeliveryRaw;

    @ConfigProperty(name = "sas.otp.code-length", defaultValue = "6")
    int codeLength;

    @ConfigProperty(name = "sas.otp.ttl-seconds", defaultValue = "300")
    long ttlSeconds;

    @ConfigProperty(name = "sas.otp.max-attempts", defaultValue = "3")
    int maxAttempts;

    @ConfigProperty(name = "sas.otp.max-codes-per-number", defaultValue = "3")
    int maxCodesPerNumber;

    @ConfigProperty(name = "sas.otp.rate-window-seconds", defaultValue = "3600")
    long rateWindowSeconds;

    /** Master switch: the endpoints answer {@code 404 NOT_FOUND} when off. */
    public boolean enabled() {
        return parseToggle(enabledRaw);
    }

    /** Delivery seam name ({@code log} in lab); empty means unset. */
    public String smsDelivery() {
        return smsDeliveryRaw == null ? "" : smsDeliveryRaw.orElse("").trim();
    }

    /** True when the configured delivery seam is the lab log-only sender. */
    public boolean labLogDelivery() {
        return DELIVERY_LOG.equalsIgnoreCase(smsDelivery());
    }

    /** OTP length in characters (digits), clamped to the spec-safe range. */
    public int codeLength() {
        return clamp(codeLength, MIN_CODE_LENGTH, MAX_CODE_LENGTH, 6);
    }

    /** How long an {@code authenticationId} stays valid, in seconds. */
    public long ttlSeconds() {
        return clamp(ttlSeconds, 30L, 3600L, 300L);
    }

    /** Wrong-code attempts before the attempt is burned (VERIFICATION_FAILED). */
    public int maxAttempts() {
        return clamp(maxAttempts, 1, 10, 3);
    }

    /** OTP sends per MSISDN inside {@link #rateWindowSeconds()} before 403. */
    public int maxCodesPerNumber() {
        return clamp(maxCodesPerNumber, 1, 100, 3);
    }

    /** Sliding window for the per-MSISDN send limit, in seconds. */
    public long rateWindowSeconds() {
        return clamp(rateWindowSeconds, 60L, 86_400L, 3600L);
    }

    private static int clamp(int value, int min, int max, int dflt) {
        return (value < min || value > max) ? dflt : value;
    }

    private static long clamp(long value, long min, long max, long dflt) {
        return (value < min || value > max) ? dflt : value;
    }

    private static boolean parseToggle(Optional<String> raw) {
        return raw != null && raw.isPresent() && Boolean.parseBoolean(raw.get().trim());
    }
}
