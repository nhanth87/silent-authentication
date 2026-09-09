/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import java.util.ArrayList;
import java.util.List;

public final class RecordingApiCdrRecorder implements ApiCdrRecorder {

    public final List<ApiCdrRecord> records = new ArrayList<>();

    @Override
    public void record(ApiCdrRecord record) {
        records.add(record);
    }

    public ApiCdrRecord last() {
        return records.get(records.size() - 1);
    }

    public void clear() {
        records.clear();
    }
}
