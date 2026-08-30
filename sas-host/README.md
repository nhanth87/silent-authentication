# Silent Auth SAS — CAMARA `/verify` adapter (P0)

The **P0** of the Silent Authentication implementation: a Quarkus + micro-jainslee
application that implements the CAMARA NumberVerification `POST /verify` surface over
the Digicom-ET Silent Auth Service (SAS) — Resolver → Verifier → Policy, **fail-closed**.

Cloned from the two templates named in `AGENTS.md` §10:

- RAs — cloned from `vendor-ras/ra-diameter` (wrapper + delegate 3-port contract).
- Bootstrap — cloned from `worktrees/voice-service/sip-freeswitch/elisa`
  (`ElisaBootstrap`: `@ApplicationScoped`, `@Observes StartupEvent`, `MicroSleeContainer`).

## Why this is the P0

The only remaining open item that turns the research/design into code is the
**CAMARA NV Java adapter** over SAS `/verify`. This module is that adapter:

```
POST /verify { phoneNumber }            (CAMARA NV v2.1.0)
  → VerifyResource (Quarkus REST)
    → VerifyRequestEvent → VerifySbb    (SLEE event router)
      RESOLVING → Resolver RA    (ip:port:ts → MSISDN/IMSI, 300 ms)
      VERIFYING → MAP Verifier RA (PSI + SAI, never ATI, 2 s, abort on timeout)  — 2G/3G
      VERIFYING → S6a Verifier RA (ULR/ULA + AIR/AIA, own HSS, 2 s)              — LTE/NR
      SCORING  → VerificationFsm (weighted assurance, fail-closed)
  ← { devicePhoneNumberVerified: boolean }
```

## Package layout

| Package | Role |
|---------|------|
| `et.digicomet.sas.api` | CAMARA REST surface (`/verify`, `/retrieve-phone-number`) + DTOs |
| `et.digicomet.sas.bootstrap` | `SasBootstrap` (ElisaBootstrap clone) |
| `et.digicomet.sas.events` | `VerifyRequestEvent` (`@EventType`) |
| `et.digicomet.sas.sbbs` | `VerifySbb` (the entitlement-service SBB) |
| `et.digicomet.sas.fsm` | `VerificationFsm`, `AssurancePolicy`, `SasTimeouts` |
| `et.digicomet.sas.ras.resolver` | Resolver RA (wrapper + delegate + backend) |
| `et.digicomet.sas.ras.mapverifier` | MAP Verifier RA (wrapper + delegate + dialog + backend) |
| `et.digicomet.sas.ras.s6averifier` | Diameter S6a Verifier RA (wrapper + delegate + session + backend) |
| `et.digicomet.sas.model` | Value records (`VerifyResult`, `ResolverResult`, …) |
| `et.digicomet.sas.coordinator` | Async SLEE ↔ sync HTTP bridge + idempotency |

## Build & run

> **Java 25 only** (root AGENTS.md). Use `mise` → `zulu-25`. R&D only — never production;
> production USSD 7.3 builds use Mobicents SLEE master-era JARs.

Multi-module build (mirrors the epc pattern): root aggregator `sas-core` →
`sas-api` (CAMARA northbound) + `sas-entitlement` (TS.43/Wi-Fi track) +
`sas-host` (this runnable composition app).

```bash
cd worktrees/silent-authentication/main
mvn -q clean test                 # all 3 modules (337 tests)
mvn -q package -DskipTests        # build everything
java -jar sas-host/target/quarkus-app/quarkus-run.jar

# per-module work
cd sas-host && mvn quarkus:dev    # dev mode on http://localhost:8085
```

### Demo happy path

```bash
curl -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer demo' \
  -H 'x-correlator: 0001' \
  -d '{"phoneNumber": "+251911111111"}'
# → {"devicePhoneNumberVerified": true}
```

Pilot seeds (see `SasBootstrap`): binding `10.20.30.40:55555 → +251911111111`,
subscriber attached / no SIM swap / region `AA`. The device network tuple is read
from `X-Sas-Src-Ip` / `X-Sas-Src-Port` / `X-Sas-Access-Tech` headers (production
mints it from the CIBA/network-auth token).

### Fail-closed demos

- `{"phoneNumber": "+251922222222"}` → `{"devicePhoneNumberVerified": false}` (MSISDN_MISMATCH)
- `X-Sas-Amr: sms-otp` → 403 `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`
- `X-Sas-Access-Tech: LTE` → `true` (S6a ULR/ULA + AIR/AIA) when the resolver tuple matches the seed
- `X-Sas-Access-Tech: WIFI` → `false` (`WIFI_NOT_READY`, fail closed — TS.43/EAP-AKA is P1)

## Harness mapping

The pure decision engine (`VerificationFsm`) + budgets (`SasTimeouts`) encode the same
contracts asserted by `harness/gates.yaml` (H1–H14). H15–H21 additionally gate the
**deployment artifact** (`application-prod.properties` + environment). Run from the tree root:

```bash
python3 harness/run_hardness.py          # 31/31 pass (contract + deployment gates)
python3 harness/preflight_prod.py        # prod-profile verdict for THIS environment
python3 harness/preflight_prod.py --selftest   # prove the deployment gate bites
```

Java-side unit tests (pure JUnit 5, no Quarkus boot): `mvn test`.

## Production profile (`QUARKUS_PROFILE=prod`)

`src/main/resources/application-prod.properties` is the deployment-safe overlay. It
carries **no lab defaults**: every credential and operator-specific value is
`${ENV_VAR}` with **no `:default`**, so a missing secret fails the config read at boot
instead of silently reverting to `change-me` / `demo` / loopback.

```bash
# from the tree root (the script resolves its own paths — run it anywhere)
export QUARKUS_PROFILE=prod
python3 harness/preflight_prod.py || exit 1                # gate BEFORE the JVM
java -jar sas-host/target/quarkus-app/quarkus-run.jar      # HTTPS :8443 only, mTLS
```

Highlights: `insecure-requests=disabled`, `ssl.client-auth=required`,
`token-validation-enabled=true`, `enforce-api-keys=true`, `transport.map=jss7`,
`s6a/swx=corsac`, PostgreSQL + Flyway (`database.generation=none`),
`entitlement.require-signed=true`, `cdr.db.enabled=true`.

The preflight (`PRO-01`…`PRO-28`) reports what is missing/unsafe without printing any
secret value — names, lengths and fingerprints only. Exit code = number of failed checks.

**Not covered by the profile:** HSTS (Quarkus core has no such property — terminate it at
the edge proxy), live mTLS/SS7/Diameter handshakes, key rotation. See
[TODO.md](TODO.md) and [`../docs/result_p1_reaudit.md`](../docs/result_p1_reaudit.md).

## Deferred / still stand-in in lab mode

- `X-Sas-Access-Tech: WIFI` in **lab** mode returns `WIFI_NOT_READY` (in-memory SWx);
  the real TS.43 / EAP-AKA SWx verifier RA is wired behind `sas.transport.swx=corsac`.
- Token validation + single-use ≤300 s enforcement is **on only in `prod`**
  (base profile keeps `token-validation-enabled=false` for demos).
- Resolver source (PGW RADIUS / PCRF Sd / CGNAT log) is an operator input:
  `sas.transport.resolver=${SAS_TRANSPORT_RESOLVER}` ∈ `radius|cgnat|sd`.
- S6a in lab mode is an in-memory HSS stand-in; `corsac-diameter` S6a is opt-in.

## P2 — real signalling transport (wired, opt-in)

The verifier backends are pluggable behind `SasTransportConfig`. Default is the
in-memory pilot backend; flip a property to switch to the real transport.

| Property | Values | Backend |
|----------|--------|---------|
| `sas.transport.map` | `memory` (default) / `jss7` | `InMemoryMapVerifierBackend` / `Jss7MapVerifierBackend` |
| `sas.transport.s6a` | `memory` (default) / `corsac` | in-memory HSS / corsac-diameter S6a |
| `sas.transport.swx` | `memory` (default) / `corsac` | in-memory AAA / corsac-diameter SWx |

### jSS7 MAP verifier (`sas.transport.map=jss7`)

`Jss7MapVerifierBackend` builds a real jSS7 (coral-valley) stack from a single-file
JSON config (`Ss7StackBuilder.build(Path)`), registers a `MAPServiceMobilityListener`
+ `MAPDialogListener`, and drives **one TCAP dialog per stage**:

- **PSI** (`provideSubscriberInfo`, FS.11 Cat 2.1) → reachable + location plausibility.
- **SAI** (`sendAuthenticationInfo`, FS.11 Cat 3.2) → SIM-swap freshness (auth-vector set).
- **Never ATI** — inbound `anyTimeInterrogation` is logged + dropped (FS.11 Cat 1).

Fail-closed: any timeout / reject / abort / error-component completes the pending
future with a `FallbackReason`; the dialog is aborted so nothing leaks.

Enable it (sample config ships at `src/main/resources/ss7-sas.json`):

```properties
sas.transport.map=jss7
sas.transport.jss7.config=src/main/resources/ss7-sas.json
sas.transport.jss7.hlr-gt=<home HLR global title>
sas.transport.jss7.local-gt=<SAS local global title>
```

If `sas.transport.jss7.config` is blank the bootstrap logs a warning and falls back
to the in-memory backend (fail-closed — a misconfiguration never silently opens a
si

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Digicom-ET. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, signed builds + license key, security advisories, L1/L2 SLA, training and integration engineering. Terms per deployment via Digicom-ET. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
