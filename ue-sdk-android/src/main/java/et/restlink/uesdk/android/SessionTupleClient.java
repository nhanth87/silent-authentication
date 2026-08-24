/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Posts a {@link TupleSnapshot} to the SAS {@code POST /session-tuple}
 * endpoint over {@link HttpURLConnection}.
 *
 * <p>Body shape mirrors the server DTO ({@code srcIp, srcPort, ts, msisdn,
 * imsi}); null fields are omitted. Run {@link #post} off the main thread —
 * this is blocking network I/O.</p>
 */
public final class SessionTupleClient {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public SessionTupleClient() {
        this(3000, 3000);
    }

    public SessionTupleClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Returns the HTTP status code; throws {@link IOException} on transport failure.
     */
    public int post(String sasBaseUrl, TupleSnapshot snapshot,
                    String apiKeyNullable) throws IOException {
        URL url = new URL(trimTrailingSlash(sasBaseUrl) + "/session-tuple");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKeyNullable != null && apiKeyNullable.trim().length() > 0) {
            conn.setRequestProperty("X-Api-Key", apiKeyNullable);
        }
        byte[] body = body(snapshot).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int status = conn.getResponseCode();
        InputStream errorStream = conn.getErrorStream();
        if (errorStream != null) {
            try {
                errorStream.close();
            } catch (IOException ignored) {
                // best-effort drain
            }
        }
        conn.disconnect();
        return status;
    }

    static String trimTrailingSlash(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/") && baseUrl.length() > 1
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    static String body(TupleSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        if (snapshot.srcIp() != null) {
            sb.append("\"srcIp\":").append(Json.quote(snapshot.srcIp()));
            first = false;
        }
        if (snapshot.srcPort() != null) {
            appendComma(sb, first);
            sb.append("\"srcPort\":").append(snapshot.srcPort().intValue());
            first = false;
        }
        appendComma(sb, first);
        sb.append("\"ts\":").append(snapshot.ts());
        first = false;
        if (snapshot.claimedMsisdn() != null) {
            appendComma(sb, first);
            sb.append("\"msisdn\":").append(Json.quote(snapshot.claimedMsisdn()));
            first = false;
        }
        if (snapshot.imsi() != null) {
            appendComma(sb, first);
            sb.append("\"imsi\":").append(Json.quote(snapshot.imsi()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendComma(StringBuilder sb, boolean first) {
        if (!first) {
            sb.append(',');
        }
    }
}
