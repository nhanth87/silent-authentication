/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Device side of the desktop → phone QR hand-off for CAMARA Number
 * Verification v2.
 *
 * <p><strong>Why this exists.</strong> A customer signing in on a desktop has no
 * SIM in that machine, so the cellular IP-match path cannot help them: the
 * address the desktop sees is a LAN or VPN address no PGW ever assigned. The
 * bank renders a QR code; the phone that <em>does</em> have the SIM scans it and
 * calls {@link #claim}. From then on the phone is the device proving the number,
 * and the desktop only polls.</p>
 *
 * <p><strong>What the SDK does and does not decide.</strong> It moves three
 * pieces of data — the session id from the QR image, the bearer it observed, and
 * optionally a TS.43 temporary token or a number for the discovery variant. It
 * makes no trust decision: the verdict is the server's, and
 * {@link #claim} reports whatever came back, including a refusal.</p>
 *
 * <p><strong>Cellular.</strong> {@link #claim} should go through a
 * {@link Connector} bound to the cellular bearer — on Android that is
 * {@code ue-sdk-android}'s {@code CellularBearer}, which opens the connection
 * on an {@code android.net.Network}. The server refuses a claim that declares a
 * non-cellular bearer on the IP-match hand-off, so an unbound call over Wi-Fi
 * would be turned away with {@code ACCESS_TECH_NOT_CELLULAR}; the SDK says so up
 * front rather than spending the round trip.</p>
 *
 * <p><strong>Fail-closed.</strong> {@link CellularRequirement} decides whether
 * the caller may proceed at all, and every method here throws
 * {@link IOException} rather than returning an optimistic default.</p>
 */
public final class QrSessionClient {

    /** Scheme the bank puts in the QR image. */
    public static final String PAYLOAD_SCHEME = "silentauth://qr-session/";

    /** Thrown when a cellular bearer was demanded and the device has none. */
    public static final class CellularUnavailableException extends IOException {

        private static final long serialVersionUID = 1L;

        public CellularUnavailableException(String message) {
            super(message);
        }
    }

    /** What the bank polls: {@code PENDING} | {@code RESOLVED}. */
    public enum QrState {
        PENDING,
        RESOLVED,
        EXPIRED,
        UNKNOWN
    }

    /**
     * The verdict of one hand-off.
     *
     * @param sessionId  the id the claim was made against
     * @param state      lifecycle as the server saw it
     * @param verified   {@code true} only when the server explicitly approved;
     *                   {@code null} while still pending
     * @param fallbackReason server's fail-closed reason, when there was one
     */
    public record ClaimResult(String sessionId, QrState state, Boolean verified,
                              String fallbackReason) {

        /**
         * True only for an explicit approval. A pending or unknown state reads as
         * false: the bank must never treat silence as consent.
         */
        public boolean approved() {
            return Boolean.TRUE.equals(verified) && state == QrState.RESOLVED;
        }
    }

    /** Status as the bank polls it; same fields, no claim semantics. */
    public record StatusResult(String sessionId, QrState state, Boolean verified,
                               String fallbackReason, String decision,
                               long expiresInSeconds) {

        public boolean approved() {
            return Boolean.TRUE.equals(verified) && state == QrState.RESOLVED;
        }
    }

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public QrSessionClient() {
        this(3000, 5000);
    }

    public QrSessionClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Extracts the session id from a scanned QR payload.
     *
     * <p>Accepts the canonical {@code silentauth://qr-session/<id>} form and a
     * bare id, because a bank may render either. Anything else is refused: this
     * string is what authorises the claim, so it is never guessed at.</p>
     *
     * @throws IOException when the payload is not a session reference
     */
    public static String parsePayload(String scanned) throws IOException {
        if (scanned == null || scanned.isBlank()) {
            throw new IOException("empty QR payload");
        }
        String value = scanned.trim();
        if (value.regionMatches(true, 0, PAYLOAD_SCHEME, 0, PAYLOAD_SCHEME.length())) {
            value = value.substring(PAYLOAD_SCHEME.length());
        } else {
            int slash = value.lastIndexOf('/');
            if (slash >= 0 && slash < value.length() - 1) {
                value = value.substring(slash + 1);
            }
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        if (value.isBlank() || !isSessionId(value)) {
            throw new IOException("not a silent-auth QR payload: " + scanned);
        }
        return value;
    }

    /** A session id is a UUID; nothing else may be claimed. */
    static boolean isSessionId(String value) {
        if (value.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = value.charAt(i);
            boolean dash = (i == 8 || i == 13 || i == 18 || i == 23);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (dash ? c != '-' : !hex) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------- claim

    /**
     * Claims the hand-off over the given connection.
     *
     * @param sessionId   id from {@link #parsePayload(String)}
     * @param accessTech  bearer the device observed; required to be cellular
     * @param requirement what the app demands before spending the round trip
     * @param msisdn      claimed number, for the discovery variant (nullable)
     * @param operatorToken GSMA TS.43 temporary token, for the Wi-Fi variant (nullable)
     * @param apiKeyNullable sent as {@code X-Api-Key} when set
     * @param connector   must be cellular-bound for the IP-match hand-off
     * @return the server's verdict, never a locally-guessed one
     */
    public ClaimResult claim(String sasBaseUrl,
                             String sessionId,
                             AccessTech accessTech,
                             CellularRequirement requirement,
                             String msisdn,
                             String operatorToken,
                             String apiKeyNullable,
                             Connector connector) throws IOException {
        boolean tokenPath = operatorToken != null && !operatorToken.isBlank();
        if (!tokenPath) {
            // No TS.43 token: the proof has to be positional, so the server will
            // demand a cellular bearer. check() is fail-closed — ANY lets a Wi-Fi
            // phone spend the round trip and be turned away, which is the app's
            // own choice to make.
            CellularRequirement need = requirement == null
                    ? CellularRequirement.CELLULAR : requirement;
            need.check(accessTech == null ? AccessTech.UNKNOWN : accessTech);
        }
        URL url = URI.create(trimTrailingSlash(sasBaseUrl)
                + "/qr-session/v1/" + sessionId + "/claim").toURL();
        JsonReader.Result result = exchange(connector.open(url), url, "POST", claimBody(
                msisdn, accessTech, operatorToken), apiKeyNullable, null);
        return new ClaimResult(result.get("sessionId"),
                stateOf(result.get("state")),
                result.bool("devicePhoneNumberVerified"),
                result.get("fallbackReason"));
    }

    // ------------------------------------------------------------------- status

    /** Reads the verdict the bank is polling for. */
    public StatusResult status(String sasBaseUrl, String sessionId,
                               String apiKeyNullable, Connector connector) throws IOException {
        URL url = URI.create(trimTrailingSlash(sasBaseUrl)
                + "/qr-session/v1/" + sessionId).toURL();
        JsonReader.Result result = exchange(connector.open(url), url, "GET", null, apiKeyNullable, null);
        return new StatusResult(result.get("sessionId"),
                stateOf(result.get("state")),
                result.bool("devicePhoneNumberVerified"),
                result.get("fallbackReason"),
                result.get("decision"),
                result.number("expiresInSeconds"));
    }

    // ------------------------------------------------------------------ helpers

    private static QrState stateOf(String raw) {
        if (raw == null) {
            return QrState.UNKNOWN;
        }
        try {
            return QrState.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notInContract) {
            return QrState.UNKNOWN;
        }
    }

    static String claimBody(String msisdn, AccessTech accessTech, String operatorToken) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        if (msisdn != null && !msisdn.isBlank()) {
            sb.append("\"msisdn\":").append(Json.quote(msisdn));
            first = false;
        }
        if (accessTech != null && accessTech != AccessTech.UNKNOWN) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"accessTech\":").append(Json.quote(accessTech.name()));
            first = false;
        }
        if (operatorToken != null && !operatorToken.isBlank()) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"operatorToken\":").append(Json.quote(operatorToken));
        }
        return sb.append('}').toString();
    }

    private JsonReader.Result exchange(URLConnection raw, URL url, String method, String body,
                                 String apiKeyNullable, String accessTechOverride)
            throws IOException {
        HttpURLConnection conn = asHttp(raw, url);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("Accept", MediaTypeJson);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", MediaTypeJson);
        }
        if (accessTechOverride != null) {
            conn.setRequestProperty("X-Sas-Access-Tech", accessTechOverride);
        }
        if (apiKeyNullable != null && !apiKeyNullable.isBlank()) {
            conn.setRequestProperty("X-Api-Key", apiKeyNullable);
        }
        if (body != null) {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(stream);
        conn.disconnect();
        return JsonReader.Result.of(status, text);
    }

    private static final String MediaTypeJson = "application/json";

    private static HttpURLConnection asHttp(URLConnection conn, URL url) throws IOException {
        if (conn instanceof HttpURLConnection http) {
            return http;
        }
        throw new IOException("connector returned " + conn.getClass().getName()
                + " for " + url + ", expected an HTTP connection");
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static String trimTrailingSlash(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/") && baseUrl.length() > 1
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }
}
