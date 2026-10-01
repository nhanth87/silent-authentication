-- Durable single-use ledger of the operator tokens this entitlement server issued
-- (layered ADR D7: the issuer owns it; moved from sas-camara V3).
-- One row per redeemed (issuer, jti); the primary key makes the first INSERT win on
-- every ECS instance sharing the database, so a replay is refused cluster-wide.
-- H2 MODE=PostgreSQL compatible.
-- Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.

CREATE TABLE IF NOT EXISTS ecs_operator_token_jti (
    token_key  VARCHAR(64)  NOT NULL PRIMARY KEY,
    issuer     VARCHAR(512) NOT NULL,
    expires_at BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_ecs_operator_token_jti_expires
    ON ecs_operator_token_jti (expires_at);
