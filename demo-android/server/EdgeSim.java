/*
 * Android emulator demo — Restlink (Ethiopia).
 * Edge proxy simulator in front of sas-camara. LAB ONLY — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Stands in for the operator's edge proxy. The emulator NATs every interface
 * (cellular and Wi-Fi) out of the host as 127.0.0.1, so sas-camara cannot tell
 * the subscriber from the connection. In production the edge proxy writes the
 * real post-CGNAT tuple into X-Sas-Src-Ip/Port; here every request is stamped
 * with the tuple the lab resolver binds to +251911111111.
 *
 * <pre>java EdgeSim.java [listenPort=8084] [upstream=http://127.0.0.1:8085]</pre>
 *
 * Redirects are passed through untouched: the device SDK must see the 302 to
 * redirect_uri and catch it itself.
 */
public class EdgeSim {

    static final String SRC_IP = System.getProperty("edge.srcIp", "10.20.30.40");
    static final String SRC_PORT = System.getProperty("edge.srcPort", "55555");
    /** Hop-by-hop and HttpClient-restricted headers, never forwarded. */
    static final Set<String> SKIP = Set.of("host", "connection", "content-length", "expect", "upgrade",
            "keep-alive", "transfer-encoding", "te", "x-sas-src-ip", "x-sas-src-port");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8084;
        String upstream = args.length > 1 ? args[1] : "http://127.0.0.1:8085";
        // HTTP/1.1: no h2c upgrade (101) on the wire, so the pcap reads as plain HTTP.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> forward(ex, http, upstream));
        server.start();
        System.out.println("[edge-sim] :" + port + " -> " + upstream + "  stamping X-Sas-Src-Ip=" + SRC_IP
                + " X-Sas-Src-Port=" + SRC_PORT);
    }

    static void forward(HttpExchange ex, HttpClient http, String upstream) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(upstream + ex.getRequestURI().toString()))
                    .timeout(Duration.ofSeconds(10))
                    .method(ex.getRequestMethod(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                if (!SKIP.contains(h.getKey().toLowerCase())) {
                    h.getValue().forEach(v -> b.header(h.getKey(), v));
                }
            }
            b.header("X-Sas-Src-Ip", SRC_IP).header("X-Sas-Src-Port", SRC_PORT);

            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            r.headers().map().forEach((k, v) -> {
                if (!SKIP.contains(k.toLowerCase()) && !k.startsWith(":")) {
                    ex.getResponseHeaders().put(k, v);
                }
            });
            // Path only: the query carries the code and state, which are never logged.
            System.out.println("[edge-sim] " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath()
                    + " -> " + r.statusCode());
            byte[] out = r.body();
            ex.sendResponseHeaders(r.statusCode(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("[edge-sim] upstream unreachable: " + e.getClass().getSimpleName());
            ex.sendResponseHeaders(502, -1);
        } finally {
            ex.close();
        }
    }
}
