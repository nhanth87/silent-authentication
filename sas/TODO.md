# SAS — Production Hardening Backlog (lab-accepts-all → production-locked)

Current state is **lab-only**: the HTTP `/verify` surface and the admin dashboard accept plain
HTTP and unauthenticated / well-known default keys. Before any production pilot with Ethiopian
banks, close the following. Each item is fail-closed releasable independently.

## P-H1 — `/verify` transport + auth (P0 blocker)
- [ ] Enforce **HTTPS only** on the bank→SAS `/verify` path (reject plain HTTP, HSTS).
- [ ] Require **Bearer / API key** on `/verify`: validate tenant `httpApiKey` OR app-user
      bearer key (web/android/ios) via `TenantGuard` — currently lab-accepts-all.
- [ ] Bind the presented key to the request `tenantId`/`networkId` (no cross-tenant reads).

## P-H2 — Admin dashboard
- [ ] Rotate the lab default admin key (`sas.admin.key=change-me`) and seed password (admin/admin).
- [ ] Force **HTTPS + secure cookies** for `/admin` (cookieSecure=true) in production.
- [ ] CSRF already enforced on session POSTs — keep it; add rate-limit on login.

## P-H3 — Bearer / API key lifecycle
- [ ] Plaintext key shown **once** at creation; store bcrypt hash + fingerprint only.
- [ ] Add key rotation + revocation (revoke = `enabled=false` + audit).
- [ ] Per-key scopes (verify only) and per-tenant `maxTps` enforcement on `/verify`.

## P-H4 — Diameter (S6a/SWx) config
- [ ] Enforce single-tail peer authentication (TLS/SCTP) — lab uses loopback.
- [ ] Prevent cross-realm leakage in multi-realm routing (validate realm→application binding).
- [ ] Persist last-good config on failed reload; alert on reload failure (fail-closed).

## P-H5 — jSS7 MAP transport
- [ ] Confirm **no interconnect ATI** (FS.11 Cat 1) — own HLR/HSS GT only (assert in config).
- [ ] Link/ASP authentication for the M3UA path toward the home STP.
- [ ] One dialog per stage + abort on timeout (dialog-leak guard) — already coded, verify in UAT.

## P-H6 — CDR / privacy
- [ ] Redact MSISDN/IMSI from app-facing surfaces; CDR is bank-backend/admin only.
- [ ] Retention window + tenant-scoped CDR visibility in the dashboard.

## P-H7 — Ops
- [ ] Metrics/alerts: resolver/verifier timeouts, fallback rate, dialogs-aborted counter.
- [ ] Backup `sas_config` (diameter.json / ss7.json) and flyway-managed schema.