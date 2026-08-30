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
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Posts a {@link SessionTupleCollector.TupleSnapshot} to the SAS
 * {@code POST /session-tuple} endpoint over {@link HttpURLConnection}.
 *
 * <p>Body shape mirrors the server DTO ({@code srcIp, srcPort, ts, msisdn,
 * imsi, accessTech}); null fields are omitted. The same tuple is echoed as
 * {@code X-Sas-Access-Tech} so the SAS can log what the device claimed next to
 * what its own Resolver observes.</p>
 *
 * <p>Pass a {@link Connector} bound to the cellular bearer (see
 * {@code ue-sdk-android}'s {@code CellularBearer}) so the address the SAS sees
 * is the bearer's, not the Wi-Fi default route's.</p>
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
        return post(sasBaseUrl, snapshot, apiKeyNullable, Connector.DEFAULT);
    }

    /**
     * Cellular-aware variant: the request is opened through {@code connector},
     * so a platform implementation can pin it to the cellular bearer.
     */
    public int post(String sasBaseUrl, SessionTupleCollector.TupleSnapshot snapshot,
                    String apiKeyNullable, Connector connector) throws IOException {
        URL url = URI.create(trimTrailingSlash(sasBaseUrl) + "/session-tuple").toURL();
        HttpURLConnection conn = asHttp(connector.open(url), url);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (snapshot.accessTech() != null && snapshot.accessTech() != AccessTech.UNKNOWN) {
            conn.setRequestProperty("X-Sas-Access-Tech", snapshot.accessTech().name());
        }
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

    /**
     * HttpURLConnection is what the SAS contract needs (method, headers,
     * status). A bearer-bound platform connection must already be an
     * HttpURLConnection; anything else is a wiring bug, not a soft failure.
     */
    private static HttpURLConnection asHttp(URLConnection conn, URL url) throws IOException {
        if (conn instanceof HttpURLConnection http) {
            return http;
        }
        throw new IOException("connector returned " + conn.getClass().getName()
                + " for " + url + ", expected an HTTP connection");
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
            first = false;
        }
        if (snapshot.accessTech() != null && snapshot.accessTech() != AccessTech.UNKNOWN) {
            appendComma(sb, first);
            sb.append("\"accessTech\":").append(Json.quote(snapshot.accessTech().name()));
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
