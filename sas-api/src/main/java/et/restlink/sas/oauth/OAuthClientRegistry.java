/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OAuthClientRegistry {

    private static final Logger LOG = LogManager.getLogger(OAuthClientRegistry.class);

    @Inject
    OAuthServerConfig config;

    private volatile String cachedJson;
    private volatile List<OAuthClient> cachedClients = List.of();

    public Optional<OAuthClient> find(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        String needle = clientId.trim();
        return clients().stream()
                .filter(client -> needle.equals(client.clientId()))
                .findFirst();
    }

    public List<OAuthClient> clients() {
        String json = config == null ? null : config.clientsJson().orElse(null);
        String current = json == null ? "" : json;
        if (!current.equals(cachedJson)) {
            synchronized (this) {
                if (!current.equals(cachedJson)) {
                    cachedJson = current;
                    cachedClients = load(current);
                }
            }
        }
        return cachedClients;
    }

    private List<OAuthClient> load(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OAuthClient.parseRegistry(json);
        } catch (RuntimeException e) {
            LOG.error("[SAS] invalid sas.oauth.clients-json — no OAuth clients are usable: {}",
                    e.getMessage());
            return List.of();
        }
    }
}
