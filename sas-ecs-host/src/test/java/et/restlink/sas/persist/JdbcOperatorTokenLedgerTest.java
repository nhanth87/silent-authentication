/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persist;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real ECS V1 migration and the real INSERT … ON CONFLICT DO NOTHING against H2 in
 * PostgreSQL mode — the same dialect the lab dist boots with — so the "first INSERT
 * wins" guarantee is proven on SQL, not assumed.
 */
class JdbcOperatorTokenLedgerTest {

    private JdbcOperatorTokenLedger ledger;
    private JdbcDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V1__ecs_operator_token_ledger.sql"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        }
        ledger = new JdbcOperatorTokenLedger();
        ledger.dataSource = ds;
    }

    @Test
    void firstUseWinsOnce() {
        long exp = System.currentTimeMillis() / 1000L + 300;
        assertTrue(ledger.consume("https://ecs", "jti-1", exp));
        assertFalse(ledger.consume("https://ecs", "jti-1", exp), "replay");
    }

    @Test
    void sameJtiFromAnotherIssuerIsIndependent() {
        long exp = System.currentTimeMillis() / 1000L + 300;
        assertTrue(ledger.consume("https://ecs-a", "jti-1", exp));
        assertTrue(ledger.consume("https://ecs-b", "jti-1", exp));
    }

    @Test
    void secondLedgerInstanceSharingTheDatabaseAlsoRefuses() {
        long exp = System.currentTimeMillis() / 1000L + 300;
        JdbcOperatorTokenLedger other = new JdbcOperatorTokenLedger();
        other.dataSource = ds;
        assertTrue(ledger.consume("https://ecs", "jti-x", exp));
        assertFalse(other.consume("https://ecs", "jti-x", exp), "cluster-wide single use");
    }

    @Test
    void databaseErrorFailsClosed() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE ecs_operator_token_jti");
        }
        assertFalse(ledger.consume("https://ecs", "jti-2", System.currentTimeMillis() / 1000L + 300));
    }

    @Test
    void blankInputRefused() {
        assertFalse(ledger.consume("https://ecs", " ", 0));
        assertFalse(ledger.consume(null, "j", 0));
    }
}
