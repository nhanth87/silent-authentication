/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads the durable CSV CDR files produced by the {@code SAS_CDR} log4j appender.
 * Active file is {@code logs/sas.cdr}; rolled files are {@code sas-*.cdr}.
 * Used by the dashboard to surface the newest file's first lines.
 */
@ApplicationScoped
public class CdrFileReader {

    private static final Logger LOG = LogManager.getLogger(CdrFileReader.class);

    /** Parsed CSV columns, in {@link SasCdrService#CSV_HEADER} order. */
    public record CdrRow(
            String time,
            String correlationId,
            String msisdn,
            String operation,
            String status,
            String detail,
            String user,
            String connector,
            String tenantId) {}

    private final String logDir;

    public CdrFileReader(@ConfigProperty(name = "sas.log.dir", defaultValue = "logs") String logDir) {
        this.logDir = logDir;
    }

    CdrFileReader() {
        this("logs");
    }

    /** Newest CDR file (active sas.cdr wins on equal mtime), or null. */
    public Path latest() {
        Path dir = Path.of(logDir);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path active = dir.resolve("sas.cdr");
        List<Path> candidates = new ArrayList<>();
        if (Files.isRegularFile(active)) {
            candidates.add(active);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("sas-") && name.endsWith(".cdr") && Files.isRegularFile(p);
            }).forEach(candidates::add);
        } catch (IOException e) {
            LOG.warn("[cdr-file] list failed in {}: {}", logDir, e.toString());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator
                .comparingLong((Path p) -> lastModified(p))
                .thenComparing(p -> p.getFileName().toString().equals("sas.cdr") ? 1 : 0));
        return candidates.get(candidates.size() - 1);
    }

    /** First {@code limit} parsed rows of the newest file (header line skipped). */
    public List<CdrRow> head(int limit) {
        Path file = latest();
        if (file == null) {
            return Collections.emptyList();
        }
        int cap = Math.max(1, limit);
        List<CdrRow> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                if (out.isEmpty() && line.startsWith("time,")) {
                    continue;
                }
                CdrRow row = parse(line);
                if (row != null) {
                    out.add(row);
                }
                if (out.size() >= cap) {
                    break;
                }
            }
        } catch (IOException e) {
            LOG.warn("[cdr-file] read {} failed: {}", file, e.toString());
        }
        return out;
    }

    static CdrRow parse(String line) {
        List<String> fields = split(line);
        if (fields.size() < 5) {
            return null;
        }
        return new CdrRow(
                fields.get(0),
                fields.get(1),
                fields.get(2),
                fields.get(3),
                fields.get(4),
                fields.size() > 5 ? fields.get(5) : null,
                fields.size() > 6 ? fields.get(6) : null,
                fields.size() > 7 ? fields.get(7) : null,
                fields.size() > 8 ? fields.get(8) : null);
    }

    /** RFC-4180 split: handles quoted fields with embedded commas/quotes/newlines escaped as "". */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"' && cur.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}