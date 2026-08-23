/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.diameter;

import java.security.SecureRandom;
import java.time.Instant;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.restlink.testapp.HssSimulator;
import et.restlink.testapp.MessageLog;
import et.restlink.testapp.SubscriberState;

/**
 * Shared answer-building helpers: 3GPP experimental result codes, random
 * auth-material minting and ring-buffer logging. Fail-safe per the lab rules:
 * handler exceptions must surface as Result-Code 3002 answers.
 */
final class Answers {

    /** RFC 6733 DIAMETER_UNABLE_TO_DELIVER — the fail-safe answer code. */
    static final long UNABLE_TO_DELIVER = ResultCodes.DIAMETER_UNABLE_TO_DELIVER;
    static final long SUCCESS = ResultCodes.DIAMETER_SUCCESS;

    /** TS 29.272 §7.2.6 / TS 29.273 §8.1.2 — DIAMETER_ERROR_USER_UNKNOWN. */
    static final long ER_USER_UNKNOWN = 5001L;
    /**
     * TS 29.272 DIAMETER_ERROR_UNKNOWN_EPS_SUBSCRIPTION (detached UE); the
     * same value serves SWx DIAMETER_ERROR_USER_NO_NON_3GPP_SUBSCRIPTION.
     */
    static final long ER_NO_SUBSCRIPTION = 5421L;

    static final long VENDOR_3GPP = 10415L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Answers() {
    }

    /**
     * Human-readable result label for the log ring. Error codes ride the base
     * Result-Code because corsac marks Experimental-Result disallowed
     * ({@code setExperimentalResultAllowed(false)}) on every S6a/SWx answer
     * implementation.
     */
    static String label(long resultCode, long experimentalCode) {
        return Long.toString(experimentalCode);
    }

    static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    static String sessionId(DiameterMessage message) {
        try {
            return message.getSessionId();
        } catch (DiameterException e) {
            return "?";
        }
    }

    static String usernameOf(DiameterMessage message) {
        try {
            String username = message.getUsername();
            return username == null ? "-" : username;
        } catch (DiameterException e) {
            return "-";
        }
    }

    static Instant now() {
        return Instant.now();
    }

    static MessageLog log(HssSimulator hss) {
        return hss.log();
    }

    /** Log a received request before any answer is built. */
    static void received(HssSimulator hss, String command, DiameterMessage request,
            String details) {
        log(hss).add(new MessageLog.Entry(now(), "req", command,
                sessionId(request), "-", details));
    }

    /** True when the subscriber is known, attached and not barred. */
    static boolean serviceable(SubscriberState state) {
        return state.attached() && !state.barred();
    }
}
