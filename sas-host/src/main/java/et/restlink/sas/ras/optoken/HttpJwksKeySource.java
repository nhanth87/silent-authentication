/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.optoken;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;

import et.restlink.sas.oauth.OperatorTokenPorts;
import et.restlink.sas.token.Es256Keys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Outbound JWKS fetch for trusted operator-token issuers ({@code jwks_uri}). The only
 * HTTP client in the CAMARA binary, hence under {@code /ras/} (gate H24).
 *
 * <p>Budget 2 s connect + 2 s read, 64 KiB cap, HTTPS only unless
 * {@code sas.operator-token.allow-http-jwks=true} (lab). The anchor caches results, so
 * this runs once per issuer per cache period. Any failure returns an empty list —
 * the issuer is simply untrusted until the next successful fetch (fail-closed).</p>
 */
@ApplicationScoped
public class HttpJwksKeySource implements OperatorTokenPorts.KeySource {

    private static final Logger LOG = LogManager.getLogger(HttpJwksKeySource.class);

    static final int MAX_BYTES = 64 * 1024;
    static final Duration BUDGET = Duration.ofSeconds(2);

    @Inject
    @ConfigProperty(name = "sas.operator-token.allow-http-jwks", defaultValue = "false")
    boolean allowHttp;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(BUDGET)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public List<ECKey> fetch(URI jwksUri) {
        if (jwksUri == null) {
            return List.of();
        }
        String scheme = jwksUri.getScheme() == null ? "" : jwksUri.getScheme().toLowerCase();
        if (!"https".equals(scheme) && !("http".equals(scheme) && allowHttp)) {
            LOG.error("[SAS] refusing non-HTTPS jwks_uri {}", jwksUri);
            return List.of();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(jwksUri).timeout(BUDGET)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    LOG.warn("[SAS] jwks_uri {} answered HTTP {}", jwksUri, resp.statusCode());
                    return List.of();
                }
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) {
                    LOG.warn("[SAS] jwks_uri {} exceeds {} bytes", jwksUri, MAX_BYTES);
                    return List.of();
                }
                List<ECKey> keys = Es256Keys.parseSet(new String(body, StandardCharsets.UTF_8),
                        KeyUse.SIGNATURE);
                LOG.info("[SAS] fetched {} operator-token key(s) from {}", keys.size(), jwksUri);
                return keys;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.warn("[SAS] jwks_uri {} fetch failed: {}", jwksUri, e.toString());
            return List.of();
        }
    }
}
