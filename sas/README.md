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

```bash
cd worktrees/silent-authentication/main/sas
mvn quarkus:dev          # dev mode on http://localhost:8085
# or
mvn package              # build the jar
java -jar target/quarkus-app/quarkus-run.jar
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
contracts asserted by `harness/gates.yaml` (H1–H14). Run the project gate from the tree root:

```bash
python3 harness/run_hardness.py          # 24/24 pass (contract mode)
```

Java-side unit tests (pure JUnit 5, no Quarkus boot): `mvn test`.

## P1 (not in this module yet)

- TS.43 / EAP-AKA SWm/SWx verifier (Wi-Fi path) — fails closed with `WIFI_NOT_READY`.
- Real OIDC token validation + single-use ≤300 s token enforcement (pilot: header check).
- Operator-side Resolver source (PGW RADIUS / PCRF Sd / CGNAT log).
- S6a transport is currently an in-memory HSS stand-in; wire corsac-diameter/jDiameter AIR/ULR
  (mirror `vendor-ras/ra-diameter`) for a live S6a client.

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
signalling path).