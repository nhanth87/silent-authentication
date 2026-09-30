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
 * Runs the real V3 migration and the real INSERT … ON CONFLICT DO NOTHING against H2 in
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
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V3__operator_token_ledger.sql"));
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
        assertTrue(ledger.firstUse("https://ecs", "jti-1", exp));
        assertFalse(ledger.firstUse("https://ecs", "jti-1", exp), "replay");
    }

    @Test
    void sameJtiFromAnotherIssuerIsIndependent() {
        long exp = System.currentTimeMillis() / 1000L + 300;
        assertTrue(ledger.firstUse("https://ecs-a", "jti-1", exp));
        assertTrue(ledger.firstUse("https://ecs-b", "jti-1", exp));
    }

    @Test
    void secondLedgerInstanceSharingTheDatabaseAlsoRefuses() {
        long exp = System.currentTimeMillis() / 1000L + 300;
        JdbcOperatorTokenLedger other = new JdbcOperatorTokenLedger();
        other.dataSource = ds;
        assertTrue(ledger.firstUse("https://ecs", "jti-x", exp));
        assertFalse(other.firstUse("https://ecs", "jti-x", exp), "cluster-wide single use");
    }

    @Test
    void databaseErrorFailsClosed() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE sas_operator_token_jti");
        }
        assertFalse(ledger.firstUse("https://ecs", "jti-2", System.currentTimeMillis() / 1000L + 300));
    }

    @Test
    void blankInputRefused() {
        assertFalse(ledger.firstUse("https://ecs", " ", 0));
        assertFalse(ledger.firstUse(null, "j", 0));
    }
}
