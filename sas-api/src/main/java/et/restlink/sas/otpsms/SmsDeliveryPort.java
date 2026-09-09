/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.otpsms;

/**
 * Delivery seam for the OTP SMS fallback: the SAS composes the message and
 * hands it over, it never owns an SMS route. Restlink does not wholesale SMS —
 * in production this is the operator's SMSC (or SGd, TS 29.338), and the
 * business refusals the operator applies (fraud list, barred, SMS not
 * supported) surface as {@link Outcome#NOT_ALLOWED} / {@link Outcome#BLOCKED},
 * which the northbound maps onto the CAMARA
 * {@code ONE_TIME_PASSWORD_SMS.PHONE_NUMBER_*} error codes.
 *
 * <p>Fail-closed: anything that is not an explicit acceptance is
 * {@link Outcome#UNAVAILABLE} — no {@code authenticationId} is issued, so a
 * bank can never believe an OTP was delivered when it was not.</p>
 */
public interface SmsDeliveryPort {

    /** Operator-side outcome of one OTP SMS hand-off. */
    enum Outcome {
        /** Handed to the operator SMSC (in lab: written to the log, not sent). */
        DELIVERED,
        /** Operator refuses for business reasons (fraud, SMS not supported). */
        NOT_ALLOWED,
        /** Number is blocked from receiving SMS. */
        BLOCKED,
        /** No usable delivery route — fail closed, nothing was sent. */
        UNAVAILABLE
    }

    /** Outcome plus a log-safe detail (never the OTP itself). */
    record DeliveryResult(Outcome outcome, String detail) {

        public static DeliveryResult delivered(String detail) {
            return new DeliveryResult(Outcome.DELIVERED, detail);
        }

        public static DeliveryResult notAllowed(String detail) {
            return new DeliveryResult(Outcome.NOT_ALLOWED, detail);
        }

        public static DeliveryResult blocked(String detail) {
            return new DeliveryResult(Outcome.BLOCKED, detail);
        }

        public static DeliveryResult unavailable(String detail) {
            return new DeliveryResult(Outcome.UNAVAILABLE, detail);
        }

        public boolean delivered() {
            return outcome == Outcome.DELIVERED;
        }
    }

    /**
     * Deliver one composed SMS.
     *
     * @param msisdn  normalized E.164 destination
     * @param smsText the composed message (already contains the OTP)
     */
    DeliveryResult deliver(String msisdn, String smsText);
}
