/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory ring buffer (last {@value #CAPACITY} records) of every MAP message
 * received, answered or dropped by the simulated HLR, for the control web UI.
 */
public final class MessageLog {

    public static final int CAPACITY = 500;

    /** One immutable log record. */
    public record Entry(Instant time, String direction, String operation,
                        long dialogId, String result, String details) {

        @Override
        public String toString() {
            return time + " " + direction + " " + operation + " dialog=" + dialogId
                    + " result=" + result + (details.isEmpty() ? "" : " " + details);
        }
    }

    private final Deque<Entry> ring = new ArrayDeque<>(CAPACITY);

    public synchronized void add(Entry entry) {
        while (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(entry);
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(ring);
    }
}
