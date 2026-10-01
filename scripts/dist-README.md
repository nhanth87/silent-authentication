# Restlink Silent Auth SAS — ship this folder

This dist is one of two binaries — `.service` says which:

| `.service` | Binary | Port (lab) | Built by |
|---|---|---|---|
| `camara` | **sas-camara** — CAMARA API gateway: NV / SIM Swap / OTP-SMS + OAuth AS; no signalling stack | 8085 | `./scripts/package-dist.sh camara` → `dist/` |
| `ecs` | **sas-ecs** — entitlement server: TS.43, token redemption, subscriber exposure API, all MAP/Diameter | 8095 | `./scripts/package-dist.sh ecs` → `dist-ecs/` |

`sas-ecs` has no admin UI (`app/`); its database holds only the operator-token ledger. The sections below describe the
CAMARA dist; for sas-ecs see `sas-ecs-host/README.md` in the source tree.

Self-contained Quarkus **fast-jar** runtime for the Silent Auth SAS
(CAMARA NumberVerification `/verify`: UE ID → monitoring ∥ SIM change at the entitlement
server → policy at the gateway, fail-closed).

```bash
./run.sh
```

## Layout (not an uber-jar)

| Path | Role |
|------|------|
| `quarkus-run.jar` | Thin launcher — start with `./run.sh` |
| `sas-host-app.jar` | Application classes at APP_HOME **root** |
| `lib/boot/` · `lib/main/` | Dependencies (replaceable jars) |
| `quarkus/` | Generated Quarkus model (ship together with the app jar) |
| `app/html/` | Admin UI only — **never jars here** |
| `configs/` | `application.properties` + `application-prod.properties` (+ `ss7-ecs.json` in the sas-ecs dist) |
| `data/` · `logs/` | Runtime state |

- Never `java -jar sas-host-app.jar` alone.
- Never ship a single fat jar.
- Deploy `quarkus/` + `lib/` **together with** `sas-host-app.jar` — never just
  one jar (`quarkus/` stale ⇒ old code still runs, or an H2-era augment overrides
  a Postgres JDBC URL and crash-loops).

- JDK 25 only.
- Admin: `http://HOST:8085/admin/login` (from the lab profile).
- Lab (default profile) accepts plain HTTP + in-memory transports **on purpose** —
  never ship it. Production is `QUARKUS_PROFILE=prod` after
  `harness/preflight_prod.py`.

## Lab happy path

```bash
./run.sh                                    # lab: in-memory MAP/S6a/SWx, H2 at data/sas-db
curl -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer demo' \
  -d '{"phoneNumber": "+251911111111"}'
# → {"devicePhoneNumberVerified": true}
```

## Production profile

```bash
QUARKUS_PROFILE=prod ./run.sh               # reads configs/application-prod.properties
```

The prod profile carries **no lab defaults**: every credential is a required
environment variable (fail-closed — a missing `${VAR}` refuses the boot). Run the
preflight first for a readable list instead of a config stack trace:

```bash
python3 harness/preflight_prod.py
```

## Config notes

- `configs/application.properties` is the operator-editable file
  (re-packaging never overwrites it — it writes `application.properties.new`).
- `quarkus.datasource.db-kind` is **build-time**: this dist is baked for the lab
  H2 backend. A PostgreSQL production artifact needs a repackage with that db-kind
  baked in; switching it by editing `configs/application.properties` alone is not
  enough.
- sas-camara points at the entitlement server with `sas.exposure.client.base-url`; it has
  no SS7/Diameter configuration. `ss7-ecs.json` (sas-ecs dist only) is the jSS7 MAP stack
  sample (`sas.transport.map=jss7`, `sas.transport.authvector=map`).

Dual-licensed — pick exactly one (full terms: `../../LICENSE.md`):
`AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`.

Copyright © 2026 Tran Nhan.