/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import et.restlink.sas.persist.SasCdrEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdrDbFlusherRecentTest {

    private CdrDbFlusher flusher;

    @BeforeEach
    void wire() {
        flusher = new CdrDbFlusher();
        flusher.init();
    }

    @AfterEach
    void stop() {
        flusher.stop();
    }

    @Test
    void durableRecentFallsBackToQueuedRowsWhenDbReadIsUnavailable() {
        SasCdrEntity row = row("corr-mem", Instant.parse("2026-09-09T10:00:00Z"));
        flusher.enqueue(row);

        List<SasCdrEntity> rows = flusher.recentDurable(10);

        assertEquals(1, rows.size());
        assertSame(row, rows.get(0));
    }

    @Test
    void mergePrefersQueuedRowsAndSortsNewestFirst() {
        UUID sharedId = UUID.randomUUID();
        SasCdrEntity persistedOld = row("corr-old", Instant.parse("2026-09-09T09:00:00Z"));
        SasCdrEntity persistedShared = row(sharedId, "corr-shared", Instant.parse("2026-09-09T10:00:00Z"));
        SasCdrEntity queuedShared = row(sharedId, "corr-shared", Instant.parse("2026-09-09T10:00:00Z"));
        SasCdrEntity queuedNewest = row("corr-new", Instant.parse("2026-09-09T11:00:00Z"));

        List<SasCdrEntity> merged = CdrDbFlusher.merge(
                List.of(queuedShared, queuedNewest),
                List.of(persistedShared, persistedOld),
                10);

        assertEquals(3, merged.size());
        assertEquals("corr-new", merged.get(0).correlationId);
        assertEquals("corr-shared", merged.get(1).correlationId);
        assertSame(queuedShared, merged.get(1));
        assertEquals("corr-old", merged.get(2).correlationId);
    }

    @Test
    void mergeHonoursLimit() {
        List<SasCdrEntity> queued = List.of(
                row("corr-3", Instant.parse("2026-09-09T12:00:00Z")),
                row("corr-2", Instant.parse("2026-09-09T11:00:00Z")));
        List<SasCdrEntity> persisted = List.of(
                row("corr-1", Instant.parse("2026-09-09T10:00:00Z")));

        List<SasCdrEntity> merged = CdrDbFlusher.merge(queued, persisted, 2);

        assertEquals(2, merged.size());
        assertEquals("corr-3", merged.get(0).correlationId);
        assertEquals("corr-2", merged.get(1).correlationId);
        assertTrue(merged.stream().noneMatch(row -> "corr-1".equals(row.correlationId)));
    }

    private static SasCdrEntity row(String correlationId, Instant recordedAt) {
        return row(UUID.randomUUID(), correlationId, recordedAt);
    }

    private static SasCdrEntity row(UUID id, String correlationId, Instant recordedAt) {
        SasCdrEntity row = new SasCdrEntity();
        row.id = id;
        row.correlationId = correlationId;
        row.recordedAt = recordedAt;
        row.updatedAt = recordedAt;
        row.phase = "API";
        row.status = "COMPLETED";
        row.csvLine = correlationId;
        return row;
    }
}
