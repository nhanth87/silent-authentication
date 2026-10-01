# Demo Script — UE SDK → CAMARA `/verify` → Entitlement (TS.43) → S6a (cellular) + SWx (lab) (English)

> **Layered architecture (M3+).** sas-camara no longer runs any signalling transport: the
> S6a/SWx/Gx and resolver settings below now belong on **sas-ecs**, and operator tokens are
> redeemed at sas-ecs. The verified step-by-step for today's binaries is
> [`testflow.en.md` §00](testflow.en.md); sections below that start sas-camara with
> `-Dsas.transport.*` are pre-M3 and were not re-run.

> 🇻🇳 Vietnamese version: [`demo-script-ue-camara-entitlement-diameter.vi.md`](demo-script-ue-camara-entitlement-diameter.vi.md)

> End-to-end demo script for Restlink Silent Auth (Ethiopia): from the **UE SDK**
> collecting the session tuple, through the **CAMARA Number Verification** northbound,
> through the **TS.43 entitlement** track (Wi-Fi), down to the **Diameter** layer using
> `sas-diameter-testapp`.
>
> Since **2026-10-01** the SAS is **two binaries**: **sas-camara** (`sas-host`, :8085 —
> CAMARA + OAuth AS) and **sas-ecs** (`sas-ecs-host`, :8095 — entitlement server). An
> operator token minted by sas-ecs (JWE) is redeemed by sas-camara **exactly once**. The
> two tracks differ: **S6a + Gx** = cellular (freshness = **Sh UDR/SNR**); **TS.43 Wi-Fi**
> = entitlement (sas-ecs: EAP-AKA over MAP/SWx; the lab SWx :3869 only stands in for the
> operator leg of sas-camara's verifier).
>
> Companions: `docs/design/sas-two-binary-architecture.md` (two-binary architecture),
> `docs/design/silent-auth-standard-flow.md` (normalised flow) and
> `docs/test/testflow.en.md` (full E2E matrix, NV Authorization Code, MAP + pcap).

- Date: 2026-09-01 · two-binary update: 2026-10-01
- Simulator: `sas-diameter-testapp` (simulated HSS + 3GPP AAA + PCRF·Gx, SCTP).

---

## Overview

```
UE SDK (device)                    sas-camara :8085                  sas-diameter-testapp (operator simulator)
  │  POST /session-tuple             │  RESOLVING→VERIFYING→SCORING    ├─ inst1 S6a :3868 (ULR/ULA) — cellular
  │  X-Sas-Access-Tech: LTE          │                                 ├─ inst2 SWx :3869 (MAR/SAR) — lab, operator leg
  │─────────────────────────────────►│──── Diameter (SCTP) ───────────►├─ inst3 Gx  :3870 (CCR-I binding) — resolver
  │  POST /number-verification/v2/verify                              │  UI: /api/messages,
  │  {phoneNumber}                   │◄── ULA · MAA/SAA · CCA ────────┤      /api/subscriber, /api/binding
  │◄──────── {devicePhoneNumberVerified}
  │
  │  (Wi-Fi) EAP-AKA ─► sas-ecs :8095 ── operator token (JWE) ─► app ─► bank ─► sas-camara (redeemed once)
```

Demo goals:

1. The UE SDK declares a cellular bearer (`accessTech`) on `/session-tuple` → sas-camara
   refuses Wi-Fi tuples (`400 ACCESS_TECH_NOT_CELLULAR`).
2. CAMARA `/verify` + `/device-phone-number` over cellular → Diameter **S6a** + **Gx** Resolver.
3. TS.43 entitlement over Wi-Fi → **sas-ecs** `/entitlement/issue` → sas-camara `/verify`
   with `operatortoken:` → (lab) RA `swxverifier` opens **SWx** in place of the operator leg.
4. Live signalling observation + fail-closed matrix.

---

## 0. Environment

- JDK zulu-25: `$HOME/.local/share/mise/installs/java/zulu-25`.
- Always build with `/usr/bin/mvn` (the `mvn` on PATH is a mise shim bound to zulu-8 — do NOT use it directly).
- Set once, then `java`/`javac` are zulu-25:
  ```bash
  export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"
  export PATH="$JAVA_HOME/bin:$PATH"   # after this line `java`, `javac` = zulu-25
  /usr/bin/mvn -version                # must print "Java version: 25.0.3"
  ```
- Never run **two `mvn` at once** (a `~/.m2` race produces bogus compile errors such as
  `illegal start of expression` on valid source) — build **sequentially**.

## 1. Build

```bash
# Build SEQUENTIALLY (never in parallel). Run from the worktree root.

# 1) simulated HSS/AAA/PCRF testapp → target/sas-diameter-testapp.jar (~26 MB)
(cd sas-diameter-testapp && /usr/bin/mvn -B clean package)

# 2) root reactor (7 modules) → two fast-jars:
#      sas-host/target/quarkus-app/quarkus-run.jar      (sas-camara)
#      sas-ecs-host/target/quarkus-app/quarkus-run.jar  (sas-ecs)
#    -Dquarkus.profile=lab is MANDATORY: without it the fast-jar silently fails to boot
#    (stops after `MicroSleeContainer started`, log full of `Failed to load config value…`)
/usr/bin/mvn -B clean package -DskipTests -Dquarkus.profile=lab
```

## 2. Run the testapp + sas-camara (corsac transports) + sas-ecs

```bash
# Instance 1: S6a HSS (SCTP :3868) + control UI :8086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA↔HSS (SCTP :3869) + control UI :18086 — lab
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &

# Instance 3: PCRF Gx (SCTP :3870) + control UI :28086  (used when resolver=sd)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3870 --web-port 28086 &
```

> The lab HSS accepts only **1 inbound SCTP association per listen port** → S6a/SWx/Gx need
> separate ports; sas-camara points at them with `-Dsas.transport.diameter.swx.peer-port=3869`
> and `-Dsas.transport.sd.peer-port=3870`.

```bash
# sas-camara (:8085)
(cd sas-host && java \
  -Dsas.transport.s6a=corsac \
  -Dsas.transport.swx=corsac \
  -Dsas.transport.diameter.swx.peer-port=3869 \
  -Dsas.transport.resolver=sd \
  -Dsas.transport.sd.peer-port=3870 \
  -jar target/quarkus-app/quarkus-run.jar &)

# sas-ecs (:8095) — entitlement server, for scenario C
(cd sas-ecs-host && java -Dquarkus.profile=lab --add-modules jdk.sctp \
  -jar target/quarkus-app/quarkus-run.jar &)
```

Wait for `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` — usually
~15–20 s, but on a multi-homed host it can take up to ~3 minutes (the sim only accepts a
loopback source; sim log: `Received connect request from non provisioned … address` until a
retry picks 127.0.0.1). `resolver=sd` enables the PCRF Gx binding probe (:3870); drop that
line to use the memory resolver. Resolver choices: `memory | cgnat | radius | sd`.

## 3. Scenario A — UE SDK cellular + CAMARA verify (S6a + Gx)

Resolver `sd` pre-seeds the binding `10.20.30.40:55555 → +251911111111` (IMSI
`655010000000001`) in the Gx BindingRegistry of instance :3870 (`POST /api/binding` to
change it). The UE SDK on the device declares its bearer + tuple via `/session-tuple`.

> Scenarios A and B live entirely inside sas-camara; their code path did not change with
> the binary split (last verified on a dist: 2026-09-03).

### 3.1 UE SDK declares the tuple (advisory, cellular-only)

```bash
# JVM UE SDK: SessionTupleClient.post(...) → body as below
curl -s -X POST http://localhost:8085/session-tuple \
  -H 'Content-Type: application/json' \
  -d '{"srcIp":"10.20.30.40","srcPort":55555,"ts":1724200000000,
       "msisdn":"+251911111111","accessTech":"LTE"}'
# → {"registered":true}   (resolver=sd: ack + forward to the operator resolver only;
#     the authoritative binding lives in the Gx BindingRegistry, not seeded by this tuple)
```

Wi-Fi refused (gate H22 — a Wi-Fi tuple must never seed a cellular binding):

```bash
curl -s -X POST http://localhost:8085/session-tuple \
  -H 'Content-Type: application/json' \
  -d '{"srcIp":"192.168.1.9","srcPort":0,"ts":1724200000000,"accessTech":"WIFI"}'
# → 400 {"code":"ACCESS_TECH_NOT_CELLULAR", ...}
```

> The device-declared `accessTech` is **advisory**: it is used to *exclude* bad tuples and
> to correlate CDRs, never to raise assurance. Assurance comes from what the operator
> network itself attests (IP:port:ts via PGW/Gx/CGNAT).
>
> With `resolver=memory`, `/session-tuple` itself seeds `InMemoryResolverBackend` (no Gx
> instance needed); the binding is then created directly by the UE SDK.

### 3.2 CAMARA `/verify` (happy path)

> ⚠️ **Actual expectation with `s6a=corsac`**: the lab testapp has **no** Sh UDR handler ⇒
> `CorsacS6aVerifierBackend` fails closed on the SIM-swap dimension ⇒ `/verify` returns
> `false` + `SIM_SWAP_SUSPECT` even though Gx CCA and ULA are both 2001 — that is fail-closed
> working as designed. To demo `true`: run sas-camara without `-Dsas.transport.s6a=corsac`
> (memory pilot).

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: a1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false}
#   Diameter: Gx CCR→CCA 2001 (resolver), S6a ULR→ULA 2001; no Sh UDR freshness → veto
# memory transport → {"devicePhoneNumberVerified":true}
```

Opt-in assurance detail (an extension beyond CAMARA, not the default):

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: a2' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false,"reqId":"…","decision":"FALLBACK",
#     "assurance":{"score":70,"level":"FALLBACK","threshold":80,"riskClass":"TRANSFER",
#       "factors":{"ipBindingFresh":{"value":1.0,…},"reachable":{"value":1.0,…},
#         "notSimSwapped":{"value":0.0,…},"locationPlausible":{"value":1.0,…}}},
#     "fallbackReason":"SIM_SWAP_SUSPECT"}   — the CDR records the full flow
# memory transport → decision APPROVE, score 100 — threshold per risk class
```

### 3.3 Discovery `/device-phone-number`

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: a3' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
# → {"devicePhoneNumber":"+251911111111"}
```

---

## 4. Scenario B — Fail-closed (S6a + Gx)

```bash
# Detach the subscriber → ULA 5421 → false
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
# verify again as in 3.2 → {"devicePhoneNumberVerified":false}

# Bar the subscriber → ULA Subscriber-Status=ODB → false
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
# verify again → false

# IP with no Gx binding → CCA 5030 → false (NO_BINDING)
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: b1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.99.99.99' -H 'X-Sas-Src-Port: 55555' \
  -d '{"phoneNumber":"+251911111111"}'
# → {"devicePhoneNumberVerified":false}

# Reset to defaults
curl -s -X POST http://127.0.0.1:8086/api/reset
```

## 5. Scenario C — TS.43 entitlement over Wi-Fi (issued by sas-ecs, redeemed by sas-camara)

A track that **needs no cellular bearer**: the device authenticates EAP-AKA with the
entitlement server, receives an operator token, and the backend redeems it at sas-camara.
**Lab**: sas-camara's RA `swxverifier` opens MAR/SAR to SWx :3869 standing in for the
operator leg. **Production**: identity comes from the operator token (EAP-AKA already ran
at the entitlement server), freshness via **Sh UDR/SNR**, no SWx is opened.

> Re-verified 2026-10-01 on the two binaries with SWx **memory** (5.1–5.4 below). SWx
> **corsac** against :3869 has **not been re-run** since the split (the new token carries no
> IMSI). Real EAP-AKA over MAP (SAI + SendIMSI) on sas-ecs: `docs/test/testflow.en.md` §0f.

### 5.1 sas-ecs issues an operator token (stands in for real EAP-AKA; called directly in the lab)

```bash
TOKEN=$(curl -s -X POST http://localhost:8095/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
awk -F. '{print NF}' <<<"$TOKEN"
# → 5  (JWE: an ES256 JWS nested in ECDH-ES/A256GCM, encrypted to sas-camara's enc key)
```

> In reality `/entitlement/issue` is called by the **3GPP AAA** after a successful EAP-AKA
> (or by sas-ecs's own `/ts43`); the lab calls it directly. The token carries the **MSISDN**
> (encrypted) but **no IMSI**: sas-camara neither needs nor ever learns the IMSI. That is why
> sas-camara's lab SWx verifier runs keyed by MSISDN.

### 5.2 `/verify` with the operator token (AccessTech = WIFI)

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: c1' -d '{}'
# → {"devicePhoneNumberVerified":true}
#   sas-camara: decrypts the JWE, fetches sas-ecs's signing keys from /jwks.json (cached 300 s),
#   verifies ES256/aud/exp/typ, records the jti in the sas_operator_token_jti ledger
```

`X-Sas-Operator-Token` header variant (only with `sas.operator-token.header-enabled=true`;
always off in prod — PRO-32):

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "X-Sas-Operator-Token: $TOKEN2" \
  -H 'x-correlator: c2' -d '{}'
# → {"devicePhoneNumberVerified":true}   (TOKEN2 = a fresh token from 5.1)
```

### 5.3 Single-use token (anti-replay)

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: c3' -d '{}'
# → 401 {"code":"UNAUTHENTICATED","message":"operator token is invalid, expired or already used"}
```

### 5.4 Identity reconciliation — `/entitlement/exchange` (legacy, lab only)

```bash
curl -s -X POST http://localhost:8095/entitlement/exchange \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\"}"
# lab → 200 {"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA","valid":true}
#        second call → 401 INVALID_TOKEN
```

> ⚠️ The result above shows **why prod turns this endpoint off**: a local redeem on sas-ecs is
> a **second** place that consumes tokens, independent of sas-camara's ledger, so the token
> already used in 5.3 still redeems here. Prod sets
> `sas.entitlement.legacy-exchange-enabled=false` (preflight PRO-22) → `404`; sas-camara is
> the only redemption point.

### 5.5 NV Authorization Code + PKCE (the standard CAMARA NV flow)

The device calls sas-camara's `/authorize` over the cellular bearer → resolver `IP:port:ts →
MSISDN` → one-time `code` → the bank exchanges `code` + `code_verifier` for an ES256 access
token (`amr=["mno"]`) + a pairwise ID token. Commands + expectations:
`docs/test/testflow.en.md` §0a A8–A9.

## 6. Scenario D (optional) — CIBA money loop

sas-camara is the operator Auth Server: it issues user-bound tokens (the phone number is
bound into the token) and the bank calls `/verify` to compare. Run with validation on:

```bash
java -Dsas.security.token-validation-enabled=true \
     -Dsas.security.hmac-secret=k1 -Dsas.oauth.secret=k1 \
     -jar sas-host/target/quarkus-app/quarkus-run.jar &
```

```bash
# 1) The bank asks for an auth_req_id (cellular anchor: resolver maps IP:port -> MSISDN)
AUTH=$(curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['auth_req_id'])")

# 2) Exchange for a token (CIBA grant; one auth_req_id = ONE token) — ES256 access token typ=at+jwt
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

# 3) /verify compares the claimed number vs the number BOUND in the token
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H "Authorization: Bearer $AT" -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'   # match → true + assurance
#    phoneNumber different from the bound number → false ; token reused → 401 single-use
```

Wi-Fi track for CIBA: replace `X-Sas-Src-Ip/Port` with `login_hint=operatortoken:<tk>` using
a token issued by sas-ecs (verified 2026-10-01: `testflow.en.md` §0a A7).

## 7. Observing signalling

```bash
curl -s http://127.0.0.1:8086/api/messages    # S6a  (ULR/ULA; freshness = Sh UDR)
curl -s http://127.0.0.1:18086/api/messages   # SWx  (MAR/SAR) — lab, operator AAA↔HSS leg
curl -s http://127.0.0.1:28086/api/messages   # Gx   (CCR)
curl -s http://127.0.0.1:28086/api/binding    # Gx binding registry
```

Or open `http://127.0.0.1:8086/` in a browser — a table refreshing every 2 s: time, command,
session-id, result-code, key AVPs (`user=… rat=EUTRAN`, `vectors=N`…).

Rootless pcap: `scripts/split-smoke.sh` (HTTP between the two binaries) and
`scripts/ecs-map-smoke.sh` (sas-ecs SCTP/M3UA/TCAP/MAP) — how to read them: `testflow.en.md` §0a.1, §0f.3.

---

## 8. Expectation matrix

| Scenario | Signalling | Result |
|----------|-----------|--------|
| A — LTE happy (resolver=sd, s6a=corsac) | Gx CCA 2001 + ULA 2001, no Sh UDR | `false` + `SIM_SWAP_SUSPECT` (correct fail-closed) |
| A — LTE happy (memory transport) | pilot resolver/verifier | `true` |
| A — discovery | Gx CCA 2001 + ULA 2001 | `{"devicePhoneNumber":"+2519…"}` |
| A — tuple `accessTech: WIFI` | — (blocked before Diameter) | `400 ACCESS_TECH_NOT_CELLULAR` |
| B — Detached | ULA **5421** | `false` |
| B — Barred | ULA 2001 + Subscriber-Status ODB | `false` |
| B — Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| C — valid operator token (issued by sas-ecs) | lab SWx: MAA items≥1 + SAA 2001 | `true` |
| C — `X-Sas-Operator-Token` header (`header-enabled` on) | as above | `true` |
| C — token replay | — (blocked before Diameter, ledger) | `401` |
| C — `/entitlement/exchange` after redemption at CAMARA | — | lab `200 valid:true` (why prod turns it off); prod `404` |
| C — NV Authorization Code + PKCE | resolver BOUND | ES256 access token + pairwise ID token → `/verify` `true` |
| A/B — wrong/missing amr | — | `403` |
| A — body missing `phoneNumber`/`hashedPhoneNumber` | — | `400 INVALID_ARGUMENT` |
| A — assurance not opted in | — | plain boolean response (CAMARA-pure) |

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `UnsupportedClassVersionError` when running a jar | default `java` is zulu-8 (shim) | use the full zulu-25 path |
| A binary does not boot, silent after `MicroSleeContainer started`, log full of `Failed to load config value…` | built without `-Dquarkus.profile=lab` | rebuild with `-Dquarkus.profile=lab` (§1) |
| `Peer is up` very slow (up to ~3 min), sim log `Received connect request from non provisioned …` | multi-homed host: SCTP tries a LAN/IPv6 source, the sim only accepts loopback | wait for a retry to pick 127.0.0.1 |
| Verify always `false`, HSS receives nothing | SCTP not yet "Peer is up" | wait longer; check all 3 associations (S6a/SWx/Gx) |
| Verify `false` + `SIM_SWAP_SUSPECT` although CCA/ULA are 2001 | `s6a=corsac` fails closed on SIM swap (no Sh UDR in the lab) | by design; use the memory transport for `true` |
| Discovery `403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` | binding resolves to an MSISDN the lab verifier does not know | `POST http://127.0.0.1:28086/api/reset` to re-seed |
| SWx timeout although the HSS sees MAR/MAA | 2 links with the same origin-host on 1 port | move SWx to its own port (`swx.peer-port=3869`) |
| Gx resolver `false` although a binding exists | binding seeded on the wrong instance | seed the binding on instance **:3870** (`POST :28086/api/binding`) |
| `/entitlement/issue` → connection refused on :8085 | entitlement moved to **sas-ecs :8095** | start sas-ecs (§2) and call :8095 |
| Operator token → `401` at sas-camara | issuer missing from `sas.operator-token.issuers-json`, sas-ecs JWKS unreachable, token expired/used | check the log `[SAS] operator token rejected: <reason>` |
| `X-Sas-Operator-Token` header ignored | `sas.operator-token.header-enabled=false` (default; always off in prod) | use `Authorization: Bearer operatortoken:<tk>` |
| `/entitlement/exchange` → `404` | prod disables the local redeem on sas-ecs | redeem the token at sas-camara |

---

## 10. Related automated tests

```bash
/usr/bin/mvn -B clean test -Dquarkus.profile=lab   # 649 tests / 7 modules (JUnit 5)
/usr/bin/mvn -B -o -pl sas-api -am test -Dtest='VerifyResourceTest,OperatorTokenAnchorTest,AuthorizationCodeFlowTest' \
    -Dsurefire.failIfNoSpecifiedTests=false        # operator-token + NV auth-code branches
python3 harness/run_hardness.py          # 37/37 gates H1–H27 (H22 bearer parity, H24 SLEE boundary, H25 binary split)
python3 harness/preflight_prod.py --service all    # prod-profile verdict for THIS env (camara + ecs)
scripts/split-smoke.sh                   # two binaries end-to-end + pcap
scripts/ecs-map-smoke.sh                 # sas-ecs ↔ HLR over MAP + fail-closed matrix + pcap
```
