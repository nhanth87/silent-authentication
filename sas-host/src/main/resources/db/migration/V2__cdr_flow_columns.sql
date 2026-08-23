-- SAS /verify full-flow CDR columns (V2 — one row per request, written once
-- at the SBB terminal point). H2 MODE=PostgreSQL compatible.
-- Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.

ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS verified BOOLEAN;
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS decision VARCHAR(16);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS score INTEGER;
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS threshold INTEGER;
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS assurance_level VARCHAR(24);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS risk_class VARCHAR(16);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS access_tech VARCHAR(12);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS fallback_reason VARCHAR(48);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS resolver_status VARCHAR(32);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS evidence_source VARCHAR(32);
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS evidence_json TEXT;
ALTER TABLE sas_cdr_session ADD COLUMN IF NOT EXISTS total_ms INTEGER;
