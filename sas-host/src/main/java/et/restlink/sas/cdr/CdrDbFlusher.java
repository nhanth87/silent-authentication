/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import et.restlink.sas.persist.SasCdrEntity;

import io.quarkus.hibernate.orm.panache.Panache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.TransactionManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Simple in-memory CDR flusher. Request threads only enqueue; a daemon worker
 * drains the queue and persists through Panache inside a guarded transaction.
 * A bounded in-memory deque keeps the most recent rows so the admin dashboard
 * renders even before the DB write lands (or if the DB is temporarily
 * unavailable).
 */
@ApplicationScoped
public class CdrDbFlusher {

    private static final Logger LOG = LogManager.getLogger(CdrDbFlusher.class);
    private static final int RECENT_CAP = 1000;

    @ConfigProperty(name = "sas.cdr.db.queue-cap", defaultValue = "100000")
    int queueCap = 100000;

    @ConfigProperty(name = "sas.cdr.db.batch-size", defaultValue = "500")
    int batchSize = 500;

    private ArrayBlockingQueue<SasCdrEntity> queue;
    private final Deque<SasCdrEntity> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile boolean running;
    private Thread worker;

    @PostConstruct
    void init() {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCap));
        this.running = true;
        this.worker = new Thread(this::runLoop, "sas-cdr-flusher");
        this.worker.setDaemon(true);
        this.worker.start();
        LOG.info("[cdr-db] flusher started queueCap={} batchSize={}", queueCap, batchSize);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    public boolean enqueue(SasCdrEntity row) {
        if (row == null) {
            return false;
        }
        remember(row);
        if (queue.offer(row)) {
            return true;
        }
        dropped.incrementAndGet();
        LOG.warn("[cdr-db] queue full; dropping corr={}", row.correlationId);
        return false;
    }

    public List<SasCdrEntity> recent(int limit) {
        int cap = Math.max(0, limit);
        synchronized (recentLock) {
            List<SasCdrEntity> out = new ArrayList<>(Math.min(cap, recent.size()));
            int i = 0;
            for (SasCdrEntity row : recent) {
                if (i++ >= cap) {
                    break;
                }
                out.add(row);
            }
            return out;
        }
    }

    public long dropped() {
        return dropped.get();
    }

    private void runLoop() {
        List<SasCdrEntity> batch = new ArrayList<>(Math.max(1, batchSize));
        while (running) {
            try {
                SasCdrEntity first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, Math.max(0, batchSize - 1));
                for (SasCdrEntity row : batch) {
                    persistQuietly(row);
                }
            } catch (InterruptedException ie) {
                running = false;
                Thread.currentThread().interrupt();
            }
        }
    }

    private void remember(SasCdrEntity row) {
        synchronized (recentLock) {
            recent.addFirst(row);
            while (recent.size() > RECENT_CAP) {
                recent.removeLast();
            }
        }
    }

    private void persistQuietly(SasCdrEntity row) {
        TransactionManager tm = null;
        try {
            tm = Panache.getTransactionManager();
            if (tm != null) {
                tm.begin();
            }
            row.persist();
            if (tm != null) {
                tm.commit();
            }
        } catch (Exception ex) {
            if (tm != null) {
                try {
                    tm.rollback();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            LOG.warn("[cdr-db] persist failed corr={}: {}", row.correlationId, ex.toString());
        }
    }
}