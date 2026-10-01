# Silent Authentication — Restlink SAS

[![License: AGPL-3.0-or-later OR Operator](https://img.shields.io/badge/License-AGPL--3.0--or--later%20OR%20Operator-blue)](LICENSE.md)

Network-side **silent authentication** for Ethiopian banks (Restlink VAS above Ethio
Telecom): prove that the phone currently on the network owns the claimed number —
**no password re-entry, no SMS OTP** on the happy path — behind standard **CAMARA** APIs
(Number Verification, SIM Swap, OTP SMS fallback, ICM OAuth).

| Method | Root of trust | Needs cellular data? | Where it runs |
|---|---|---|---|
| **IP-match** | operator binding IP:port:ts → subscriber (PGW/PCRF/CGNAT) + MAP/Diameter evidence | yes | gateway asks, **sas-ecs** answers |
| **SIM / GSMA TS.43 EAP-AKA** | the SIM credential | no (Wi-Fi, browsers) | **sas-ecs** `/ts43` issues an operator token, redeemed once |

Fail-closed everywhere: missing evidence, a timeout or an unreachable component never
approves. Fallback when silent auth is unavailable: TOTP, passkey, push, or firewalled
SMS OTP.

## Architecture — two binaries, split by layer

Plan of record: [`docs/design/sas-layered-architecture.md`](docs/design/sas-layered-architecture.md)
(accepted ADR) and the interface contract
[`docs/design/openapi/sas-subscriber-exposure-v1.yaml`](docs/design/openapi/sas-subscriber-exposure-v1.yaml).

```
 bank backend / GSMA sandbox
          │  CAMARA NBI: NV v2 · SIM Swap v2 · OTP SMS v1 · OAuth (CIBA, Auth Code+PKCE, JWT-bearer)
          ▼
 ┌─────────────────────────── sas-camara (sas-host, :8085) ───────────────────────────┐
 │ CAMARA API gateway: OAuth AS (ES256), CAMARA APIs, consent, assurance scoring, CDR  │
 │ NO MAP · NO Diameter · NO SCTP · NO TS.43 · no token key or ledger                  │
 └─────────────────┬───────────────────────────────────────────────────────────────────┘
                   │ subscriber exposure API (REST, 3GPP TS 29.522/29.122-inspired):
                   │ ue-id/retrieve · monitoring-event · sim-change/retrieve · operator-token/redeem
 ┌─────────────────▼───────────────── sas-ecs (sas-ecs-host, :8095) ──────────────────┐
 │ entitlement server: TS.43 /ts43 (EAP-AKA), /session-tuple, token issue + single-use │
 │ redemption, Subscriber Services (source choice), operator adapter RAs               │
 └─────────────────┬───────────────────────────────────────────────────────────────────┘
        UE ─TS.43─►│ MAP over SCTP/M3UA (SAI · SendIMSI · PSI) · Diameter over SCTP (S6a · SWx · Gx)
                   ▼
            own HLR/AuC · HSS · PCRF · 3GPP AAA        (never interconnect ATI, never SRI-SM)
```

Both binaries are **micro-jainslee** applications (RAs + SBBs inside one container per
binary) on **Java 25 / Quarkus**. REST layers only submit events and wait; signalling,
timers and sockets live in resource adaptors under `/ras/`. Machine-checked by the
harness gates (H24 container boundary, H25 binary split + dependency closure, H28
contract parity) and by `scripts/check-dependency-boundary.sh` / `check-no-sctp.sh`.

## Repository layout — what every directory does

### Maven reactor (Java 25, built by the root `pom.xml`, in dependency order)

| Module | Packaged as | What it does | Key contents | Depends on | Must never |
|---|---|---|---|---|---|
| **`sas-common/`** | library (both binaries) | The shared kernel: the verification model and the rules every module must agree on. | `model/` — `ResolverResult`, `VerificationEvidence`, `LocationEvidence`, `VerifyResult`, `FallbackReason`, `AccessTech`, `AssuranceLevel`, `LabLocation`; `fsm/SasTimeouts` (300 ms resolver, 2 s MAP/Diameter, 3 s total); `id/PhoneNumberNormalizer` (the **only** E.164 ↔ TS 23.003 digits ↔ GPSI converter); `ras/resolver/ResolverBackend` + lab `InMemoryResolverBackend`; `security/ApiKeyAuthenticator`. | Quarkus Arc, Log4j2 API | expose a REST endpoint; import either side |
| **`sas-token/`** | library (both) | JOSE primitives for tokens: ES256 JWS and the **operator token** profile (ES256 JWS nested in ECDH-ES/A256GCM JWE, ≤ 300 s, `typ=operator-token+jwt`). | `Es256Jws`, `Es256Keys`, `OperatorToken` (`issue`, `verify`, `Policy`). | Nimbus JOSE | depend on any SAS module |
| **`sas-subscriber-api/`** | library (both) | The **subscriber exposure contract in Java** — the boundary between gateway and entitlement server (ADR R1). Pure Java, no I/O. | `SubscriberService` (one async method per operation), `ExposureApi` (paths, headers, scopes, enums), `ExposureCause` (problem codes + HTTP status), `ExposureOutcome` (value xor problem), `ExposureContext` (`X-Request-Id`, `X-Sas-Budget-Ms`), `ExposureJson` (strict JSON), `ExposureRequestValidator`, `dto/` (records with the 3GPP field model). Contract test A validates every DTO against the OpenAPI file. | sas-common, Jackson | carry a transport or a REST resource |
| **`sas-operator-adapter/`** | library (**sas-ecs only**) | Every way to talk to the operator core — the "operator adapter layer". Each RA is a micro-jainslee resource adaptor: endpoint + delegate + backend + commands. | `ras/mapverifier/` (jSS7 PSI + SAI, never ATI; also the MAP auth-vector source); `ras/authvector/` (SAI or SWx MAR vectors for EAP-AKA); `ras/binding/` (IMSI ↔ MSISDN: MAP SendIMSI on SSN 3, read-only export); `ras/resolver/` (PCRF Gx/Sd probe, RADIUS accounting listener, CGNAT log tail); `ras/s6averifier/` (Diameter S6a ULR + Sh UDR); `ras/swxverifier/` (Diameter SWx MAR/SAR); `config/SasTransportConfig`, `diameter/DiameterConfig`. In-memory lab backends sit next to the real ones. | sas-common, jSS7 (coral-valley), SCTP, corsac-diameter | be reachable from sas-camara (H25 closure check) |
| **`sas-api/`** | library (**sas-camara only**) | The CAMARA gateway's northbound and policy logic. | `api/` — `VerifyResource` (`/verify`, CAMARA NV), `SasVerifyEngine` (the seam the REST layer submits to), `ExposureAdapters` (**one** mapping of exposure answers → resolver result / evidence; location policy), tenants/audit; `camara/` — `/camara/*` aliases, `x-correlator`, CAMARA error shapes; `oauth/` — the OAuth AS: `/authorize` (+PKCE), `/bc-authorize` (CIBA), `/token`, discovery, JWKS, `private_key_jwt`, scope policy, `OperatorTokenAnchor` (forwards tokens to the ECS); `simswap/`, `otpsms/` — CAMARA SIM Swap and OTP SMS; `fsm/AssurancePolicy` (weights, thresholds); `security/` — token validation, replay guard; `events/VerifyRequestEvent`. | sas-common, sas-token, sas-subscriber-api | import MAP/Diameter/TS.43 code (H25) |
| **`sas-entitlement/`** | library (**sas-ecs only**) | The TS.43 entitlement service and the operator token's whole lifecycle. | `api/Ts43Resource` (`/ts43` challenge/respond), `entitlement/eap/` (EAP-AKA server, AKA/AKA′ keys — **codec not yet RFC 4187, P1 blocker**), `EntitlementTokenService` (issues tokens for the ECS itself), `OperatorTokenRedemption` (decrypt, verify, single use, `(jti, X-Request-Id)` idempotency), `OperatorTokenLedger` port, `EcsTokenKeys`, `EntitlementResource` (lab `/entitlement/*`), `lab/` (lab SIM card). | sas-common, sas-token | import CAMARA code (H25) |
| **`sas-subscriber-exposure/`** | library (**sas-ecs only**) | The entitlement server's REST faces that are not TS.43. | `exposure/SubscriberExposureResource` (`/sas-subscriber-exposure/v1/**`, validated against the contract, waits within the caller budget), `ExposureCoordinator` (dedup by operation + request id + body), `ExposureConfig`, `SessionTupleResource` (`/session-tuple`, device side, cellular-only), `ResolverAccess`; `events/SubscriberQueryEvent`. | sas-subscriber-api, sas-common | own signalling (the SBB does) |
| **`sas-host/`** | **binary sas-camara** (:8085) | Assembles the CAMARA gateway. | `bootstrap/SasBootstrap` (the only micro-jainslee seam: wires `exposure-ra`, `VerifySbb`); `sbbs/VerifySbb` (UE ID → monitoring ∥ SIM change → `fsm/VerificationFsm`); `ras/exposure/` (`ExposureClient` = JDK HTTP client implementing `SubscriberService`, `ExposureRaEndpoint`); `cdr/` + `persist/` (CDR and tenants, Hibernate/Panache, Flyway `db/migration` V1–V4); `admin/`, `web/`, `app/html/` (admin UI); `tenant/`, `simswap/HostSimSwapEvidence`, `otpsms/LabLogSmsDelivery`. Config: `application.properties` (lab), `application-prod.properties`. | sas-api, sas-subscriber-api | ship a MAP/Diameter/SCTP jar (artifact check) |
| **`sas-ecs-host/`** | **binary sas-ecs** (:8095) | Assembles the entitlement server — the only binary that signals the core. | `bootstrap/EcsBootstrap` (the only seam: auth-vector, binding, resolver, MAP, S6a RAs on **one** jSS7 stack; implements `SasEntitlementEngine`, `SubscriberService`, `ResolverAccess`); `sbbs/EntitlementSbb` (EAP-AKA FSM), `sbbs/SubscriberSbb` (the four exposure operations, source choice, budgets, dialog abort); `coordinator/`; `persist/JdbcOperatorTokenLedger` + Flyway V1 `ecs_operator_token_jti`; `ss7-ecs.json` (jSS7 stack sample). | sas-entitlement, sas-subscriber-exposure, sas-operator-adapter | expose a CAMARA endpoint (H25) |

### Standalone builds (`cd` in and build separately)

| Directory | What it is |
|---|---|
| `sas-jss7-testapp/` | Lab **home-HLR simulator** (jSS7 coral-valley): answers MAP SAI (quintuplets), SendIMSI and PSI over SCTP :2906; control/log HTTP on :8087 (`/subscribers`, `/state`, `/messages`). |
| `sas-diameter-testapp/` | Lab **HSS / 3GPP AAA / PCRF (Gx)** simulator (corsac-diameter) over SCTP :3868, control UI :8086 (`/api/messages`, `/api/subscriber`, `/api/binding`). Run several instances on other ports for SWx/Gx. |
| `ue-sdk/` | Device-side tuple SDK for the JVM (Java 25): declares `AccessTech`, posts the session tuple, `CellularRequirement` fails closed. |
| `ue-sdk-android/` | Android SDK: pins its own socket to the cellular `Network`, reads the radio via `TelephonyManager`. |
| `ue-sdk-ios/` | Swift SDK: `NWPathMonitor` + CoreTelephony bearer gate (URLSession cannot be pinned — documented). |
| `ue-sdk-web/` | Browser SDK: observes `navigator.connection`, refuses before `fetch` when not cellular. |

### Tooling, gates and documents

| Path | What it holds |
|---|---|
| `harness/` | `gates.yaml` (H1–H28, each anchored to a 3GPP/GSMA/CAMARA clause) + `run_hardness.py` (runner; `--mutations` proves H24/H25/H28 bite); `preflight_prod.py` (static prod verdict per binary: `--service camara|ecs|all`, `--selftest`); `mut_*.py` mutation suites. `harness/oracle/` is a local research folder, git-ignored. |
| `scripts/` | `package-dist.sh [camara|ecs|all]` (Quarkus fast-jar dists → `dist/`, `dist-ecs/`), `run.sh` (dist launcher; only sas-ecs loads `jdk.sctp`), `dist-README.md`; smokes: `split-smoke.sh` (both binaries, CAMARA flows, gateway owns no SCTP), `exposure-smoke.sh` (exposure API vs HLR sim), `ecs-map-smoke.sh` (TS.43 over MAP + fail-closed matrix), `layered-e2e.sh` (packaged dists + HLR sim + Diameter testapp, live and lab phases), `ts43-lab-demo.sh`; checks: `check-dependency-boundary.sh`, `check-no-sctp.sh`; GSMA helpers: `gsma_camara_smoke.py`, `gsma_mock_server.py`, `gsma.env.template`, `sas_oauth_jwt_tool.py` (`private_key_jwt` assertions). All smokes run in a private user + network namespace (no root) and write a pcap. |
| `docs/design/` | Architecture and protocol design: `sas-layered-architecture.md` (ADR, decisions D1–D8/R1–R10, migration M0–M6), `openapi/sas-subscriber-exposure-v1.yaml` (contract), `silent-auth-standard-flow.md` (+`.en.md`), `unified-identity-sms-security-architecture.md`, `3gpp-spec-coverage.md`, `cellular-bearer-login.md`, `hardness.md` (gate table), `entitlement-core-nw-plan-2.md` (TS.43 entitlement plan), `ts43-*.md`; `sas-two-binary-architecture.md` is superseded. |
| `docs/research/` | Spec notes that the gates cite (paraphrased, never copied): MAP TS 29.002, S6a TS 29.272, SWx/S6b TS 29.273, EAP-AKA TS 33.402, SGd TS 29.338, N32 TS 33.501, CAMARA NV/ICM/SIM Swap/OTP SMS, GSMA FS index, SMS channel protection. |
| `docs/test/` | Step-by-step test flows and demo scripts, **one language per file**: `testflow.vi.md` / `testflow.en.md`, `demo-script-…vi.md` / `.en.md` (the plain `.md` files are indexes). |
| `docs/lesson_learn.md` | Lessons learned (Vietnamese): flows, pitfalls found while building and testing. |
| `docs/result_p*.md`, `docs/P2-missing_item.md` | Audit and gap reports per phase. |
| `proposal/` | Formal DOCX proposal (chapters + build script). |
| `LICENSE.md`, `LICENSES/` | Dual-licence notice and the verbatim AGPL-3.0 text. |
| `AGENTS.md`, `.clinerules`, `mise.toml` | Agent rules for this tree, scope discipline, toolchain pin (Java 25, Maven 3.9.9). |
| `slides/`, `dist/`, `dist-ecs/`, `*/target/`, `*/data/` | Local artifacts — git-ignored, never committed. |

## Build, run, verify

```bash
mvn -o test                                   # 9 modules, JDK 25 (mise: zulu-25)
./scripts/package-dist.sh all                 # dist/ (sas-camara) + dist-ecs/ (sas-ecs)
dist-ecs/run.sh & dist/run.sh                 # lab: sas-ecs :8095, sas-camara :8085
scripts/split-smoke.sh                        # CAMARA flows across both binaries + pcap
scripts/layered-e2e.sh dist dist-ecs          # packaged dists + HLR sim + Diameter testapp
python3 harness/run_hardness.py               # 38/38 gates (H1–H28)
python3 harness/run_hardness.py --mutations   # H24 10/10 · H25 11/11 · H28 8/8
python3 harness/preflight_prod.py --service all   # prod verdict for this environment
scripts/check-dependency-boundary.sh          # gateway ships no MAP/Diameter/SCTP jar
```

Lab configuration can be overridden with `-D…` or environment variables (e.g.
`SAS_TRANSPORT_MAP=jss7`) — the dist file has the normal configuration ordinal.

## Call flows

- [`docs/design/sas-layered-architecture.md`](docs/design/sas-layered-architecture.md) §6 —
  NV cellular (Authorization Code), NV Wi-Fi (TS.43 → CIBA operator token), SIM Swap, TS.43.
- [`docs/design/silent-auth-standard-flow.md`](docs/design/silent-auth-standard-flow.md) —
  banking E2E, SAS FSM, timeout table.
- [`docs/design/unified-identity-sms-security-architecture.md`](docs/design/unified-identity-sms-security-architecture.md) —
  access-tech decision, fail-closed pipeline, Strategy A/B rollout.
- [`docs/design/camara-flow-diagram.svg`](docs/design/camara-flow-diagram.svg) — CAMARA NV gateway flow.

## Signalling used (all from sas-ecs, own network only)

| Interface | Operation | Used for |
|---|---|---|
| MAP (TS 29.002) | **PSI** | reachability + location evidence metadata (monitoring) |
| MAP | **SAI** | EAP-AKA vectors for `/ts43` |
| MAP | **SendIMSI** (SSN 3) | MSISDN ↔ IMSI confirmation (binding, monitoring) |
| MAP | ATI / SRI-SM | **never** — FS.11 Category 1 / Home Routing correlation IDs |
| Diameter S6a (TS 29.272) | ULR/ULA (+ read-only Sh UDR) | LTE monitoring path |
| Diameter SWx (TS 29.273) | MAR/SAR | EAP-AKA vectors when the operator exposes SWx |
| Diameter Gx/Sd | CCR binding probe | IP:port:ts → subscriber (resolver) |

## UE SDKs (device side)

Four standalone artifacts post the session tuple to the SAS. Build output
(`target/`, `.build/`, `node_modules/`) and the local H2 database
(`sas-host/data/`) are git-ignored — never commit them to a public tree.

| Module | Language / toolchain | Cellular 2G/3G/4G/5G login |
|--------|----------------------|----------------------------|
| `ue-sdk` | Java 25 (`mvn`) | declares `AccessTech` from the interface name; `CellularRequirement` fails closed; `Connector` seam for platform pinning |
| `ue-sdk-android` | Java 8 (`mvn`, minSdk 24, no `android.jar` dep) | **pins its own socket** to the cellular `Network` (`CellularBearer`), reads radio via `TelephonyManager`, `requestNetwork` for app-owned binding |
| `ue-sdk-ios` | Swift (SPM, iOS 14+) | `NWPathMonitor` + CoreTelephony gate; URLSession cannot pin (documented limitation), fails closed on Wi-Fi |
| `ue-sdk-web` | ES2020 (`node --test`) | observation only (`navigator.connection`); `requireCellular` refuses before `fetch` |

```bash
python3 harness/run_hardness.py          # incl. H22 bearer parity + H23 license parity
(cd ue-sdk && mvn -o test)               # JDK 25 (mise: zulu-25)
(cd ue-sdk-android && mvn -o test)
(cd ue-sdk-web && node --test)
(cd ue-sdk-ios && swift test)            # macOS/Xcode only
```

See [`docs/design/cellular-bearer-login.md`](docs/design/cellular-bearer-login.md).

## Design documents

- [`docs/design/sas-layered-architecture.md`](docs/design/sas-layered-architecture.md) — the ADR.
- [`docs/design/openapi/sas-subscriber-exposure-v1.yaml`](docs/design/openapi/sas-subscriber-exposure-v1.yaml) — exposure contract v1.0.0.
- [`docs/design/hardness.md`](docs/design/hardness.md) — the gate set and how to run it.
- [`docs/design/3gpp-spec-coverage.md`](docs/design/3gpp-spec-coverage.md) — stage → spec → message coverage.
- [`docs/design/cellular-bearer-login.md`](docs/design/cellular-bearer-login.md) — UE SDK bearer pinning matrix.
- [`docs/design/entitlement-core-nw-plan-2.md`](docs/design/entitlement-core-nw-plan-2.md) — TS.43 entitlement service plan (D6 Shape S).
- [`docs/result_p1_reaudit.md`](docs/result_p1_reaudit.md) — what is production-gated today.

## Security research

Two strategies protect the same phone-number identity from opposite sides — silent auth
removes SMS where it can; a signalling firewall protects the SMS that must still be sent.

- [`docs/research/sms-channel-protection.md`](docs/research/sms-channel-protection.md) —
  SS7 (Home Routing / SRI-SM filtering / MT-spoofing / Double MAP), Diameter (S6c/S6a/SGd
  via DEA), 5G (SEPP/N32/PRINS/SMSF); appendix maps controls to jSS7 `service/sms` classes.
- [`docs/research/gsma-fs-index.md`](docs/research/gsma-fs-index.md) — FASG document index
  (FS.07/11/19/20/21/31/36, SG.22, FF.09) + FS.11 MAP categorisation.
- [`docs/research/3gpp-spec-reference-index.md`](docs/research/3gpp-spec-reference-index.md) —
  index of the 3GPP specs the SAS depends on (TS 29.002, TS 29.272, TS 33.402).
- [`docs/research/3gpp-ts29-002-map.md`](docs/research/3gpp-ts29-002-map.md) — MAP
  (PSI/ATI/SAI/SRI-SM) operation & ASN.1 notes for the 2G/3G Verifier.
- [`docs/research/3gpp-ts29-272-s6a.md`](docs/research/3gpp-ts29-272-s6a.md) — Diameter
  S6a/S6d (ULR/PUR) + Sh UDR/SNR command reference for the LTE Verifier.

## Proposal DOCX

[`proposal/Restlink_Silent_Auth_Proposal_v3.docx`](proposal/Restlink_Silent_Auth_Proposal_v3.docx) —
~50-page formal proposal derived from `slides/Restlink_Silent_AuthProposal_v3.pptx`:
fraud/UN–ITU evidence, two-stage MAP/Diameter design, CAMARA + GSMA FASG tables,
commercial ROI (illustrative), roadmap. Chapters in `proposal/chapters/`; rebuild via
`python3 proposal/scripts/build_proposal_docx.py`.

## Pitch decks (local artifacts)

`slides/` is gitignored. These decks and generator scripts live only in the local workspace
and are not tracked repo links:

- `slides/Restlink_Silent_Auth_Mix_v3.pptx` — recommended Mix v3 deck: government/bank story,
  MAP/Diameter technical detail, CAMARA API table, GSMA FASG security tables.
- `slides/Restlink_Silent_Auth_Technical_v2.pptx` — technical message-flow deck.
- `slides/Restlink_Silent_Auth_Ethiopia.pptx` — bank pitch deck.

When the local generator scripts are present:

```bash
python3 slides/scripts/generate_svgs.py      # v1 story assets
python3 slides/scripts/generate_svgs_v2.py   # v2 protocol diagrams
python3 slides/scripts/build_pptx_v3.py      # Mix v3; v2/v1 use build_pptx_v2.py / build_pptx.py
```

## Status

- [x] Two binaries split by layer (M0–M6): CAMARA gateway signals nothing; sas-ecs owns
      MAP/Diameter, TS.43 and token redemption (durable ledger).
- [x] CAMARA NV v2.1.0 (incl. Authorization Code + PKCE, ES256 tokens), SIM Swap v2.1.0,
      OTP SMS v1.1.1 (lab sender, prod off), ICM OAuth (CIBA, client credentials, JWT bearer).
- [x] Subscriber exposure API v1.0.0 (3GPP-inspired), contract tests + gate H28.
- [ ] **M4** — mTLS + certificate-bound client credentials on the exposure hop; until then
      the prod profile keeps the exposure surface off, so the pair is not production-deployable.
- [ ] EAP-AKA wire codec to RFC 4187 (P1 blocker); `/ts43` → GSMA TS.43 wire + abuse controls.
- [ ] A live SIM-change evidence source (SIM DB / Sh UDR); monitoring with PSI only (no SAI).
- [ ] Resolver source per operator; post-CGNAT `srcPort` discovery; assurance weights.
- [ ] Refresh tokens; shared store for OAuth codes / CIBA bindings; OTP SMS delivery via SGd.

## Proposal DOCX

[`proposal/Restlink_Silent_Auth_Proposal_v3.docx`](proposal/Restlink_Silent_Auth_Proposal_v3.docx) —
formal proposal; chapters in `proposal/chapters/`; rebuild via
`python3 proposal/scripts/build_proposal_docx.py`. Pitch decks live in the git-ignored
`slides/` workspace.

## License & Commercial Model (dual-license)

**SPDX:** `AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

Silent Authentication (SAS) is released under a **dual-license model**
(MySQL/Sidekiq pattern), both grants held by the copyright owner (Tran Nhan):
one **AGPL-3.0 Community** license and one proprietary **Operator** license.
Full terms: [`LICENSE.md`](LICENSE.md) — notice — plus the verbatim grant text
[`LICENSES/AGPL-3.0.txt`](LICENSES/AGPL-3.0.txt). **Pick exactly one** — they are not
stacked, and the Operator grant is not a supplement to AGPL.

| Edition | License | Who | Gets | Channel |
|---|---|---|---|---|
| **Community** | AGPL-3.0-or-later | Free — **whole repo** (sas-api + sas-entitlement + sas-host + UE SDKs; D1=A) | Source, mods, redistribution — **copyleft incl. network use** (AGPL §13: serve it, publish your changes) | Public source |
| **Operator** | Commercial (owner-held) | Banks / SIs / operators (Restlink deployments) | **Production rights without copyleft** (incl. micro-jainslee, D2) + license key, L1/L2 SLA, training, integration engineering | Private offers, license-key enforced |

Neither edition is a warranty or a compliance claim: `/verify` answers for
**network reachability of the MSISDN**, never for the human behind the app, and
the lab profile (plain HTTP, mock transports, no auth) is **not** a deployable
configuration — see [`harness/preflight_prod.py`](harness/preflight_prod.py).

**Why AGPL-3.0, not GPL/BSL/SSPL:** the SAS Diameter path (S6a/SWx) links a
local fork of Mobius **corsac-diameter, which is AGPL-3.0** — any distributed
combined binary is legally AGPL. AGPL is therefore the only compatible *and*
the monetization lever (same posture as the Mobicents/jSS7 ecosystem). All
other deps (Quarkus, Log4j2, Jackson, jSS7) are Apache-2.0/LGPL —
AGPL-compatible; `micro-jainslee` is in-house and owner-held under the same
dual license (D2).

**UE SDK note (deliberate, not an oversight):** the client SDKs are AGPL too,
so an app that embeds them and ships a modified SDK must publish that source.
A bank that wants a permissive client (Apache-2.0/MIT) takes it through the
**Operator** license — that is the intended commercial boundary, not a fork.

**Open source + revenue ("trả tiền"):** Community edition is the free funnel
(bank/operator onboarding, PoCs); the paid Operator edition earns per-deployment
license revenue (per verify / node / year) through the Restlink VAS model,
supported by L1/L2 SLA, training and integration engineering.

Research & decision records and the commercial / go-to-market plan are
maintained privately (outside this public tree).
Owner decisions (2026-08-28, shared with the Elisa Roaming product family):
**D1 = open everything** (single all-AGPL tree, incl. SAS host);
**D2 = micro-jainslee dual-licensed** (AGPL/commercial);
**D3 = Mobius corsac handled by direct relationship** — no open blocker.