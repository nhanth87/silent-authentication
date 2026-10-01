-- Layered ADR D7 (V4): the operator-token ledger belongs to the entitlement server, the
-- token's issuer and only redeemer (sas-ecs Flyway V1, ecs_operator_token_jti). The
-- CAMARA gateway keeps no identity state, so its V3 table goes.
-- H2 MODE=PostgreSQL compatible.
-- Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.

DROP INDEX IF EXISTS ix_sas_operator_token_jti_expires;
DROP TABLE IF EXISTS sas_operator_token_jti;
