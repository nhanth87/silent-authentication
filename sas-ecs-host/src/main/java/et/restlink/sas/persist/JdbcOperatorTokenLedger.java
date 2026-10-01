/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persist;

import et.restlink.sas.entitlement.OperatorTokenLedger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The entitlement server's durable single-use ledger (layered ADR D7). Redemption is
 * one {@code INSERT … ON CONFLICT DO NOTHING}: the first insert of a key wins on every
 * instance sharing the database. A database error refuses the token (fail closed).
 */
@ApplicationScoped
public class JdbcOperatorTokenLedger implements OperatorTokenLedger {

    private static final Logger LOG = LogManager.getLogger(JdbcOperatorTokenLedger.class);

    static final int PURGE_EVERY = 256;

    /** Keep a row this long past token expiry (clock skew between instances). */
    static final long GRACE_SECONDS = 120L;

    private static final String INSERT =
            "INSERT INTO ecs_operator_token_jti (token_key, issuer, expires_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT DO NOTHING";
    private static final String PURGE = "DELETE FROM ecs_operator_token_jti WHERE expires_at < ?";

    @Inject
    DataSource dataSource;

    private final AtomicLong calls = new AtomicLong();

    @Override
    public boolean consume(String issuer, String jti, long expiresAtEpochSec) {
        if (issuer == null || jti == null || jti.isBlank()) {
            return false;
        }
        try (Connection c = dataSource.getConnection()) {
            int inserted;
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                ps.setString(1, key(issuer, jti));
                ps.setString(2, issuer.length() > 512 ? issuer.substring(0, 512) : issuer);
                ps.setLong(3, expiresAtEpochSec + GRACE_SECONDS);
                inserted = ps.executeUpdate();
            }
            if (calls.incrementAndGet() % PURGE_EVERY == 0) {
                try (PreparedStatement ps = c.prepareStatement(PURGE)) {
                    ps.setLong(1, System.currentTimeMillis() / 1000L);
                    ps.executeUpdate();
                }
            }
            return inserted == 1;
        } catch (SQLException e) {
            LOG.error("[ECS] operator-token ledger unavailable — refusing the token: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean durable() {
        return true;
    }

    static String key(String issuer, String jti) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((issuer + "\u0000" + jti).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
