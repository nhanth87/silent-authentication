/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.web;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Startup log of the admin UI mount point and the page files it found. */
@ApplicationScoped
public class SasAdminUiBoot {

    private static final Logger LOG = LogManager.getLogger(SasAdminUiBoot.class);

    void onStart(@Observes StartupEvent ev) {
        Path dir = Path.of(System.getProperty("user.dir", ".")).resolve("app").resolve("html").resolve("admin")
                .toAbsolutePath().normalize();
        List<String> pages = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                pages = stream.filter(p -> p.getFileName().toString().endsWith(".html"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
            } catch (IOException e) {
                LOG.warn("[admin-ui] cannot list {}: {}", dir, e.toString());
            }
        }
        LOG.info("[admin-ui] dashboard mounted at /admin; uiDir={}; pages={}",
                dir, pages.isEmpty() ? "none found" : pages);
    }
}