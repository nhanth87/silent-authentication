/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Device side of the desktop → phone QR hand-off for CAMARA Number
 * Verification v2.
 *
 * <p><strong>The flow.</strong> A customer signs in to internet banking on a
 * desktop. That machine has no SIM, so the cellular IP-match path cannot help
 * them. The bank shows a QR code; the phone scans it and calls {@link #claim} on
 * this SDK; the desktop polls {@code GET /qr-session/{id}}. From that moment
 * the phone is the device proving the number.</p>
 *
 * <p><strong>Why this is on the phone and not the browser.</strong> Android can
 * pin a socket to the cellular {@code Network}
 * ({@code Network.openConnection(URL)}); a browser cannot pin anything, and
 * neither can the desktop. That asymmetry is the whole reason the hand-off
 * exists, and why the claim must go through a {@link CellularBearer}.</p>
 *
 * <p><strong>Run it off the main thread.</strong> {@link #claim} and
 * {@link #status} are blocking I/O — Android will throw
 * {@code NetworkOnMainThreadException} on the UI thread. There is deliberately
 * no executor in this SDK: the hosting bank app owns its threading, and a
 * library that spawns one is a library that leaks.</p>
 *
 * <p><strong>Fail-closed.</strong> {@link CellularRequirement#check} refuses a
 * non-cellular bearer before the request is built, and the server refuses one
 * too. A phone on Wi-Fi either carries a TS.43 temporary token (the Wi-Fi
 * variant) or must fall back to OTP — it is never quietly accepted.</p>
 */
public final class QrSessionClient {

    /** Scheme the bank puts in the QR image. */
    public static final String PAYLOAD_SCHEME = "silentauth://qr-session/";

    /** Lifecycle as the server sees it. */
    public enum QrState {
        PENDING,
        RESOLVED,
        EXPIRED,
        UNKNOWN
    }

    /**
     * The verdict of one hand-off.
     *
     * @param verified {@code true} only when the server explicitly approved;
     *                 {@code null} while still pending
     */
    public static final class ClaimResult {
        public final String sessionId;
        public final QrState state;
        public final Boolean verified;
        public final String fallbackReason;

        ClaimResult(String sessionId, QrState state, Boolean verified, String fallbackReason) {
            this.sessionId = sessionId;
            this.state = state;
            this.verified = verified;
            this.fallbackReason = fallbackReason;
        }

        /**
         * True only for an explicit approval. A pending or unknown state reads as
         * false: the bank must never treat silence as consent.
         */
        public boolean approved() {
            return Boolean.TRUE.equals(verified) && state == QrState.RESOLVED;
        }
    }

    /** Status as the bank polls it. */
    public static final class StatusResult {
        public final String sessionId;
        public final QrState state;
        public final Boolean verified;
        public final String fallbackReason;
        public final String decision;
        public final long expiresInSeconds;

        StatusResult(String sessionId, QrState state, Boolean verified, String fallbackReason,
                     String decision, long expiresInSeconds) {
            this.sessionId = sessionId;
            this.state = state;
            this.verified = verified;
            this.fallbackReason = fallbackReason;
            this.decision = decision;
            this.expiresInSeconds = expiresInSeconds;
        }

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
     * bare id, because a bank may render either. This string authorises the
     * claim, so anything else is refused rather than parsed hopefully.</p>
     *
     * @throws IOException when the payload is not a session reference
     */
    public static String parsePayload(String scanned) throws IOException {
        if (scanned == null || scanned.trim().length() == 0) {
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
        if (!isSessionId(value)) {
            throw new IOException("not a silent-auth QR payload: " + scanned);
        }
        return value;
    }

    /** A session id is a UUID; nothing else may be claimed. */
    static boolean isSessionId(String value) {
        if (value == null || value.length() != 36) {
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
     * Claims the hand-off over the cellular bearer.
     *
     * @param bearer   a {@link CellularBearer} bound to the cellular {@code Network};
     *                 anything else leaves the socket on the default route and the
     *                 server will refuse the claim
     * @param accessTech the radio the bearer reports; also sent as the declaration
     * @throws CellularUnavailableException when the bearer cannot satisfy the
     *         requirement — the app must fall back to OTP
     */
    public ClaimResult claim(String sasBaseUrl, String sessionId, CellularBearer bearer,
                             CellularRequirement requirement, String msisdn,
                             String operatorToken, String apiKeyNullable) throws IOException {
        boolean tokenPath = operatorToken != null && operatorToken.trim().length() > 0;
        AccessTech tech = bearer == null ? AccessTech.UNKNOWN : bearer.accessTech();
        if (!tokenPath) {
            CellularRequirement need = requirement == null
                    ? CellularRequirement.CELLULAR : requirement;
            need.check(tech);
        }
        String body = claimBody(msisdn, tokenPath ? AccessTech.WIFI : tech, operatorToken);
        URL url = new URL(trimTrailingSlash(sasBaseUrl) + "/qr-session/v1/" + sessionId + "/claim");
        Flat json = exchange(url, bearer, "POST", body, apiKeyNullable, tech);
        return new ClaimResult(json.string("sessionId"), stateOf(json.string("state")),
                json.bool("devicePhoneNumberVerified"), json.string("fallbackReason"));
    }

    // ------------------------------------------------------------------- status

    /** Reads the verdict the bank is polling for. */
    public StatusResult status(String sasBaseUrl, String sessionId, Connector connector,
                               String apiKeyNullable) throws IOException {
        URL url = new URL(trimTrailingSlash(sasBaseUrl) + "/qr-session/v1/" + sessionId);
        Flat json = exchange(url, connector, "GET", null, apiKeyNullable, null);
        return new StatusResult(json.string("sessionId"), stateOf(json.string("state")),
                json.bool("devicePhoneNumberVerified"), json.string("fallbackReason"),
                json.string("decision"), json.number("expiresInSeconds"));
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
        if (msisdn != null && msisdn.trim().length() > 0) {
            sb.append("\"msisdn\":").append(Json.quote(msisdn));
            first = false;
        }
        if (accessTech != null && accessTech != AccessTech.UNKNOWN) {
            appendComma(sb, first);
            sb.append("\"accessTech\":").append(Json.quote(accessTech.name()));
            first = false;
        }
        if (operatorToken != null && operatorToken.trim().length() > 0) {
            appendComma(sb, first);
            sb.append("\"operatorToken\":").append(Json.quote(operatorToken));
        }
        sb.append('}');
        return sb.toString();
    }

    private Flat exchange(URL url, Connector connector, String method, String body,
                          String apiKeyNullable, AccessTech declared) throws IOException {
        URLConnection raw = (connector == null ? Connector.DEFAULT : connector).open(url);
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("connector returned " + raw.getClass().getName()
                    + " for " + url + ", expected an HTTP connection");
        }
        HttpURLConnection conn = (HttpURLConnection) raw;
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
        }
        if (declared != null && declared != AccessTech.UNKNOWN) {
            conn.setRequestProperty("X-Sas-Access-Tech", declared.name());
        }
        if (apiKeyNullable != null && apiKeyNullable.trim().length() > 0) {
            conn.setRequestProperty("X-Api-Key", apiKeyNullable);
        }
        if (body != null) {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        String text = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        return Flat.of(text);
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

    private static void appendComma(StringBuilder sb, boolean first) {
        if (!first) {
            sb.append(',');
        }
    }

    /**
     * Flat scalar fields of a JSON object.
     *
     * <p>Deliberately tiny and deliberately unforgiving: anything that is not a
     * single-level object of scalars comes back empty, so an unreadable answer
     * reads as "no verdict" rather than as a half-parsed approval. The device SDK
     * never guesses a boolean out of a malformed body.</p>
     *
     * <p>Hand-rolled because this artifact compiles without {@code android.jar},
     * so neither {@code org.json} nor a JSON library is on its classpath — and a
     * library that ships inside a bank app is a dependency the bank must audit.
     * The surface here is exactly the fields the SAS returns, all scalars.</p>
     */
    static final class Flat {

        private final java.util.Map<String, String> fields;

        private Flat(java.util.Map<String, String> fields) {
            this.fields = fields;
        }

        static Flat of(String body) {
            java.util.Map<String, String> fields =
                    new java.util.LinkedHashMap<String, String>();
            if (body == null) {
                return new Flat(fields);
            }
            String s = body.trim();
            int i = 0;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) != '{') {
                return new Flat(fields);            // not an object: no verdict
            }
            i++;
            while (true) {
                while (i < s.length() && (Character.isWhitespace(s.charAt(i))
                        || s.charAt(i) == ',')) {
                    i++;
                }
                if (i >= s.length()) {
                    return new Flat(fields);        // truncated: refuse
                }
                if (s.charAt(i) == '}') {
                    return new Flat(fields);
                }
                if (s.charAt(i) != '"') {
                    return new Flat(new java.util.HashMap<String, String>());
                }
                Scan key = readString(s, i);
                if (key == null) {
                    return new Flat(new java.util.HashMap<String, String>());
                }
                i = skip(s, key.next);
                if (i >= s.length() || s.charAt(i) != ':') {
                    return new Flat(new java.util.HashMap<String, String>());
                }
                i = skip(s, i + 1);
                Scan value = readScalar(s, i);
                if (value == null) {
                    return new Flat(new java.util.HashMap<String, String>());
                }
                fields.put(key.value, value.value);
                i = value.next;
            }
        }

        private static int skip(String s, int from) {
            int i = from;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i;
        }

        /** One scanned token: its text and the index just past it. */
        private static final class Scan {
            final String value;
            final int next;

            Scan(String value, int next) {
                this.value = value;
                this.next = next;
            }
        }

        private static Scan readString(String s, int from) {
            if (from >= s.length() || s.charAt(from) != '"') {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from + 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    return new Scan(sb.toString(), i + 1);
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i + 1 >= s.length()) {
                    return null;
                }
                char esc = s.charAt(++i);
                // Java 8 source level: no switch rules here.
                switch (esc) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    default:
                        sb.append(esc);
                        break;
                }
            }
            return null;
        }

        private static Scan readScalar(String s, int from) {
            if (from >= s.length()) {
                return null;
            }
            char c = s.charAt(from);
            if (c == '"') {
                return readString(s, from);
            }
            if (c == '{' || c == '[') {
                return null;                        // nested: out of scope
            }
            int i = from;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            if (i <= from) {
            return null;
        }
        String token = s.substring(from, i).trim();
        // JSON null is an absent value, not the four characters "null"
        return "null".equals(token) ? new Scan(null, i) : new Scan(token, i);
        }

        String string(String field) {
            return fields.get(field);
        }

        Boolean bool(String field) {
            String raw = fields.get(field);
            if (raw == null) {
                return null;
            }
            if ("true".equalsIgnoreCase(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return Boolean.FALSE;
            }
            return null;
        }

        long number(String field) {
            String raw = fields.get(field);
            if (raw == null) {
                return -1L;
            }
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException notANumber) {
                return -1L;
            }
        }
    }
}
