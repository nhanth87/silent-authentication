/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The V2 migration exists and adds every full-flow column (string assertions;
 * the SQL runs for real against H2 at Quarkus boot).
 */
class CdrMigrationV2Test {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V2__cdr_flow_columns.sql");

    private static String migrationSql() throws IOException {
        assertTrue(Files.isRegularFile(MIGRATION), "V2 migration file missing: " + MIGRATION);
        return Files.readString(MIGRATION);
    }

    private static void assertColumn(String sql, String name, String type) {
        assertTrue(sql.contains(name + " " + type),
                "expected column declaration '" + name + " " + type + "'");
    }

    @Test
    void v2AddsAllFlowColumns() throws IOException {
        String sql = migrationSql();
        assertColumn(sql, "verified", "BOOLEAN");
        assertColumn(sql, "decision", "VARCHAR(16)");
        assertColumn(sql, "score", "INTEGER");
        assertColumn(sql, "threshold", "INTEGER");
        assertColumn(sql, "assurance_level", "VARCHAR(24)");
        assertColumn(sql, "risk_class", "VARCHAR(16)");
        assertColumn(sql, "access_tech", "VARCHAR(12)");
        assertColumn(sql, "fallback_reason", "VARCHAR(48)");
        assertColumn(sql, "resolver_status", "VARCHAR(32)");
        assertColumn(sql, "evidence_source", "VARCHAR(32)");
        assertColumn(sql, "evidence_json", "TEXT");
        assertColumn(sql, "total_ms", "INTEGER");
    }

    @Test
    void v2OnlyAltersTheExistingCdrTable() throws IOException {
        String sql = migrationSql();
        assertFalse(sql.toUpperCase().contains("CREATE TABLE"), "V2 must not create tables");
        assertFalse(sql.toUpperCase().contains("DROP "), "V2 must not drop anything");
        assertTrue(sql.contains("sas_cdr_session"));
    }
}
