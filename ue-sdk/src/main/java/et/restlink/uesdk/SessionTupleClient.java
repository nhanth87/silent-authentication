/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Posts a {@link SessionTupleCollector.TupleSnapshot} to the SAS
 * {@code POST /session-tuple} endpoint over {@link HttpURLConnection}.
 *
 * <p>Body shape mirrors the server DTO ({@code srcIp, srcPort, ts, msisdn,
 * imsi}); null fields are omitted.</p>
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
    public int post(String sasBaseUrl, SessionTupleCollector.TupleSnapshot snapshot,
                    String apiKeyNullable) throws IOException {
        URL url = URI.create(trimTrailingSlash(sasBaseUrl) + "/session-tuple").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKeyNullable != null && !apiKeyNullable.isBlank()) {
            conn.setRequestProperty("X-Api-Key", apiKeyNullable);
        }
        byte[] body = body(snapshot).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int status = conn.getResponseCode();
        try {
            conn.getErrorStream().close();
        } catch (IOException | RuntimeException ignored) {
            // best-effort drain
        }
        conn.disconnect();
        return status;
    }

    static String trimTrailingSlash(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/") && baseUrl.length() > 1
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    static String body(SessionTupleCollector.TupleSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        if (snapshot.srcIp() != null) {
            sb.append("\"srcIp\":").append(Json.quote(snapshot.srcIp()));
            first = false;
        }
        if (snapshot.srcPort() != null) {
            appendComma(sb, first);
            sb.append("\"srcPort\":").append(snapshot.srcPort());
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
