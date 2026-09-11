/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OAuthAssertionReplayGuard {

    static final long TTL_SECONDS = 600L;

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public boolean useOnce(String namespace, String jti) {
        if (namespace == null || namespace.isBlank() || jti == null || jti.isBlank()) {
            return false;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        evictExpired(nowSec);
        String key = namespace + "|" + jti;
        Long previous = seen.putIfAbsent(key, nowSec + TTL_SECONDS);
        return previous == null;
    }

    private void evictExpired(long nowSec) {
        seen.values().removeIf(deadline -> nowSec >= deadline);
    }
}
