/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.qrsession;

/**
 * Wire shapes for the desktop → phone QR hand-off.
 *
 * <p>Kept as records so Jackson serialises them by name and
 * {@code fail-on-unknown-properties} still rejects anything the bank invents.</p>
 */
public final class QrSessionRequests {

    /** Cap on the number of live sessions a tenant may hold at once. */
    public static final int MAX_SESSIONS_PER_TENANT = 64;

    private QrSessionRequests() {
    }

    /**
     * {@code POST /qr-session} body.
     *
     * @param phoneNumber optional E.164 the bank wants proven; when absent the
     *                    phone supplies it after the SIM lookup, which is the
     *                    discovery variant of the same hand-off
     */
    public record CreateRequest(String phoneNumber) {
    }

    /**
     * {@code POST /qr-session} response — what the bank renders as a QR code.
     *
     * @param sessionId        opaque id the phone sends back
     * @param qrPayload        the exact string to encode; carries no bank data
     * @param expiresInSeconds remaining budget, so the bank can draw a countdown
     */
    public record CreateResponse(String sessionId, String qrPayload, long expiresInSeconds) {
    }

    /**
     * {@code POST /qr-session/{id}/claim} body — the phone's evidence.
     *
     * <p>{@code accessTech} is the phone's own bearer declaration. It is
     * advisory: it can only <em>exclude</em> a claim that declares a non-cellular
     * bearer, never raise assurance. What raises assurance is what the network
     * attests, exactly as at {@code /session-tuple} (gate H22).</p>
     *
     * @param msisdn        optional claimed number, for the discovery variant
     * @param accessTech    optional bearer declaration: GS_2G3G | LTE | NR | WIFI | FIXED
     * @param operatorToken optional GSMA TS.43 temporary token, for the Wi-Fi variant
     */
    public record ClaimRequest(String msisdn, String accessTech, String operatorToken) {
    }

    /** {@code POST /qr-session/{id}/claim} response — verdict for the phone. */
    public record ClaimResponse(String sessionId,
                                String state,
                                Boolean devicePhoneNumberVerified,
                                String fallbackReason) {
    }

    /**
     * {@code GET /qr-session/{id}} response — status for the bank to poll.
     *
     * <p>{@code state} is {@code PENDING} | {@code RESOLVED} | {@code EXPIRED}.
     * The bank must treat anything but {@code RESOLVED} as "not authenticated";
     * there is no default-approve reading of this object.</p>
     */
    public record StatusResponse(String sessionId,
                                 String state,
                                 Boolean devicePhoneNumberVerified,
                                 String fallbackReason,
                                 String decision,
                                 long expiresInSeconds) {
    }

    /** CAMARA-shaped error body: status + code + message, all required. */
    public record QrError(int status, String code, String message) {
    }
}
