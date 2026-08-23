/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persist;

import et.restlink.sas.tenant.AdminUserService;
import et.restlink.sas.tenant.TenantService;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Flyway migration is driven by quarkus-flyway ({@code migrate-at-start=true});
 * this observer only verifies the required catalog exists, applies the baseline
 * as a last-resort fallback if needed, and seeds first-run rows.
 */
@ApplicationScoped
public class SasSchemaInitializer {
    private static final Logger LOG = LogManager.getLogger(SasSchemaInitializer.class);

    static final Set<String> REQUIRED_TABLES = Set.of(
            "sas_tenant", "sas_admin_user", "sas_app_user", "sas_config", "sas_cdr_session");
    static final String BASELINE = "db/migration/V1__sas_baseline.sql";

    @Inject DataSource dataSource;
    @Inject TenantService tenants;
    @Inject AdminUserService users;

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE + 100) StartupEvent ev) {
        ensureSchema();
        seed();
    }

    public void ensureSchema() {
        List<String> missing = findMissingTables();
        if (missing.isEmpty()) {
            LOG.info("[sas-schema] OK tables={}", REQUIRED_TABLES.size());
            return;
        }
        LOG.warn("[sas-schema] missing tables {} — applying baseline fallback", missing);
        applyBaselineFallback();
        missing = findMissingTables();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "[sas-schema] incomplete after auto-init; missing: " + missing);
        }
        LOG.info("[sas-schema] OK after baseline fallback");
    }

    void seed() {
        try {
            if (tenants.byId("lab").isEmpty()) {
                tenants.upsert("lab", "Lab", 1, true, null);
                LOG.info("[sas-schema] seeded tenant=lab networkId=1");
            }
        } catch (RuntimeException ex) {
            LOG.warn("[sas-schema] tenant seed skipped: {}", ex.toString());
        }
        try {
            if (users.byUsername("admin").isEmpty()) {
                users.create("admin", "admin", "ADMIN", null, "Administrator", true);
                LOG.info("[sas-schema] seeded admin user=admin role=ADMIN");
            }
        } catch (RuntimeException ex) {
            LOG.warn("[sas-schema] admin seed skipped: {}", ex.toString());
        }
    }

    private List<String> findMissingTables() {
        Set<String> present = loadPresentTables();
        List<String> missing = new ArrayList<>();
        for (String t : REQUIRED_TABLES) {
            if (!present.contains(t.toLowerCase(Locale.ROOT))) {
                missing.add(t);
            }
        }
        return missing;
    }

    private Set<String> loadPresentTables() {
        Set<String> names = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            collectTables(md, c.getCatalog(), c.getSchema(), names);
            if (names.isEmpty()) {
                collectTables(md, null, null, names);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("[sas-schema] cannot inspect tables", e);
        }
        return names;
    }

    private static void collectTables(DatabaseMetaData md, String catalog, String schema, Set<String> names)
            throws SQLException {
        try (ResultSet rs = md.getTables(catalog, schema, "%", new String[] {"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private void applyBaselineFallback() {
        try (Connection c = dataSource.getConnection()) {
            boolean prev = c.getAutoCommit();
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                for (String stmt : splitSql(readBaseline())) {
                    try {
                        st.execute(stmt);
                    } catch (SQLException ex) {
                        LOG.warn("[sas-schema] baseline skip: {}", ex.getMessage());
                    }
                }
            } finally {
                c.setAutoCommit(prev);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("[sas-schema] baseline fallback failed: " + e.getMessage(), e);
        }
    }

    private static String readBaseline() {
        try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BASELINE)) {
            if (in == null) {
                throw new IllegalStateException("classpath " + BASELINE + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + BASELINE, e);
        }
    }

    static List<String> splitSql(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            cur.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String s = cur.toString().trim();
                if (s.endsWith(";")) {
                    s = s.substring(0, s.length() - 1).trim();
                }
                if (!s.isEmpty()) {
                    out.add(s);
                }
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }
}