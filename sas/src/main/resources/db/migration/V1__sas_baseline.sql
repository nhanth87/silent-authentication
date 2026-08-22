-- SAS persistence baseline (H2 MODE=PostgreSQL).
-- Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.

CREATE TABLE IF NOT EXISTS sas_tenant (
  tenant_id VARCHAR(128) PRIMARY KEY,
  display_name VARCHAR(256),
  network_id INT NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  admin_api_key VARCHAR(256),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sas_admin_user (
  username VARCHAR(64) PRIMARY KEY,
  password_hash VARCHAR(256) NOT NULL DEFAULT '',
  role VARCHAR(16) NOT NULL DEFAULT 'OPS',
  tenant_id VARCHAR(128),
  display_name VARCHAR(256),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sas_app_user (
  username VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(128) NOT NULL,
  network_id INT NOT NULL,
  api_key_hash VARCHAR(128) NOT NULL DEFAULT '',
  api_key_fp VARCHAR(8),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sas_config (
  config_key VARCHAR(128) PRIMARY KEY,
  config_value VARCHAR(65536) NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS sas_cdr_session (
  id UUID PRIMARY KEY,
  recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
  correlation_id VARCHAR(128) UNIQUE NOT NULL,
  phase VARCHAR(32) NOT NULL,
  status VARCHAR(64) NOT NULL,
  msisdn VARCHAR(32),
  operation VARCHAR(16),
  detail VARCHAR(1024),
  network_id INT,
  tenant_id VARCHAR(128),
  csv_line VARCHAR(4000) NOT NULL,
  started_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  event_count INT NOT NULL DEFAULT 1,
  events_json VARCHAR(8192)
);

CREATE INDEX IF NOT EXISTS idx_sas_cdr_network_id ON sas_cdr_session(network_id);
CREATE INDEX IF NOT EXISTS idx_sas_cdr_tenant_id ON sas_cdr_session(tenant_id);