# SAS P1 Re-Audit — from "lab accepts everything" to a gated production profile

> Date: 2026-08-30
> Scope: re-audit of `result_p1.md` §2 (deferred items) **plus** the deployment
> surface that the P1 code changes did not cover — profile, transport, persistence,
> operator identity.
> Constraint honoured: **no Java toolchain work**. Everything here is config
> (`application-prod.properties`), Python (`harness/preflight_prod.py`), gates
> (`harness/gates.yaml`) and docs.

---

## 1. What the re-audit found

P1 closed the *application-layer* controls (token validation, replay/idempotency,
TTL persistence, SWx fail-closed path). The gap that remained was **not** code —
it was that the shipped configuration is a lab configuration, and nothing in the
tree could tell the difference between "deployable" and "accidentally still lab".

| Layer | P1 state | Risk if deployed as-is |
|---|---|---|
| `/verify` auth logic | enforced in code | fine, **but only if** `sas.security.token-validation-enabled=true` |
| Base profile value | `false` (lab) | identity = caller-claimed `phoneNumber` → number-swap fraud |
| API-key tenancy | implemented | `sas.security.enforce-api-keys=false` → every caller is tenant `lab` |
| Transport | HTTP :8085 plain | credentials + MSISDN in cleartext on the bank link |
| Admin | `sas.admin.key=change-me` | full dashboard takeover |
| Signalling | `memory` pilot backends | approvals served from seeded fake data |
| Persistence | H2 file DB in `./data` | single node, no CDR durability, no replication |

The application is already fail-closed *at runtime* (blank secret ⇒ reject all,
unknown transport ⇒ memory). Runtime refusal is not good enough for a pilot: it
looks like an outage. The missing piece was a **pre-boot gate with a readable verdict**.

---

## 2. What was added

### 2.1 `sas-host/src/main/resources/application-prod.properties`

Quarkus `prod` profile (`QUARKUS_PROFILE=prod`). Design rule: **the file carries no
lab defaults**. Every credential and operator-specific value is
`${ENV_VAR}` **without** `:default`, so a missing secret fails the config read at
boot instead of silently falling back.

| Area | Prod value | Why it is pinned |
|---|---|---|
| Cleartext | `quarkus.http.insecure-requests=disabled` | the socket answers nothing (vs `redirect`, which still accepts plain requests) |
| TLS | keystore/truststore path + passwords env-only, `ssl-port=8443` | no keystore shipped in the repo |
| mTLS | `quarkus.http.ssl.client-auth=${SAS_TLS_CLIENT_AUTH:required}` | FS.11 §3.3.4 — the bank identity must come from the cert, not a header |
| Auth | `token-validation-enabled=true`, `hmac-secret`, pinned `iss`/`aud` | signature + claims enforced |
| Scopes | `required-scopes` **stays empty** | it is a global AND-gate; the per-endpoint CAMARA scopes are enforced in `VerifyResource`/`TokenValidator` |
| Tenancy | `enforce-api-keys=true`, `sas.tenants.api-keys=${SAS_TENANT_API_KEYS}` | billing + tenant-scoped CDRs are meaningless without it |
| Windows | clock-skew 30 s, replay 120 s, reqId TTL 600 s | bounded replay surface, dedup outlives the window |
| Admin | rotated key + session secret env-only, `cookie-secure=true`, bcrypt cost 12 | closes `change-me` / `dev-session-hmac` |
| MAP | `sas.transport.map=jss7` + stack JSON + own HLR/local GT | real PSI/SAI against the own HLR; never ATI |
| S6a/SWx | `sas.transport.s6a=corsac`, `sas.transport.swx=corsac` + realm/host env-only | loopback lab peer rejected |
| Resolver | `sas.transport.resolver=${SAS_TRANSPORT_RESOLVER}` ∈ `radius\|cgnat\|sd` | operator decision stays an operator input, but a *typo* is refused |
| RADIUS | `resolver.radius.secret=${SAS_RADIUS_SECRET}` (no default) | an empty secret disables `Message-Authenticator` (RFC 2869 §5.7) → spoofable bindings |
| TS.43 | `require-signed=true`, `issue-attestation-required=true`, TTL 300 s | unsigned entitlement = anyone claims any MSISDN over Wi-Fi |
| OAuth | `sas.oauth.secret=${SAS_OAUTH_SECRET}` | blank makes issuance throw |
| Persistence | PostgreSQL + `database.generation=none` + `flyway.migrate-at-start=true` | schema owned by migrations, not Hibernate |
| CDR | `sas.cdr.enabled=true`, `sas.cdr.db.enabled=true`, `sas.log.dir` absolute | auditability (P-H6) |
| Contract | `sas.api.assurance-detail-enabled=false` | internal evidence is not in CAMARA NV; opt-in per request only |

**Known gap (P-H1b):** Quarkus core (vertx-http 3.37.3) does **not** emit
`Strict-Transport-Security`. HSTS must be terminated at the edge proxy
(`max-age=31536000; includeSubDomains; preload`) until an app-level response
filter lands — tracked in `sas-host/TODO.md`.

### 2.2 `harness/preflight_prod.py` — 28 static checks, pre-boot

Merges base + prod properties, expands `${VAR}/${VAR:default}` against the real
environment, and asserts 28 invariants (PRO-01…PRO-28) in five families:
gate integrity (01–03), northbound auth (04–10), transport security (11–13),
admin (14–16), signalling truthfulness (17–21), entitlement/OAuth/persistence/ops
(22–28). Design notes:

- **Coverage guard (PRO-02):** a 30-key `CRITICAL_KEYS` list — if a key is dropped
  from the prod file, the lab default silently wins, so "overridden every critical
  key" is itself a check.
- **No secret disclosure:** failures print key names, lengths and SHA-256
  fingerprints — never values.
- **Environment-aware:** `--env KEY=VALUE` drills a deployment without touching
  the real env; `--set key=value` drills profile edits; `--no-file-checks` skips
  on-disk existence (CI without the operator's keystores).
- **Exit code = number of failed checks.**

```bash
python3 harness/preflight_prod.py                 # verdict for this machine's env
python3 harness/preflight_prod.py --json           # CI-consumable
python3 harness/preflight_prod.py --selftest       # prove the gate bites
```

Without secrets provisioned the gate reports `12/28 pass, 16 fail` and exits 16 —
i.e. it *refuses*, and names every missing variable — instead of booting a
reject-all-auth, memory-transport, H2-backed process.

### 2.3 Harness gates H15–H21 (`gates.yaml` + `run_hardness.py`)

New checker `preflight` drives `preflight_prod.verify(scenario)`, which applies a
deliberate misconfiguration to a synthetic complete deployment and requires the
expected `PRO-xx` ids to fire. Gate totals moved **24 → 31** (10 contract checks +
21 gates, H1–H21), all passing, exit 0.

| Gate | Assertion |
|---|---|
| H15 | baseline: a fully provisioned prod profile passes all 28 checks (the gate is not vacuous) |
| H16 | auth cannot be softened: validation off, enforcement off, over-pinned scope, duplicate tenant key, dropped/short/unset secret |
| H17 | cleartext HTTP, `client-auth=none`, lab keystore path are refused |
| H18 | no fake signalling: memory resolver, loopback Diameter peer, missing jSS7 stack file |
| H19 | TS.43 stays signed + attested |
| H20 | admin/persistence leave the lab: `change-me`, insecure cookies, H2, relative log dir |
| H21 | quota bound to a real tenant; assurance detail not forced on globally |

Negative control: flipping `token-validation-enabled=true → false` in the prod
file makes H15 fail (`30/31`) and `--selftest` report `21/22`. Restoring it
returns `31/31` — the gate is attached to the real artifact, not to a fixture.

---

## 3. What this does **not** prove

The preflight is static analysis of config + environment. It cannot and does not
assert runtime behaviour. Still open before a real pilot:

| # | Open item | Why the gate cannot close it |
|---|---|---|
| 1 | **Live mTLS handshake** | needs a running listener + a bank client cert; only the *configuration* is asserted |
| 2 | **HSTS** | not a Quarkus core property in 3.37.3 — edge proxy or app filter (P-H1b) |
| 3 | **Real SS7/MAP dialog** | PRO-18 checks the stack JSON parses and GTs differ; it never sends a PSI/SAI. UAT against the own HLR/HSS is still required (P-H5) |
| 4 | **Diameter watchdog / peer reachability** | config-shaped only; no peer connection is opened (P-H4) |
| 5 | **PostgreSQL / Flyway migration run** | URL + credentials are asserted; `V1__baseline.sql` applying cleanly on the operator DB is not |
| 6 | **Secret provenance** | the gate requires env-sourced values; it cannot tell a Vault-injected secret from one pasted into a shell profile |
| 7 | **Key lifecycle (P-H3)** | rotation/revocation, per-key scopes, `maxTps` enforcement on `/verify` remain code work |
| 8 | **Login rate limiting, metrics/alerting (P-H7)** | observability work, out of scope for a config gate |
| 9 | **Assurance weights / thresholds** | still the design placeholder from `AGENTS.md` §10 |

Policy caveats worth stating plainly:

- The `prod` profile is **inert unless `QUARKUS_PROFILE=prod`** (or
  `-Dquarkus.profile=prod`) is set. An orchestrator that forgets the env var gets
  the lab profile — so the preflight must run in the release pipeline, not only on
  a laptop.
- Container images: if the keystore is mounted rather than baked in, the file
  existence check must run **inside** the target container
  (`--no-file-checks` is for the CI host, not a licence to skip the in-container pass).
- `micro-jainslee` remains R&D-only (workspace rule); a production SAS build must
  use Mobicents SLEE container master-era JARs. Nothing in this change alters that.
- Ports stay loopback-bound by default (`sas.http.port=8085`, `ssl-port=8443`) per
  the workplace resource-hygiene rule; publishing them is an explicit infra decision.

---

## 4. Verdict

**Production *configuration* is now gated; production *readiness* is not yet claimed.**

- ✅ The `prod` profile exists, carries no lab defaults, and cannot boot with a
  missing secret (no `:default` fallbacks).
- ✅ 28 preflight checks + 7 new harness gates (H15–H21) refuse every
  lab-shaped deployment state that P1 left behind, with a readable verdict and a
  non-zero exit code before the JVM starts.
- ✅ `python3 harness/run_hardness.py` → **31/31 pass**, exit 0.
- ⛔ **Not** production-ready until: HSTS at the edge, live mTLS + SS7/Diameter
  UAT against the operator's own HLR/HSS/AAA/PGW, Flyway applied on the real DB,
  and P-H3/P-H7 lifecycle + observability work land.

The distinction this change makes possible: *"lab-accepts-all"* and
*"production-locked"* are now different, *checked* artifacts — instead of one
binary whose safety depends on someone remembering to set five properties.
