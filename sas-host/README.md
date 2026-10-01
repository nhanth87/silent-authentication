# Silent Auth SAS — `sas-camara`: the CAMARA API gateway

`sas-host` builds **sas-camara**, the internet/partner-facing binary of the Restlink SAS
(layered ADR: [`docs/design/sas-layered-architecture.md`](../docs/design/sas-layered-architecture.md)).
It serves the CAMARA APIs and the OAuth Authorization Server, scores the evidence and
decides — and it **never signals the core network**: no MAP, Diameter or SCTP code or jar
is in this binary (gate H25 + `scripts/check-dependency-boundary.sh`). Every subscriber
fact comes from the entitlement server ([`sas-ecs-host`](../sas-ecs-host/README.md))
through the subscriber exposure API, over the single resource adaptor `exposure-ra`.

Quarkus + micro-jainslee, **Java 25**, fast-jar. One SLEE seam: `SasBootstrap` (gate H24).

```
POST /camara/number-verification/v2/verify  (or /authorize → /token → /verify)
  → VerifyResource → VerifyRequestEvent → VerifySbb            (SLEE event router)
      RESOLVING  exposure-ra → POST /ue-id/retrieve {IP, port, ts}        400 ms
                 (Wi-Fi: the number of a redeemed operator token instead)
      VERIFYING  exposure-ra → monitoring-event ∥ sim-change/retrieve      2.2 s
      SCORING    ExposureAdapters → VerificationFsm (weights, thresholds, home-PLMN policy)
  ← { devicePhoneNumberVerified: boolean }          — fail-closed on any problem/timeout
```

## Package layout

| Package | Role |
|---|---|
| `bootstrap` | `SasBootstrap` — starts the container, wires `exposure-ra` and `VerifySbb`, bridges REST → SLEE; exposes `subscribers()` for the non-SBB callers (OAuth anchor, SIM Swap) |
| `sbbs` | `VerifySbb` — RESOLVING → VERIFYING → SCORING over exposure calls; one flow CDR per request (internal evidence sources go to the CDR only) |
| `ras/exposure` | `ExposureClient` (JDK HTTP client, implements `SubscriberService`; every non-200 / timeout / off-contract answer is a problem), `ExposureRaEndpoint`, `SubscriberCallCommand` |
| `fsm` | `VerificationFsm` — pure decision engine |
| `coordinator` | async SLEE ↔ sync HTTP bridge + idempotency per `reqId` |
| `simswap` | `HostSimSwapEvidence` — CAMARA SIM Swap evidence via `/sim-change/retrieve` (no evidence → 404, never `swapped:false`) |
| `otpsms` | `LabLogSmsDelivery` — lab OTP sender (logs, sends nothing; prod keeps OTP off, PRO-29) |
| `cdr`, `persist`, `persistence` | CDR ledger (flow + API rows), entities, Flyway `db/migration` V1–V4 (V4 drops the former operator-token ledger) |
| `admin`, `web`, `tenant`, `security`, `config` | admin UI (`app/html/admin`), tenants and app keys, admin runtime config |

The CAMARA surface itself (NV, SIM Swap, OTP SMS, OAuth AS) lives in the `sas-api`
library; this module assembles it.

## Build & run

```bash
mvn -o test                                   # from the repo root (9 modules)
./scripts/package-dist.sh camara              # → dist/ (fast-jar, configs/, app/html, run.sh)
(cd dist && ./run.sh)                         # lab :8085 — needs sas-ecs on :8095
scripts/split-smoke.sh                        # both binaries end to end + pcap
python3 harness/preflight_prod.py --service camara
```

Configuration that matters here:

| Property | Lab | Meaning |
|---|---|---|
| `sas.exposure.client.base-url` | `http://127.0.0.1:8095/sas-subscriber-exposure/v1` | where the entitlement server is (prod: HTTPS, PRO-36) |
| `sas.exposure.client.af-id` | `sas-camara` | this gateway's AF id on the exposure hop |
| `sas.policy.home-plmn`, `sas.policy.location-max-age-minutes` | `63601`, `1440` | location policy on the evidence metadata |
| `sas.operator-token.direct-bearer-enabled` | `true` (lab only) | the SAS extension `Bearer operatortoken:` on `/verify`; prod off (PRO-32) |

There is no `sas.transport.*` key in this binary any more (prod preflight PRO-17 refuses
one). Environment variables and `-D` override the dist's `configs/application.properties`.

## OAuth AS and operator tokens (CAMARA ICM)

| Surface | Notes |
|---|---|
| `GET /authorize` | Authorization Code + mandatory PKCE S256; network authentication = UE ID retrieval at sas-ecs for the connection source (edge headers only with `sas.oauth.authorize.trust-src-header=true`) |
| `POST /token` | `authorization_code`, CIBA, `client_credentials`, JWT bearer (`sub=tel:` / `operatortoken:`) |
| `POST /bc-authorize` | CIBA; `login_hint=operatortoken:<token>` or the cellular tuple |
| `GET /jwks.json` | ES256 signing keys only (`use=sig`) |

Access tokens are ES256 `at+jwt` with `aud`; ID tokens carry a pairwise `sub`. An operator
token is forwarded **unread** to sas-ecs `POST /operator-token/redeem`, which decrypts,
verifies and spends it exactly once; this binary holds no token key and no ledger.

## Production profile (`QUARKUS_PROFILE=prod`)

`src/main/resources/application-prod.properties` carries no lab defaults: every
credential and operator-specific value is `${ENV_VAR}`, so a missing secret fails the
boot. HTTPS only, mTLS towards the bank, token validation and API keys on, PostgreSQL +
Flyway. The preflight (`--service camara`) reports what is missing or unsafe without
printing secrets. The exposure hop has no client authentication until migration step M4,
so the pair is not production-deployable before then (see the ADR).

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, signed builds + license key, security advisories, L1/L2 SLA, training and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
