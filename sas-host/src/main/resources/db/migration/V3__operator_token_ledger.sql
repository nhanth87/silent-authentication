-- Durable single-use ledger for CAMARA operator tokens (V3).
-- One row per consumed (issuer, jti); the primary key makes the first INSERT win on
-- every CAMARA instance sharing the database, so a replay is refused cluster-wide.
-- H2 MODE=PostgreSQL compatible.
-- Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.

CREATE TABLE IF NOT EXISTS sas_operator_token_jti (
    token_key  VARCHAR(64)  NOT NULL PRIMARY KEY,
    issuer     VARCHAR(512) NOT NULL,
    expires_at BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_sas_operator_token_jti_expires
    ON sas_operator_token_jti (expires_at);
