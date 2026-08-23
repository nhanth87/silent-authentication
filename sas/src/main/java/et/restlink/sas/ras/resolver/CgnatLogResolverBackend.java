/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CGNAT-log resolver backend with a live incremental tail (P2 missing item #1).
 *
 * <p>Parses CGNAT translation log lines of the form:
 * <pre>
 * ts_epoch_ms,src_ip,src_port,msisdn,imsi,bearer_age_ms
 * </pre>
 * A scheduled executor re-reads the file every {@code refreshIntervalMs}
 * starting from the last consumed byte offset; a truncated or rotated file
 * (size below the remembered offset) triggers a full re-read from byte 0.
 * Incomplete trailing lines are buffered until the newline arrives.
 * {@link #reload()} forces the same full re-read manually.</p>
 *
 * <p>Resolution is point-in-time and fail-closed: only rows with
 * {@code rowTs <= tsEpochMs} that also fall inside the staleness window
 * ({@code rowTs >= tsEpochMs - staleWindowMs}) count as candidates. No
 * candidate ⇒ {@link FallbackReason#NO_BINDING}; more than one distinct
 * MSISDN on the same IP:port ⇒ {@link FallbackReason#AMBIGUOUS_BINDING};
 * otherwise the most recent candidate row wins.</p>
 */
public final class CgnatLogResolverBackend implements ResolverBackend {

    private static final Logger LOG = LogManager.getLogger(CgnatLogResolverBackend.class);

    /** Default tail refresh interval. */
    public static final long DEFAULT_REFRESH_MS = 2_000L;
    /** Default point-in-time staleness window. */
    public static final long DEFAULT_STALE_MS = 60_000L;

    private record CgnatBinding(long tsEpochMs, String msisdn, String imsi, long bearerAgeMs) {}

    private final Path logPath;
    private final long refreshIntervalMs;
    private final long staleWindowMs;
    private final Map<String, List<CgnatBinding>> bindings = new ConcurrentHashMap<>();
    private volatile long fileOffset;
    private ScheduledExecutorService tailer;

    public CgnatLogResolverBackend(Path logPath) {
        this(logPath, DEFAULT_REFRESH_MS, DEFAULT_STALE_MS);
    }

    /**
     * @param logPath          CGNAT CSV log to tail.
     * @param refreshIntervalMs scheduled tail refresh interval.
     * @param staleWindowMs    staleness window around the requested timestamp.
     */
    public CgnatLogResolverBackend(Path logPath, long refreshIntervalMs, long staleWindowMs) {
        this.logPath = logPath;
        this.refreshIntervalMs = refreshIntervalMs;
        this.staleWindowMs = staleWindowMs;
    }

    /** Start the scheduled tailer (immediate first poll). */
    public synchronized void start() {
        if (tailer != null) {
            return;
        }
        tailer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cgnat-log-tailer");
            t.setDaemon(true);
            return t;
        });
        tailer.scheduleWithFixedDelay(this::pollSafely, 0L, refreshIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("CGNAT log tailer started: {} refresh={}ms stale={}ms", logPath, refreshIntervalMs, staleWindowMs);
    }

    /** Stop the tailer cleanly. */
    public synchronized void stop() {
        if (tailer == null) {
            return;
        }
        tailer.shutdownNow();
        try {
            tailer.awaitTermination(2_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tailer = null;
        LOG.info("CGNAT log tailer stopped");
    }

    /** Manual trigger: full re-read of the file from byte 0. */
    public synchronized void reload() {
        fileOffset = 0L;
        bindings.clear();
        pollFile();
    }

    private void pollSafely() {
        try {
            pollFile();
        } catch (RuntimeException e) {
            LOG.warn("CGNAT tail cycle failed for {}: {}", logPath, e.toString());
        }
    }

    private synchronized void pollFile() {
        try {
            if (!Files.isReadable(logPath)) {
                LOG.warn("CGNAT log not readable: {}", logPath);
                return;
            }
            long size = Files.size(logPath);
            if (size < fileOffset) {
                LOG.info("CGNAT log truncated/rotated ({} < {}) — full re-read", size, fileOffset);
                fileOffset = 0L;
                bindings.clear();
            }
            if (size == fileOffset) {
                return;
            }
            try (SeekableByteChannel ch = Files.newByteChannel(logPath, StandardOpenOption.READ)) {
                ch.position(fileOffset);
                ByteBuffer buf = ByteBuffer.allocate(Math.toIntExact(size - fileOffset));
                while (buf.hasRemaining() && ch.read(buf) >= 0) { /* read to EOF */ }
                fileOffset += consume(buf.array(), buf.position());
            }
        } catch (IOException e) {
            LOG.error("CGNAT tail error: {}", logPath, e);
        }
    }

    /** Consumes complete lines only; returns the number of bytes safely consumed. */
    private int consume(byte[] chunk, int len) {
        int start = 0;
        int nl;
        while ((nl = indexOf(chunk, start, len)) >= 0) {
            parseLine(new String(chunk, start, nl - start, StandardCharsets.UTF_8));
            start = nl + 1;
        }
        return start;
    }

    private static int indexOf(byte[] data, int from, int end) {
        for (int i = from; i < end; i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void parseLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        String[] parts = line.split(",");
        if (parts.length < 6) {
            LOG.debug("Skipping malformed CGNAT line: {}", line);
            return;
        }
        try {
            long tsEpochMs = Long.parseLong(parts[0].trim());
            String srcIp = parts[1].trim();
            int srcPort = Integer.parseInt(parts[2].trim());
            String msisdn = parts[3].trim();
            String imsi = parts[4].trim();
            long bearerAgeMs = Long.parseLong(parts[5].trim());
            if (srcIp.isEmpty() || msisdn.isEmpty()) {
                throw new NumberFormatException("empty src_ip/msisdn");
            }
            bindings.computeIfAbsent(key(srcIp, srcPort), k -> new CopyOnWriteArrayList<>())
                    .add(new CgnatBinding(tsEpochMs, msisdn, imsi, bearerAgeMs));
        } catch (NumberFormatException e) {
            LOG.debug("Skipping malformed CGNAT line: {}", line);
        }
    }

    @Override
    public CompletableFuture<ResolverResult> resolve(String srcIp, int srcPort, long tsEpochMs) {
        return CompletableFuture.supplyAsync(() -> {
            List<CgnatBinding> rows = bindings.getOrDefault(key(srcIp, srcPort), List.of());
            CgnatBinding best = null;
            Set<String> distinctMsisdns = new HashSet<>();
            for (CgnatBinding b : rows) {
                if (b.tsEpochMs() > tsEpochMs || b.tsEpochMs() < tsEpochMs - staleWindowMs) {
                    continue;
                }
                distinctMsisdns.add(b.msisdn());
                if (best == null || b.tsEpochMs() >= best.tsEpochMs()) {
                    best = b;
                }
            }
            if (best == null) {
                return ResolverResult.miss(FallbackReason.NO_BINDING);
            }
            if (distinctMsisdns.size() > 1) {
                return ResolverResult.miss(FallbackReason.AMBIGUOUS_BINDING);
            }
            return ResolverResult.bound(best.msisdn(), best.imsi(), best.bearerAgeMs());
        });
    }

    private static String key(String srcIp, int srcPort) {
        return srcIp + ":" + srcPort;
    }
}
