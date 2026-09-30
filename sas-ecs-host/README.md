# sas-ecs — Restlink TS.43 entitlement server

The second SAS binary. Deploy it only when the operator has **no entitlement server of its
own** (mode 2); an operator with its own ECS runs `sas-camara` (`sas-host`) alone and lists
its ECS as a trusted operator-token issuer. Architecture, trust model and the open P1 items:
[`docs/design/sas-two-binary-architecture.md`](../docs/design/sas-two-binary-architecture.md).

Quarkus + micro-jainslee, **Java 25**, fast-jar. One SLEE seam: `EcsBootstrap` (gate H24).
No CAMARA endpoint, no admin UI, no database (gate H25 keeps it that way).

| Surface | Purpose |
|---|---|
| `GET /ts43/challenge`, `POST /ts43/respond` | EAP-AKA against the own HLR/HSS (not yet the GSMA TS.43 wire — see the architecture doc §9) |
| `POST /entitlement/issue` | the operator AAA reports a completed EAP-AKA (API key + attestation MAC in prod) |
| `POST /entitlement/exchange` | legacy ECS-local redeem (single use) |
| `GET /entitlement/status` | health |
| `GET /jwks.json` | ES256 key operator tokens are signed with — `sas-camara`'s `jwks_uri` for this issuer |

Core network (own stacks, never shared with `sas-camara`): MAP **SAI** + **SendIMSI** over
M3UA/**SCTP** (`src/main/resources/ss7-ecs.json`, lab local port 2907) or Diameter **SWx
MAR** (own `Origin-Host`). ATI, SRI-SM and S6a AIR are banned on this path (gate H25).

Tokens: `sas.entitlement.token-format=operator-jwe` — an ES256 JWS nested in an
ECDH-ES/A256GCM JWE addressed to the CAMARA server's `enc` key, lifetime ≤ 300 s. The
subscriber number is never readable while the token crosses the handset and the bank app.

## Build and run

```bash
mvn -o test                                   # from the repo root (all modules)
./scripts/package-dist.sh ecs                 # → dist-ecs/ (fast-jar, configs/, run.sh)
(cd dist-ecs && ./run.sh)                     # lab: :8095, in-memory vectors (never ship)
scripts/split-smoke.sh                        # both binaries end to end + pcap
python3 harness/preflight_prod.py --service ecs   # prod gate for THIS environment
```

Production is `QUARKUS_PROFILE=prod` with the `ECS_*` environment variables listed in
`src/main/resources/application-prod.properties`; the preflight refuses fabricated auth
vectors, the committed lab encryption key, an unencrypted or HMAC token format, a missing
attestation secret and a deployment without an IMSI→MSISDN source.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, signed builds + license key, security advisories, L1/L2 SLA, training and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
