# SAS — Production Hardening Backlog (lab-accepts-all → production-locked)

**Status 2026-08-30:** the *configuration* half of this backlog is closed.
[`src/main/resources/application-prod.properties`](src/main/resources/application-prod.properties)
carries no lab defaults and [`../../harness/preflight_prod.py`](../../harness/preflight_prod.py)
refuses a lab-shaped deployment before the JVM starts (gates H15–H21 in
`harness/gates.yaml`). Re-audit: [`../docs/result_p1_reaudit.md`](../docs/result_p1_reaudit.md).
What follows is what is **left** — mostly runtime/UAT and code work.

## P-H1 — `/verify` transport + auth (P0 blocker)
- [x] Enforce **HTTPS only** on the bank→SAS `/verify` path —
      `quarkus.http.insecure-requests=disabled` + env-only keystore in the `prod`
      profile; PRO-11/PRO-12 gate it.
- [ ] **P-H1b HSTS** — Quarkus core (vertx-http 3.37.3) has no HSTS property.
      Emit `Strict-Transport-Security` at the edge proxy, or add a JAX-RS
      response filter (`max-age=31536000; includeSubDomains; preload`).
- [x] Require **Bearer / API key** on `/verify` — `sas.security.token-validation-enabled=true`
      + `sas.security.enforce-api-keys=true` in the `prod` profile (PRO-04/PRO-08).
- [x] Bind the presented key to `tenantId`/`networkId` — tenant is taken from the
      authenticated key, never from the request; PRO-09 rejects duplicate/lab keys.
- [ ] Live **mTLS handshake** UAT (config is asserted by PRO-13; the handshake is not).

## P-H2 — Admin dashboard
- [x] Rotate the lab default admin key — `sas.admin.key=${SAS_ADMIN_KEY}` (no
      default); PRO-14 refuses `change-me`, <16 chars or a literal in the file.
- [x] Session secret rotated — `sas.admin.session-hmac-secret=${SAS_ADMIN_SESSION_HMAC_SECRET}`; PRO-15.
- [x] Force **secure cookies** in production — `sas.admin.cookie-secure=true`; PRO-16
      (also pins bcrypt cost to 10..16).
- [ ] Add rate-limit on admin login (CSRF on session POSTs already enforced).
- [ ] Seed-password first-login change (admin/admin) — not yet forced.

## P-H3 — Bearer / API key lifecycle (code work, still open)
- [ ] Plaintext key shown **once** at creation; store bcrypt hash + fingerprint only.
- [ ] Add key rotation + revocation (revoke = `enabled=false` + audit).
- [ ] Per-key scopes (verify only) and per-tenant `maxTps` enforcement on `/verify`
      (quota *provisioning* is gated — PRO-28 rejects a quota naming no real tenant).

## P-H4 — Diameter (S6a/SWx) config
- [x] Real peer, not the lab — `sas.transport.diameter.peer-host=${SAS_DIAMETER_PEER_HOST}`,
      realm/origin/destination identity env-only; PRO-19/PRO-20 refuse loopback and
      the `restlink.et` lab identity.
- [ ] Enforce single-tail peer authentication (TLS/SCTP) — needs a live peer to verify.
- [ ] Prevent cross-realm leakage in multi-realm routing (validate realm→application binding).
- [ ] Persist last-good config on failed reload; alert on reload failure (fail-closed).

## P-H5 — jSS7 MAP transport
- [x] Stack config env-only (`sas.transport.jss7.config=${SAS_JSS7_CONFIG}`), JSON parsed,
      HLR GT ≠ local GT, lab GTs refused — PRO-18.
- [ ] Confirm **no interconnect ATI** against a real home STP (FS.11 Cat 1) — asserted
      in code + config shape, not yet in UAT.
- [ ] Link/ASP authentication for the M3UA path toward the home STP.
- [ ] One dialog per stage + abort on timeout (dialog-leak guard) — already coded, verify in UAT.

## P-H6 — CDR / privacy
- [x] Persistence on PostgreSQL (not the H2 lab file) under Flyway, `generation=none`,
      `sas.cdr.db.enabled=true`, absolute `sas.log.dir` — PRO-25/PRO-26.
- [x] Assurance detail off by default (not part of the CAMARA contract) — PRO-27.
- [ ] Redact MSISDN/IMSI from app-facing surfaces; CDR is bank-backend/admin only —
      verify with a live capture, not just code review.
- [ ] Retention window + tenant-scoped CDR visibility in the dashboard.

## P-H7 — Ops
- [ ] Metrics/alerts: resolver/verifier timeouts, fallback rate, dialogs-aborted counter.
- [ ] Backup `sas_config` (diameter.json / ss7.json) and flyway-managed schema.
- [ ] Run `harness/preflight_prod.py --json` in the release pipeline (the `prod`
      profile is inert unless `QUARKUS_PROFILE=prod` is set — forgetting it is the
      exact failure this item exists to catch).

## P-H8 — UE bearer: post-CGNAT port + attested declaration

- [ ] Echo endpoint (e.g. `GET /session-tuple/observed`) returning the caller's
      observed IP **and translated source port**. Real handsets cannot see the
      CGNAT port, so `/session-tuple` arrives with `srcPort=0` and the Resolver
      can only do IP-level correlation — not enough behind a shared NAT pool.
- [ ] Bind the device's declared `accessTech` to evidence: the observed source
      address of the tuple POST, plus Play Integrity (Android) / App Attest
      (iOS). Until then `accessTech` only *excludes* known-bad (Wi-Fi) tuples —
      it never raises assurance.
- [ ] Android sample app for `CellularBearer.fromNetwork(network, tech)` over
      `requestNetwork()` with a `subscriptionId` (dual-SIM): the SDK cannot
      subclass `NetworkCallback` (no `android.jar` in this artifact).

## Gate commands

```bash
python3 harness/preflight_prod.py            # verdict for this machine's env (exit = #fails)
python3 harness/preflight_prod.py --selftest # prove the gate bites (exit = undetected)
python3 harness/run_hardness.py              # 33/33 (H1–H23 + contract checks)
```