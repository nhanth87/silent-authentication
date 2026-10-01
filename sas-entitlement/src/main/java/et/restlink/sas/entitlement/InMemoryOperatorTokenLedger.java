/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-process ledger: correct for one ECS instance, lost on restart. Lab and tests only;
 * the durable JDBC ledger moves here from sas-camara in migration step M3, and prod
 * preflight refuses redemption on a non-durable ledger.
 */
public final class InMemoryOperatorTokenLedger implements OperatorTokenLedger {

    /** Hard cap; expired entries are dropped first. Beyond it, refuse (fail closed). */
    static final int MAX_ENTRIES = 100_000;

    private final Map<String, Long> spent = new ConcurrentHashMap<>();

    @Override
    public boolean consume(String issuer, String jti, long expiresAtEpochSec) {
        if (issuer == null || jti == null) {
            return false;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (spent.size() >= MAX_ENTRIES) {
            spent.values().removeIf(exp -> exp < nowSec);
            if (spent.size() >= MAX_ENTRIES) {
                return false;
            }
        }
        return spent.putIfAbsent(issuer + '|' + jti, expiresAtEpochSec) == null;
    }

    @Override
    public boolean durable() {
        return false;
    }

    int size() {
        return spent.size();
    }
}
