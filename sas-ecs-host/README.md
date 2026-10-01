# sas-ecs — Restlink entitlement server

`sas-ecs-host` builds **sas-ecs**, the binary inside the operator trust zone
(layered ADR: [`docs/design/sas-layered-architecture.md`](../docs/design/sas-layered-architecture.md)).
It is the **only** SAS binary that signals the core network, and it serves three things:

1. **GSMA TS.43 entitlement** — EAP-AKA on `/ts43` against the own HLR/HSS, then an
   operator token (ES256 JWS in an ECDH-ES/A256GCM JWE **encrypted to this ECS**, ≤ 300 s).
2. **Operator-token redemption** — the only redemption point, single use in a durable
   ledger (`ecs_operator_token_jti`), idempotent per `(jti, X-Request-Id)`.
3. **Subscriber exposure API** for the CAMARA gateway — `/sas-subscriber-exposure/v1`:
   UE ID retrieval, one-time monitoring, SIM change, token redemption
   (contract: [`docs/design/openapi/sas-subscriber-exposure-v1.yaml`](../docs/design/openapi/sas-subscriber-exposure-v1.yaml)).

Quarkus + micro-jainslee, **Java 25**, fast-jar. One SLEE seam: `EcsBootstrap` (gate H24).
No CAMARA endpoint and no admin UI (gate H25).

| Surface | Caller | Purpose |
|---|---|---|
| `GET /ts43/challenge`, `POST /ts43/respond` | device | EAP-AKA (not yet the GSMA TS.43 wire; codec P1 blocker) |
| `POST /session-tuple` | device | cellular session tuple for the resolver (Wi-Fi refused) |
| `POST /sas-subscriber-exposure/v1/…` | sas-camara only | the four exposure operations (lab only until M4 adds mTLS + client credentials; prod pins it off, PRO-35) |
| `POST /entitlement/issue` | operator AAA | report a completed EAP-AKA (API key + attestation MAC in prod) |
| `GET /entitlement/status` | ops | health |

`/entitlement/exchange` refuses while this ECS redeems (one redemption point); the former
operator-token `/jwks.json` is gone (no external verifier remains).

## Inside

| Package | Role |
|---|---|
| `bootstrap/EcsBootstrap` | wires the RAs on **one** jSS7 stack (auth vectors MAP SAI / SWx MAR, SendIMSI binding, resolver, MAP verifier, S6a verifier), `EntitlementSbb`, `SubscriberSbb`; implements `SasEntitlementEngine`, `SubscriberService`, `ResolverAccess` |
| `sbbs/EntitlementSbb` | EAP-AKA challenge/response, binding confirmation, token issue |
| `sbbs/SubscriberSbb` | the exposure operations: chooses the source, enforces stage budgets, aborts dialogs on expiry, maps every failure to a contract problem |
| `coordinator/` | TS.43 sessions and answers |
| `persist/JdbcOperatorTokenLedger` | the durable ledger (Flyway V1; H2 lab, PostgreSQL prod) |

Core network, own stacks only: MAP **SAI**, **SendIMSI** (SSN 3) and **PSI** over
M3UA/**SCTP** (`src/main/resources/ss7-ecs.json`, lab local port 2907); Diameter **S6a**,
**SWx MAR/SAR**, Gx probe. ATI, SRI-SM and S6a AIR are banned (gate H25).

## Build and run

```bash
mvn -o test                                   # from the repo root (all modules)
./scripts/package-dist.sh ecs                 # → dist-ecs/ (fast-jar, configs/, run.sh)
(cd dist-ecs && ./run.sh)                     # lab: :8095, in-memory transports (never ship)
(cd dist-ecs && SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP=jss7 \
   SAS_BINDING_SOURCE_ORDER=map-smi SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json ./run.sh)
scripts/exposure-smoke.sh                     # exposure API vs the HLR simulator + pcap
python3 harness/preflight_prod.py --service ecs
```

Production is `QUARKUS_PROFILE=prod` with the `ECS_*` environment variables listed in
`src/main/resources/application-prod.properties`; the preflight refuses fabricated auth
vectors, a lab or public decryption key, a per-instance (H2) ledger, redemption handed back
to a CAMARA server, a missing attestation secret, no IMSI→MSISDN source, and the exposure
surface before it is authenticated.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, signed builds + license key, security advisories, L1/L2 SLA, training and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
