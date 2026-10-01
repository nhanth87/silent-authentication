# Silent Auth SAS — E2E Test Flow (English)

> 🇻🇳 Vietnamese version: [`testflow.vi.md`](testflow.vi.md) — same section numbers, same commands.

Date: 2026-08-23 · Updated: **2026-10-01** — the SAS is now **two binaries**:
**sas-camara** (`sas-host`, :8085 — CAMARA NV / SIM Swap / OTP-SMS + OAuth AS) and
**sas-ecs** (`sas-ecs-host`, :8095 — TS.43 entitlement server, EAP-AKA over MAP/Diameter).
New: **§0a** (two-binary quick start + NV Authorization Code + operator token), **§0f**
(entitlement ↔ core over MAP on sas-ecs, with pcap). Earlier updates: `/camara` aliases,
strict bodies, `x-correlator`; GSMA mock ↔ local §0d; OAuth ICM Phase 3 §0e; dist demo §0b;
3 test-app instances incl. Gx; SIM-swap fail-closed on the corsac S6a leg; durable CDR for
SimSwap + OTP.

Architecture: [`../design/sas-two-binary-architecture.md`](../design/sas-two-binary-architecture.md).

> **CAMARA alignment:** primary endpoints are under `/number-verification/v2` and the
> `/camara` alias root (`POST /camara/number-verification/v2/verify`,
> `GET /camara/number-verification/v2/device-phone-number`). Legacy `/verify` and
> `/retrieve-phone-number` still work as deprecated lab aliases. Unknown CAMARA request
> properties are rejected as `400 INVALID_ARGUMENT`; a present `x-correlator` must match
> `^[a-zA-Z0-9-_:;./<>{}]{0,256}$` and is echoed. Assurance detail (score/factors) is OPT-IN
> via header `X-Sas-Assurance-Detail: true`. Spec snapshot + gap analysis: `docs/research/camara/`.

> **Two different tracks (do not mix them up):** **S6a + Gx** is the *cellular* track
> (4G/5G): sas-camara runs ULR/ULA against the HSS, the Gx resolver looks up the binding;
> SIM-swap freshness = read-only **Sh UDR/SNR**. **TS.43 Wi-Fi** is the *entitlement*
> track: sas-ecs runs EAP-AKA, fetches vectors with **MAP SAI** (or **SWx MAR**), confirms
> the number with **MAP SendIMSI**, and issues an **operator token** (JWE) that sas-camara
> accepts exactly once. The SWx :3869 instance in §2 only stands in for the operator
> AAA↔HSS leg of sas-camara's lab verifier.

```
bank / curl / GSMA client        sas-camara :8085                 operator simulators
     │  OIDC /authorize /token       │  Resolver → Verifier → Policy   ├─ S6a :3868  (ULR/ULA)
     │  CAMARA NV / SIM Swap / OTP   │──── Diameter (SCTP) ───────────►├─ SWx :3869  (MAR/SAR, lab)
     ├──────────────────────────────►│                                 └─ Gx  :3870  (CCR-I binding)
     │                               │  ▲ GET /jwks.json (issuer keys)
UE (lab card) ── /ts43 ─► sas-ecs :8095 ─┘
                            │──── MAP over M3UA/SCTP (own association :2907) ──► HLR sim :2906 (ctrl :8087)
                            │     sendAuthenticationInfo (SSN 6) · sendIMSI (SSN 3)
                            └──► operator token (ES256 JWS in ECDH-ES/A256GCM JWE) ──► app ──► bank ──► sas-camara
```

## 00. Layered architecture (M3+) — start here (verified 2026-10-01 on packaged dists)

Since migration step M3 the **CAMARA gateway (sas-camara) signals no core network**. Every
subscriber fact comes from the entitlement server (sas-ecs) over the **subscriber exposure
API** (`/sas-subscriber-exposure/v1`, contract
[`../design/openapi/sas-subscriber-exposure-v1.yaml`](../design/openapi/sas-subscriber-exposure-v1.yaml)).
Consequences for testing:

- All transport switches (`sas.transport.*`, `SAS_TRANSPORT_*`, jSS7/Diameter peers,
  resolver) are set on **sas-ecs**. On sas-camara they no longer exist (preflight PRO-17
  refuses them in prod).
- Operator tokens are encrypted to and redeemed by sas-ecs (`/operator-token/redeem`). The
  gateway holds no key or ledger. `/jwks.json` on sas-ecs is gone (404).
- `/session-tuple` lives on sas-ecs.
- Wi-Fi `/verify` needs a redeemed operator token; otherwise `FALLBACK WIFI_NOT_READY`.
- On live transports there is no SIM-change source yet. SIM Swap answers 404, and NV
  ends in `SIM_SWAP_SUSPECT` (fail-closed). With lab evidence on sas-ecs the same flows approve.

Sections §0a–§9 below predate M3 where they start sas-camara with `-Dsas.transport.*`;
those steps are marked **pre-M3**.

### 00.1 Build and package

```bash
/usr/bin/mvn -B -o clean package -Dquarkus.profile=lab           # 9 modules, 791 tests
(cd sas-jss7-testapp && /usr/bin/mvn -o package -DskipTests)      # HLR simulator
(cd sas-diameter-testapp && /usr/bin/mvn -o package)              # HSS / AAA / PCRF simulator
./scripts/package-dist.sh all                                     # dist/ (camara) + dist-ecs/ (ecs)
scripts/check-dependency-boundary.sh dist dist-ecs                # both lines must be OK
```

Re-packaging over an existing dist **keeps** its `configs/*` (operator-owned) and writes the
new defaults next to them as `*.new`. A dist configured before 2026-10-01 still carries the
pre-M3 gateway config (`ss7-sas.json`, operator-token issuers). For a lab dist, start from
the current defaults:

```bash
for d in dist dist-ecs; do mkdir -p $d/configs/backup && mv $d/configs/*.properties* $d/configs/*.json* $d/configs/backup/ 2>/dev/null; done
./scripts/package-dist.sh all                                     # installs fresh configs
```

### 00.2 Start four processes (four terminals)

```bash
# T1 — HLR simulator: MAP over SCTP :2906 (peer sas-ecs :2907), control :8087
java --add-modules jdk.sctp -jar sas-jss7-testapp/target/sas-jss7-testapp.jar \
     --listen-port 2906 --peer-port 2907 --http-port 8087

# T2 — HSS simulator: Diameter S6a over SCTP :3868, control :8086
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar

# T3 — sas-ecs (:8095) on the LIVE core: MAP SAI/SendIMSI/PSI + S6a peer
(cd dist-ecs && SAS_TRANSPORT_AUTHVECTOR=map SAS_TRANSPORT_MAP=jss7 SAS_TRANSPORT_S6A=corsac \
   SAS_BINDING_SOURCE_ORDER=map-smi SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json ./run.sh)

# T4 — sas-camara (:8085), OAuth validation on, one lab client
CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","sim-swap:check"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'
(cd dist && SAS_JAVA_OPTS="-Dsas.security.token-validation-enabled=true -Dsas.security.accept-hs256=false -Dsas.oauth.clients-json=$CLIENTS" ./run.sh)
```

Environment variables and `-D` override the dist's `configs/application.properties`
(`config_ordinal=250`). Before 2026-10-01 they were silently ignored by `run.sh`.

### 00.3 Steps — exposure API directly on sas-ecs

```bash
E=http://127.0.0.1:8095/sas-subscriber-exposure/v1
H=(-H 'Content-Type: application/json' -H "X-Request-Id: req-$(date +%s%N)" -H 'X-Sas-Budget-Ms: 2500')

# S1 — UE ID: lab tuple 10.20.30.40:55555 → GPSI
curl -s -X POST $E/ue-id/retrieve "${H[@]}" \
  -d '{"afId":"sas-camara","ueIpAddr":{"ipv4Addr":"10.20.30.40"},"portNumber":55555,"observedAt":"2026-10-01T06:00:00Z"}'
# → 200 {"gpsi":"msisdn-251911111111","bindingObservedAt":"…","evidenceSource":"LAB_RESOLVER"}

# S2 — monitoring over the live MAP core (SendIMSI, then PSI)
curl -s -X POST $E/monitoring-event/sas-camara/subscriptions "${H[@]}" \
  -d '{"msisdn":"251911111111","monitoringType":"UE_REACHABILITY","addnMonTypes":["LOCATION_REPORTING"],"maximumNumberOfReports":1,"immediateRep":true}'
# → 200 … "ueReachable":true … "plmnId":{"mcc":"636","mnc":"01"},"locationInfo":{"ageOfLocationInfo":1} … "evidenceSource":"MAP_PSI"
#   unknown number 251900000000 → 404 SUBSCRIBER_NOT_FOUND

# S3 — SIM change on live transports
curl -s -X POST $E/sim-change/retrieve "${H[@]}" -d '{"afId":"sas-camara","msisdn":"251911111111"}'
# → 404 {"cause":"NO_EVIDENCE",…}   (fail-closed: never "not swapped")

# Contract refusals: unknown field → 400 INVALID_ARGUMENT; "msisdn":"+2519…" → 400;
# X-Sas-Budget-Ms: 50 on monitoring → 504 BUDGET_EXHAUSTED (nothing signalled)
```

### 00.4 Steps — CAMARA flows through the gateway

```bash
ECS=http://127.0.0.1:8095; CAM=http://127.0.0.1:8085

# S4 — operator token: real EAP-AKA over MAP SAI + SendIMSI, lab SIM card
CJ=$(curl -s "$ECS/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")
R=$(java -cp sas-entitlement/target/classes et.restlink.sas.entitlement.lab.LabSimCardMain \
      655010000000001 "$(jq -r .challenge <<<"$CJ")")
TOK=$(curl -s -X POST $ECS/ts43/respond -H 'Content-Type: application/json' \
  -d "{\"reqId\":\"$(jq -r .reqId <<<"$CJ")\",\"imsi\":\"655010000000001\",\"msisdn\":\"+251911111111\",\"response\":\"$R\"}" | jq -r .token)
awk -F. '{print NF}' <<<"$TOK"                  # → 5 (JWE)

# S5 — CIBA with login_hint=operatortoken: the gateway forwards it to /operator-token/redeem
AUTH=$(curl -s -X POST $CAM/bc-authorize --data-urlencode "login_hint=operatortoken:$TOK" \
  --data-urlencode "scope=dpv:FraudPreventionAndDetection sim-swap:check" \
  --data-urlencode client_id=bank-lab | jq -r .auth_req_id)
AT=$(curl -s -X POST $CAM/token --data-urlencode grant_type=urn:openid:params:grant-type:ciba \
  --data-urlencode "auth_req_id=$AUTH" --data-urlencode client_id=bank-lab | jq -r .access_token)
# replay the same token at /bc-authorize → 403 (spent in the ECS ledger)

# S6 — SIM Swap check with the CIBA token
curl -s -X POST $CAM/camara/sim-swap/v2/check -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -H "x-correlator: $(cat /proc/sys/kernel/random/uuid)" -d '{"maxAge":240}'
# live core → 404 (no SIM-change evidence)        lab evidence (00.5) → 200 {"swapped":false}

# S7 — NV Authorization Code + PKCE; network authentication = UE ID retrieval at the ECS
VERIFIER=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n')
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' -G $CAM/authorize \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  --data-urlencode response_type=code --data-urlencode client_id=bank-lab \
  --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "scope=openid dpv:FraudPreventionAndDetection number-verification:verify" \
  --data-urlencode state=s --data-urlencode nonce=n \
  --data-urlencode "code_challenge=$CHALLENGE" --data-urlencode code_challenge_method=S256)
CODE=$(sed -E 's/.*[?&]code=([^&]+).*/\1/' <<<"$LOC")
NVAT=$(curl -s -X POST $CAM/token --data-urlencode grant_type=authorization_code \
  --data-urlencode "code=$CODE" --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode client_id=bank-lab | jq -r .access_token)
curl -s -X POST $CAM/camara/number-verification/v2/verify -H "Authorization: Bearer $NVAT" \
  -H 'Content-Type: application/json' -H "x-correlator: $(cat /proc/sys/kernel/random/uuid)" \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' -d '{"phoneNumber":"+251911111111"}'
# live core → {"devicePhoneNumberVerified":false}  (gateway log dist/logs: SIM_SWAP_SUSPECT)
# lab evidence (00.5) → {"devicePhoneNumberVerified":true}; the answer never names an evidence source

# S8 — runtime boundary: the gateway owns no SCTP socket, sas-ecs does
scripts/check-no-sctp.sh "$(pgrep -f '^[^ ]*java .*/dist/quarkus-run\.jar')" sas-camara       # OK … owns no SCTP endpoint
scripts/check-no-sctp.sh "$(pgrep -f '^[^ ]*java .*/dist-ecs/quarkus-run\.jar')" sas-ecs      # FAIL … owns SCTP socket inode(s) — expected
```

### 00.5 Lab-evidence variant, and the prod profile on your own machine

The subscriber exposure API is **on in the lab profile** (both dists above run lab). The
**prod profile keeps it off** on purpose: the exposure hop has no client authentication
until migration step M4, so anyone reaching :8095 could resolve IP→MSISDN or spend tokens
(preflight PRO-35). To try the prod profile locally, switch it on for that run only —
never in the file:

```bash
(cd dist-ecs && QUARKUS_PROFILE=prod SAS_EXPOSURE_ENABLED=true ECS_…=… ./run.sh)   # plus the ECS_* env of application-prod.properties
```


Restart T3 without `SAS_TRANSPORT_MAP=jss7` (MAP monitoring then answers from the lab HLR
backend, SIM change from its seeded binding age, `evidenceSource` `LAB_HLR`). EAP-AKA still
runs over the live HLR simulator. S6 then answers `200 {"swapped":false}` and S7 `true`.

### 00.6 Automated runs

| Script | What it proves | Verified result |
|---|---|---|
| `scripts/layered-e2e.sh dist dist-ecs` | 00.2–00.5 end to end on the packaged dists, live and lab phases, pcap | all OK, rc=0 |
| `scripts/split-smoke.sh` | both binaries from `target/`: CAMARA flows, ECS JWKS gone, no SCTP from the gateway | all OK |
| `scripts/exposure-smoke.sh` | the four exposure operations vs the HLR simulator, contract refusals, no IMSI in answers | 14/14 |
| `scripts/ecs-map-smoke.sh` | TS.43 over MAP + fail-closed matrix; sas-ecs owns the MAP association, gateway none | demo OK + 8/8 |

### 00.7 Expectations at a glance

| Flow | Live core (00.2) | Lab evidence (00.5) |
|---|---|---|
| UE ID 10.20.30.40:55555 | 200 GPSI | 200 GPSI |
| Monitoring 251911111111 | 200 reachable, PLMN 636-01, `MAP_PSI` | 200, `LAB_HLR` |
| SIM change | 404 `NO_EVIDENCE` | 200, 10 days ago |
| CIBA with operator token | auth_req_id; replay 403 | same |
| SIM Swap check | 404 | 200 `swapped:false` |
| NV Auth Code → /verify | `false` (`SIM_SWAP_SUSPECT`) | `true` |
| Wi-Fi /verify without token | `FALLBACK WIFI_NOT_READY` | same |
| Gateway SCTP sockets | none | none |

## 0. Environment

- JDK zulu-25: `$HOME/.local/share/mise/installs/java/zulu-25`
- Always build with `/usr/bin/mvn` (the `mvn` on PATH is a mise shim bound to zulu-8 — do NOT use it directly)
- Set once, then `java`/`javac` are zulu-25:
  ```bash
  export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"
  export PATH="$JAVA_HOME/bin:$PATH"
  /usr/bin/mvn -version   # must print "Java version: 25.0.3"
  ```
- Never run **two `mvn` at once** (a `~/.m2` race produces fake compile errors) — build **sequentially**.
- `tcpdump`, `tshark` and `unshare` are enough for the pcap scripts (§0a, §0f); no root needed
  (they run inside a private user + network namespace).

## 0a. Two binaries — quick start (verified 2026-10-01)

> **Pre-M3 (two-binary by track).** The gateway-side operator-token config (`sas.operator-token.issuers-json`, decryption key, ledger) shown here no longer exists — tokens are redeemed at sas-ecs (§00). The flows still pass with today's lab defaults: `scripts/split-smoke.sh`.

Runs without the Diameter simulators: both binaries use memory transports in the lab
profile. Covers the operator token sas-ecs → sas-camara (Wi-Fi `/verify`, CIBA + SIM
Swap, replay), the **Authorization Code + PKCE flow for NV** (network authentication),
the ID token, and surface isolation between the two binaries.

**A0 — build + package both dists:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
./scripts/package-dist.sh all          # → dist/ (sas-camara) + dist-ecs/ (sas-ecs)
```
→ expect: `Dist ready [camara]: …/dist` and `Dist ready [ecs]: …/dist-ecs`.

> ⚠️ **Upgrading an existing `dist/`:** `package-dist.sh` **never overwrites** existing
> `dist/configs/*` (operator configuration) — the new defaults land next to them as `.new`.
> Keys missing from the old file fall back to the defaults packaged in the jar, but keys
> present in the old file still win (e.g. `sas.transport.resolver=sd` from the §0b
> Diameter demo ⇒ `/authorize` answers `NO_BINDING` when no Gx is running). Run §0a on a
> clean config: `diff dist/configs/application.properties{,.new}` and merge, or package
> elsewhere: `SAS_SKIP_BUILD=1 SAS_DIST_DIR=/path/dist-clean ./scripts/package-dist.sh camara`.

**A1 — start sas-ecs (:8095) and sas-camara (:8085) with one lab OAuth client:**

```bash
CLIENTS='{"clients":[{"client_id":"bank-lab","token_endpoint_auth_method":"none","grant_types":["authorization_code","urn:openid:params:grant-type:ciba"],"redirect_uris":["https://bank.example/cb"],"scopes":["number-verification:verify","number-verification:device-phone-number:read","sim-swap:check","sim-swap:retrieve-date"],"purposes":["dpv:FraudPreventionAndDetection"]}]}'
(cd dist-ecs && exec ./run.sh > logs/lab.log 2>&1) &
(cd dist && SAS_JAVA_OPTS="-Dsas.oauth.clients-json=$CLIENTS" exec ./run.sh > logs/lab.log 2>&1) &
for p in 8095 8085; do for i in $(seq 1 60); do curl -fs -o /dev/null http://127.0.0.1:$p/jwks.json && break; sleep 1; done; done
```
→ `SAS_JAVA_OPTS=-D…` works here because `sas.oauth.clients-json` is **not** in
`configs/application.properties`; for keys that are in the file, the file wins (§8).

**A2 — sas-ecs JWKS (operator-token signing key):**

```bash
curl -s http://127.0.0.1:8095/jwks.json | jq -c '.keys[]|{kid,use,alg,has_d:has("d")}'
```
→ expect: one key, `use:"sig"`, `alg:"ES256"`, `has_d:false`.

**A3 — sas-ecs issues an operator token** (lab: `/entitlement/issue` is open and stands in
for an EAP-AKA completed at the AAA):

```bash
TOK=$(curl -s -X POST http://127.0.0.1:8095/entitlement/issue -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' | jq -r .token)
awk -F. '{print NF}' <<<"$TOK"
```
→ expect: `5` — the token is a **JWE** (ECDH-ES/A256GCM) around an ES256 JWS; the number is
not readable while the token crosses the handset and the app.

**A4 — sas-camara JWKS:**

```bash
curl -s http://127.0.0.1:8085/jwks.json | jq -c '.keys[]|{kid,use,alg,has_d:has("d")}'
```
→ expect: a `use:"sig"` key (ES256, signs access/ID tokens) **and** a `use:"enc"` key
(ECDH-ES — what the ECS encrypts operator tokens to); none carries `d`.

**A5 — Wi-Fi `/verify` with the operator token:**

```bash
curl -s -X POST http://127.0.0.1:8085/camara/number-verification/v2/verify \
  -H "Authorization: Bearer operatortoken:$TOK" -H 'X-Sas-Access-Tech: WIFI' \
  -H 'Content-Type: application/json' -H 'x-correlator: demo-wifi-1' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"devicePhoneNumberVerified":true}`. sas-camara decrypts the JWE, fetches the
signing key from `http://127.0.0.1:8095/jwks.json` (cached 300 s), checks
ES256/aud/exp/typ and records the `jti` in the `sas_operator_token_jti` ledger.

**A6 — replay the same token:** re-run A5 → expect `401`
`{"code":"UNAUTHENTICATED","message":"operator token is invalid, expired or already used"}`.

**A7 — CIBA with an operator token → ES256 access token → SIM Swap:**

```bash
TOK2=$(curl -s -X POST http://127.0.0.1:8095/entitlement/issue -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' | jq -r .token)
BC=$(curl -s -X POST http://127.0.0.1:8085/bc-authorize --data-urlencode "login_hint=operatortoken:$TOK2" \
  --data-urlencode 'scope=dpv:FraudPreventionAndDetection sim-swap:check' --data-urlencode client_id=bank-lab)
AT=$(curl -s -X POST http://127.0.0.1:8085/token --data-urlencode grant_type=urn:openid:params:grant-type:ciba \
  --data-urlencode auth_req_id=$(jq -r .auth_req_id <<<"$BC") --data-urlencode client_id=bank-lab | jq -r .access_token)
cut -d. -f1 <<<"$AT" | base64 -d 2>/dev/null; echo
curl -s -X POST http://127.0.0.1:8085/camara/sim-swap/v2/check -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -H 'x-correlator: demo-ss-1' -d '{"phoneNumber":"+251911111111","maxAge":240}'
```
→ expect: header `{"kid":"…","typ":"at+jwt","alg":"ES256"}` and `{"swapped":false}`.
Sending `/bc-authorize` again with the same `TOK2` → `403 access_denied` (token spent).

**A8 — NV Authorization Code + PKCE (network authentication):**

```bash
V=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk     # code_verifier (RFC 7636 example)
C=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM     # = BASE64URL(SHA256(V))
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' -G http://127.0.0.1:8085/authorize \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  --data-urlencode response_type=code --data-urlencode client_id=bank-lab \
  --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode 'scope=openid dpv:FraudPreventionAndDetection number-verification:verify' \
  --data-urlencode state=s1 --data-urlencode nonce=n1 \
  --data-urlencode code_challenge=$C --data-urlencode code_challenge_method=S256)
echo "$LOC"
CODE=$(sed -E 's/.*[?&]code=([^&]+).*/\1/' <<<"$LOC")
TR=$(curl -s -X POST http://127.0.0.1:8085/token --data-urlencode grant_type=authorization_code \
  --data-urlencode code=$CODE --data-urlencode redirect_uri=https://bank.example/cb \
  --data-urlencode code_verifier=$V --data-urlencode client_id=bank-lab)
jq -c 'keys' <<<"$TR"
NV=$(jq -r .access_token <<<"$TR")
curl -s -X POST http://127.0.0.1:8085/camara/number-verification/v2/verify \
  -H "Authorization: Bearer $NV" -H 'Content-Type: application/json' -H 'x-correlator: demo-nv-1' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `https://bank.example/cb?code=…&state=s1`;
`["access_token","expires_in","id_token","scope","token_type"]`;
`{"devicePhoneNumberVerified":true}`. Access-token payload: `iss=http://127.0.0.1:8085`,
`aud=http://127.0.0.1:8085/camara`, `amr=["mno"]`, `exp-iat=300`. ID token: `aud=bank-lab`,
`nonce=n1`, a **pairwise** `sub` (never the phone number).

> `X-Sas-Src-Ip/Port` are trusted only because the lab sets
> `sas.oauth.authorize.trust-src-header=true`. In prod the SAS uses the **connection**
> source unless an edge proxy overwrites the headers. No port (`X-Sas-Src-Port`) ⇒
> `access_denied … NO_BINDING` — the CGNAT IP+port+ts rule.

**A9 — fail-closed branches of `/authorize` + `/token`:**

| Command | Expect |
|---|---|
| `/token` again with the same `CODE` | `400 invalid_grant` "code is invalid, expired or already used" |
| `/authorize` without `code_challenge` | `302 …?error=invalid_request&error_description=PKCE+with+code_challenge_method%3DS256+is+required&state=…` |
| `/authorize` with `redirect_uri=https://evil.example/cb` | `400` JSON `redirect_uri is not registered for this client` — **no** redirect |
| scope `number-verification:device-phone-number:read` → token → `GET /camara/number-verification/v2/device-phone-number` | `200 {"devicePhoneNumber":"+251911111111"}` |

**A10 — surface isolation between the two binaries:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8085/entitlement/status      # 404
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8085/ts43/challenge?imsi=1' # 404
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8095/token            # 404
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8095/.well-known/openid-configuration  # 404
curl -s http://127.0.0.1:8085/.well-known/openid-configuration | jq -c '{authorization_endpoint,code_challenge_methods_supported,id_token_signing_alg_values_supported}'
```
→ expect: four `404`s, then `{"authorization_endpoint":"http://127.0.0.1:8085/authorize","code_challenge_methods_supported":["S256"],"id_token_signing_alg_values_supported":["ES256"]}`.

**A11 — cleanup:**

```bash
pkill -f "$PWD/dist/quarkus-run.jar"; pkill -f "$PWD/dist-ecs/quarkus-run.jar"
sleep 2; pgrep -fa quarkus-run.jar || echo "all SAS JVMs stopped"
```

### 0a.1 Automated + pcap

```bash
mvn -o -q package -DskipTests -Dquarkus.package.jar.type=fast-jar -Dquarkus.profile=lab
scripts/split-smoke.sh      # A2–A10 automated, no root; pcap: target/split-smoke/split-smoke.pcap
```

Read the pcap:

```bash
P=target/split-smoke/split-smoke.pcap
tshark -r $P -Y http.request -T fields -e tcp.dstport -e http.request.method -e http.request.uri
tshark -r $P -Y 'http.response' -T fields -e tcp.srcport -e http.response.code
```
→ expect: sas-camara calls `GET /jwks.json` on :8095 **exactly once**, right after the first
`/bc-authorize` (later ones are served from cache); no `101` response.

## 0b. Manual demo runbook — Diameter (S6a/SWx/Gx) + CAMARA (verified 2026-09-03 · TS.43 leg updated for two binaries 2026-10-01)

> **Pre-M3.** This runbook starts sas-camara with `-Dsas.transport.*` (S6a/SWx/Gx); since M3 those transports are configured on **sas-ecs** and reached through the exposure API (§00). The S6a/Gx signalling scenarios have not been re-run on the layered binaries.

One command per step. Pre-built artifacts: `dist/` (sas-camara), `dist-ecs/` (sas-ecs —
only needed for Step 12) + `sas-diameter-testapp/target/sas-diameter-testapp.jar`. `dist/configs/application.properties`
is **already configured** for the Diameter demo (`s6a=swx=corsac`, `resolver=sd` :3870,
`swx.peer-port=3869`, entitlement HMAC secret) plus the two CAMARA add-ons:
SimSwap evidence (in-memory MAP seed) and the OTP SMS lab sender
(`sas.otp.enabled=true`, `sas.otp.sms-delivery=log` — logs the SMS, sends nothing).

> ⚠️ **Config gotcha (verified):** `dist/run.sh` loads `configs/application.properties`
> via `quarkus.config.locations`; for **keys present in that file**, the file outranks
> `-D` system properties. Change transports in the **config file**; `SAS_JAVA_OPTS=-D…`
> only takes effect for keys **absent** from the file (e.g. `sas.oauth.clients-json` in
> §0a). Running `java -jar dist/quarkus-run.jar` directly, `-D` works normally.

**Step 0 — enter the worktree, check JDK 25:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
J=$HOME/.local/share/mise/installs/java/zulu-25/bin/java
$J -version
```
→ expect: `openjdk version "25…"` (zulu).

**Step 1 — S6a simulator (HSS, SCTP :3868, UI :8086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &
```
→ expect: `HSS Diameter listening on 127.0.0.1:3868` + `Control UI listening on http://127.0.0.1:8086/`

**Step 2 — SWx simulator (3GPP AAA↔HSS, :3869, UI :18086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar --diameter-port 3869 --web-port 18086 &
```
→ expect: `listening on 127.0.0.1:3869` + UI `:18086`

**Step 3 — Gx simulator (PCRF binding, :3870, UI :28086):**

```bash
$J -jar sas-diameter-testapp/target/sas-diameter-testapp.jar --diameter-port 3870 --web-port 28086 &
```
→ expect: `listening on 127.0.0.1:3870` + UI `:28086`

**Step 4 — SAS from dist** (config already set to corsac + sd; tee the log for Step 5):

```bash
dist/run.sh 2>&1 | tee /tmp/sas-demo.log &
```
→ expect: RAs wired, `S6a Diameter transport started`,
`PCRF Sd/Gx resolver dialing 127.0.0.1:3870`, `Listening on: http://0.0.0.0:8085`.

**Step 4b — sas-ecs (only needed for Step 12 — the entitlement server is its own binary since 2026-10-01):**

```bash
(cd dist-ecs && ./run.sh > logs/lab.log 2>&1 &)
for i in $(seq 1 60); do curl -fs -o /dev/null http://localhost:8095/jwks.json && break; sleep 1; done
curl -s http://localhost:8095/entitlement/status
```
→ expect: `{"enabled":true,…}`. sas-camara (Step 4) trusts sas-ecs through
`sas.operator-token.issuers-json` (lab: `http://127.0.0.1:8095`).

**Step 5 — wait for all 3 Diameter peers:**

```bash
sleep 15; grep -c 'Peer is up' /tmp/sas-demo.log; grep 'Peer is up' /tmp/sas-demo.log | sed -E 's/.*name=([a-z0-9-]+).*/\1/'
```
→ expect: count `3` and names `s6a-sas`, `swx-sas`, `sd-sas`. If <3: repeat every 15 s —
on a multi-homed host it can take up to ~3 minutes (sims accept loopback sources only;
sim logs show `Received connect request from non provisioned … address` until a retry
picks 127.0.0.1). Do NOT continue until all 3 peers are up.

**Step 6 — ① LTE verify (CAMARA `/verify`):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: d1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"devicePhoneNumberVerified":false}` — **correct fail-closed**: the lab HSS has
no Sh UDR handler, so the SIM-swap dimension is vetoed (`SIM_SWAP_SUSPECT`). Demo story:
CCR→CCA and ULR→ULA both returned 2001, yet SAS refuses because freshness evidence is
missing — that is fail-closed working as designed.
(For a `true` happy path instead: set `sas.transport.s6a=memory` in
`dist/configs/application.properties`, restart Step 4.)

**Step 7 — ①b assurance detail (opt-in):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: d2' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `"decision":"FALLBACK"`, `"score":70`, `"threshold":80`, `"fallbackReason":"SIM_SWAP_SUSPECT"`,
factors: `ipBindingFresh 1.0 · reachable 1.0 · notSimSwapped 0.0 · locationPlausible 1.0`.

**Step 8 — discovery (number bound to the bearer IP):**

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: d3' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
```
→ expect: `{"devicePhoneNumber":"+251911111111"}`

**Step 9 — ② fail-closed: detach the subscriber:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
```
then re-run the Step 6 curl (change to `x-correlator: d4`)
→ expect: `{"devicePhoneNumberVerified":false}` (ULA 5421 — see UI `:8086`).

**Step 10 — ③ fail-closed: barring:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
```
then re-run the Step 6 curl (change to `x-correlator: d5`)
→ expect: `false` (ULA 2001 + Subscriber-Status ODB).

**Step 11 — reset HSS to defaults:**

```bash
curl -s -X POST http://127.0.0.1:8086/api/reset
```
→ expect: `{"reset":true}`

**Step 12 — ④ TS.43 Wi-Fi: sas-ecs issues an operator token:**

```bash
curl -s -X POST http://localhost:8095/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}'
```
→ expect: `{"token":"eyJ…","expiresInSeconds":300}` — a 5-part JWE (the number is encrypted
to sas-camara's `enc` key). Copy into a variable: `TOKEN="<paste token>"`

**Step 13 — ④ verify with the operator token (no cellular needed):**

```bash
curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d6' -d '{}'
```
→ expect: `{"devicePhoneNumberVerified":true}` (SWx MAR→MAA + SAR→SAA on :3869 — UI `:18086`).

> After the split (2026-10-01) this path was re-run with **memory** SWx
> (`{"devicePhoneNumberVerified":true}`, replay `401`). With **corsac** SWx to :3869 it has
> **not been re-run** yet: the new operator token carries no IMSI, so the MAR is keyed by MSISDN.

**Step 14 — ⑤ anti-replay: reuse the token:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: d7' -d '{}'
```
→ expect: `401` (single-use token).

**Step 15 — signalling observation (optional, open a browser):**
`http://127.0.0.1:8086/` (S6a) · `http://127.0.0.1:18086/` (SWx) · `http://127.0.0.1:28086/` (Gx)

**Step 16 — ⑥ CAMARA SimSwap v2.1.0 `/sim-swap/v2/check` (2-legged, on the Step 4 SAS):**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw1' -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"swapped":false}` — the pilot seed's last SIM change is 10 days old,
i.e. outside the default `maxAge=240` h window. Evidence is the same read-only
binding age the Verifier scores as `notSimSwapped` (MAP `lastUpdateLocation` →
Sh UDR → SWx); no Diameter exchange is needed for this call.

**Step 17 — same subscriber, wider window (`maxAge` is hours, spec range 1..2400):**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw2' -d '{"phoneNumber":"+251911111111","maxAge":2400}'
```
→ expect: `{"swapped":true}` (10 days ≤ 100 days).

**Step 18 — `/sim-swap/v2/retrieve-date`:**

```bash
curl -s -X POST http://localhost:8085/sim-swap/v2/retrieve-date \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw3' -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `{"latestSimChange":"<RFC 3339 instant, ~10 days ago>"}` (no
`monitoredPeriod` while the SAS has no configured monitoring window).

**Step 19 — fail-closed + CAMARA error codes (no evidence is never "not swapped"):**

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw4' -d '{"phoneNumber":"+251999999999"}'
```
→ expect: `404` `{"status":404,"code":"IDENTIFIER_NOT_FOUND",…}`

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw5' -d '{}'
```
→ expect: `422` `MISSING_IDENTIFIER` (2-legged call with no `phoneNumber`)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw6' -d '{"phoneNumber":"+251911111111","maxAge":0}'
```
→ expect: `400` `OUT_OF_RANGE` (spec range is 1..2400 — never silently clamped)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'x-correlator: sw7' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `401` `UNAUTHENTICATED` (no `Authorization` header)

**Step 20 — ⑦ CAMARA OneTimePasswordSMS v1.1.1 `/send-code` (the FALLBACK branch):**

```bash
curl -s -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp1' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ expect: `200` with body `{"authenticationId":"<uuid>"}` (a UUID is exactly the
36 chars the spec schema allows). The OTP itself is **never**
in the response — `message` is a template that must contain `{{code}}` (≤160 chars).
The SAS only orchestrates: in production the SMS goes out over the operator's own
SMSC/SGd route (Restlink does not wholesale SMS).

**Step 21 — read the OTP from the lab sender (log-only, nothing is sent):**

```bash
grep 'LAB-SMS' /tmp/sas-demo.log | tail -1
```
→ expect: `WARN LabLogSmsDelivery - [SAS][LAB-SMS — NOT SENT] to=+251****11 chars=28
text="921540 is your Restlink code"` — set `OTP="921540"`, `AID="<authenticationId>"`.
This cleartext OTP in the log is exactly why the prod profile ships the surface
**off** and preflight `PRO-29` refuses a lab sender.

**Step 22 — `/validate-code` wrong then right:**

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp2' -d "{\"authenticationId\":\"$AID\",\"code\":\"000000\"}"
```
→ expect: `400` `ONE_TIME_PASSWORD_SMS.INVALID_OTP`

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp3' -d "{\"authenticationId\":\"$AID\",\"code\":\"$OTP\"}"
```
→ expect: `204` (no body). Re-run it → `404 NOT_FOUND` (one OTP validates once).

**Step 23 — attempt burn: 3 wrong codes kill the attempt:**

```bash
for i in 1 2 3; do curl -s -o /dev/null -w '%{http_code} ' -X POST \
  http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H "x-correlator: burn$i" -d "{\"authenticationId\":\"$AID2\",\"code\":\"000000\"}"; done; echo
```
→ expect: `400 400 400` — the third is `ONE_TIME_PASSWORD_SMS.VERIFICATION_FAILED`,
and the **correct** code afterwards also answers `VERIFICATION_FAILED`.

**Step 24 — OTP fail-closed matrix:**

```bash
# rate limit: a 4th send to the SAME number inside sas.otp.rate-window-seconds
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' -H 'x-correlator: otp4' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ expect: `403` `ONE_TIME_PASSWORD_SMS.MAX_OTP_CODES_EXCEEDED` (another MSISDN is
unaffected; a restart clears the in-memory window)

```bash
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' -H 'x-correlator: otp5' \
  -d '{"phoneNumber":"+251933333333","message":"no code label here"}'
```
→ expect: `400` `INVALID_ARGUMENT` (template must carry `{{code}}`; >160 chars likewise).
No `Authorization` → `401`; unknown `authenticationId` → `404 NOT_FOUND`;
`sas.otp.enabled=false` (or the key missing from `dist/configs/application.properties`)
→ `404 NOT_FOUND` on both endpoints; no delivery route → `500 INTERNAL_ERROR` with
**no** `authenticationId` issued.

**Step 25 — ⑧ CDR/audit for the SimSwap + OTP calls:**

Each ⑥/⑦ request writes one API CDR row, keyed by `x-correlator`, to both the
durable `SAS_CDR` CSV file and the DB-backed admin ledger. With `dist/run.sh`,
the CSV is `dist/logs/sas.cdr`.

```bash
grep -E 'swcdr1|otpcdr1|otpcdr2' dist/logs/sas.cdr
```
→ expect:
```text
…,swcdr1,+251****11,SIMSWAP,COMPLETED,action=check maxAgeHours=240 swapped=false http=200,http,http,lab
…,otpcdr1,+251****11,OTP,COMPLETED,action=send-code messageChars=35 delivery=log result=SENT authenticationId=<uuid> ttlSeconds=300 http=200,http,http,lab
…,otpcdr2,+251****11,OTP,FAILED,action=validate-code authenticationId=<uuid> attempts=1 maxAttempts=3 result=INVALID http=400 code=ONE_TIME_PASSWORD_SMS.INVALID_OTP,http,http,lab
```

The same rows are visible at `http://localhost:8085/admin/cdr` (lab seed
`admin/admin`). The dashboard merges newly queued rows with persisted DB rows, so
history survives a restart.

Privacy invariants:

- MSISDN is masked (`+251****11`) before the CDR port sees it; raw MSISDN/IMSI are absent.
- OTP plaintext and SMS message text are absent; only `messageChars` and
  `authenticationId` are recorded.
- 2xx maps to `COMPLETED`; 4xx/5xx maps to `FAILED`, with `http=` and CAMARA `code=`.
- A blank `x-correlator` is replaced by a generated UUID, but repeated smoke runs
  should use fresh correlators because `sas_cdr_session.correlation_id` is unique.

---

**CIBA money-loop (optional — run AFTER stopping the Step 4 SAS; keep the simulators):**

**Step C1 — stop SAS** (Ctrl-C the Step 4 terminal, or):

```bash
pkill -f quarkus-run.jar
```

**Step C2 — SAS with token validation (run DIRECTLY, not via run.sh):**

```bash
$J -Dsas.security.token-validation-enabled=true -Dsas.security.hmac-secret=k1 \
   -Dsas.oauth.secret=k1 --add-modules jdk.sctp -jar dist/quarkus-run.jar 2>&1 | tee /tmp/sas-ciba.log &
sleep 8; grep 'Listening on' /tmp/sas-ciba.log
```
→ expect: `Listening on: http://0.0.0.0:8085`. Since 2026-10-01 access tokens are **ES256
`at+jwt`** (lab `sas.oauth.signing-alg=ES256`, a random key per run) verified via
`/jwks.json`; `-Dsas.oauth.secret=k1` is no longer needed but harmless.

**Step C3 — bank requests auth_req_id (cellular anchor IP:port):**

```bash
curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify'
```
→ expect: `{"auth_req_id":"…","expires_in":120}` — set `AUTH="<auth_req_id>"`

**Step C4 — exchange for a token (CIBA grant):**

```bash
curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH"
```
→ expect: `{"access_token":"eyJ…","expires_in":300,…}` — set `AT="<access_token>"`

> If Step C3 sends `client_id=...`, Step C4 must send the same `client_id`; the
> `auth_req_id` is bound to that client. With `private_key_jwt`, each endpoint needs a
> separate client assertion because a `jti` is one-time use.

**Step C5 — verify the matching number (token is bound to +251911111111):**

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `true`, `"decision":"APPROVE"`, `"score":100`

**Step C6 — reuse the token (single-use):** re-run the Step C5 command
→ expect: `401`

**Step C7 — SIM Swap on the 3-legged path (identity comes from the token, body empty):**

```bash
AUTH=$(curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=sim-swap:check' | python3 -c 'import sys,json;print(json.load(sys.stdin)["auth_req_id"])')
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'x-correlator: sw8' -d '{}'
```
→ expect: `{"swapped":false}` + `200`. Re-run the last curl → `401` (single-use).
With a bound token, sending `{"phoneNumber":"+251911111111"}` → `422
UNNECESSARY_IDENTIFIER`; a `number-verification:verify` token → `403
PERMISSION_DENIED` (wrong scope family); `scope=sim-swap:retrieve-date` works on
`/sim-swap/v2/retrieve-date` with an empty body.

**Step C8 — OTP SMS on the 3-legged path (one scope covers both operations):**

```bash
AUTH=$(curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=one-time-password-sms:send-validate' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["auth_req_id"])')
AT=$(curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" -H 'x-correlator: otp6' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
```
→ expect: `200` + `authenticationId` when `phoneNumber` **equals** the token binding.
A different number → `403 PERMISSION_DENIED` ("phoneNumber does not match the
access-token binding") and no OTP is even composed; a `sim-swap:check` token →
`403 PERMISSION_DENIED` (wrong scope family); re-using the token → `401` (single-use,
so send-code and validate-code each need their own token).

**Step C9 — cleanup:**

```bash
for p in $(pgrep -x java); do kill $p; done
pgrep -x java || echo "all JVMs stopped"
```

### Expected results at a glance (§0b)

| # | Step | Signalling observed | Expected result |
|---|------|--------------------|-----------------|
| 6 | ① LTE verify (corsac S6a) | CCA 2001 + ULA 2001, no Sh UDR | `false` + `SIM_SWAP_SUSPECT` (correct fail-closed) |
| 7 | ①b assurance detail | same | `FALLBACK`, score 70 < threshold 80 |
| 8 | discovery | CCA 2001 | `{"devicePhoneNumber":"+251911111111"}` |
| 9 | ② subscriber detached | ULA **5421** | `false` |
| 10 | ③ subscriber barred | ULA 2001 + ODB status | `false` |
| 12–13 | ④ operator-token verify (issued by sas-ecs, redeemed by sas-camara) | lab SWx: MAA items≥1 + SAA 2001 | `true` |
| 14 | ⑤ token replay | — (blocked before Diameter) | `401` |
| 16 | ⑥ SIM Swap `/check` (default `maxAge=240`) | — (read-only evidence) | `{"swapped":false}` |
| 17 | ⑥ SIM Swap `/check` `maxAge=2400` | — | `{"swapped":true}` |
| 18 | ⑥ SIM Swap `/retrieve-date` | — | `{"latestSimChange":"…Z"}` |
| 19 | ⑥ unknown · no `phoneNumber` · `maxAge=0` · no token | — | `404` · `422` · `400` · `401` |
| C3–C5 | CIBA money-loop (match) | resolver BOUND | `true`, `APPROVE`, score 100 |
| C5 | CIBA wrong claimed number | — | `false` |
| C6 | CIBA token reuse | — | `401` |
| C7 | SIM Swap 3-legged (empty body) | resolver BOUND | `{"swapped":false}`; reuse → `401`; `phoneNumber` in body → `422` |
| 20 | ⑦ OTP `/send-code` | — (no signalling; lab sender logs) | `200 {"authenticationId":"<uuid>"}` |
| 21 | ⑦ read the OTP from the lab log | — | `[SAS][LAB-SMS — NOT SENT] … text="<6 digits> …"` |
| 22 | ⑦ `/validate-code` wrong · right · replay | — | `400 INVALID_OTP` · `204` · `404` |
| 23 | ⑦ 3 wrong codes burn the attempt | — | `400 400 400`, third = `VERIFICATION_FAILED` |
| 24 | ⑦ 4th send to one number · bad template · no token | — | `403 MAX_OTP_CODES_EXCEEDED` · `400` · `401` |
| C8 | OTP 3-legged (bound number) | resolver BOUND | `200`; foreign number → `403`; wrong scope → `403`; reuse → `401` |
| ICM | `/token` CIBA with `scope` · replayed `auth_req_id` · wrong/expired/replayed client assertion | — (manual §0e) | `400 invalid_request` · `400 invalid_grant` · `401 invalid_client` |
| ICM | `auth_req_id` expiry · pending binding issued to another client | — (unit-tested) | `400 expired_token` · `400 invalid_grant` |
| ICM | `client_credentials` against NV `/verify` | — (manual §0e) | 2-legged `token_use=client`; `/verify` → `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |
| ICM | JWT bearer `tel:` · missing purpose · bad subject · form `scope` | — (manual §0e) | user-bound token · `400 invalid_scope` · `400 invalid_grant` · `400 invalid_request` |
| ICM | JWT bearer `operatortoken:` | — (unit-tested; CIBA/Wi-Fi with an operator token is run manually in §0a) | user-bound token |
| — | LTE happy path on memory transport (optional) | pilot backends | `true`, score 100 |

## 0d. GSMA Open Gateway smoke / mock ↔ local SAS (verified 2026-09-10)

Lab helpers:

- `scripts/gsma_mock_server.py` — mock Open Gateway on `127.0.0.1:18099`.
- `scripts/gsma_camara_smoke.py` — CAMARA smoke/compare for `local`, `gsma`, or `compare`.
- `scripts/gsma.env.template` — copy outside the repo and fill live sandbox values.

The smoke script adds `X-Sas-*` headers only for `--target local`; it never sends them to
GSMA. Reports mask phone numbers and tokens. Do not commit a filled env file or live report.

> Since 2026-10-01 sas-camara offers the flows the sandbox uses for **NV** (Authorization
> Code + PKCE, §0a A8) and **SIM Swap** (CIBA, §0a A7), with ES256 tokens and `/jwks.json`.
> Connecting to the live sandbox still needs GSMA-side onboarding (a public HTTPS endpoint,
> client registration, test-number mapping) — see `docs/design/sas-two-binary-architecture.md` §10.

### Mock ↔ local compare (no live sandbox required)

```bash
MOCK_PORT=18099 python3 scripts/gsma_mock_server.py > /tmp/gsma_mock.log 2>&1 &
MOCK_PID=$!
./dist/run.sh > /tmp/sas.log 2>&1 &
SAS_PID=$!

curl -fsS http://127.0.0.1:18099/health
curl -fsS http://127.0.0.1:8085/camara/health

python3 scripts/gsma_camara_smoke.py --target compare \
  --gsma-api-root http://127.0.0.1:18099/camara \
  --token-url http://127.0.0.1:18099/oauth/token \
  --client-id mock-client --client-secret mock-secret \
  --gsma-test-number +251911111111 \
  --local-base http://127.0.0.1:8085 \
  --local-test-number +251911111111 \
  --tests simswap,cdr --timeout 20 \
  --report /tmp/gsma_local_compare.json --require-match

pkill -P "$SAS_PID" 2>/dev/null || true
kill "$SAS_PID" "$MOCK_PID" 2>/dev/null || true
```

Expected:

```text
[simswap-check] MATCH
[simswap-retrieve-date] MATCH
[cdr-privacy] OK
COMPARE_SUMMARY MATCH=2 OK=1
EXIT=0
```

`cdr-privacy` is local-only: it confirms the CDR/report masks the MSISDN and contains no
OTP plaintext.

### Local SAS only

```bash
python3 scripts/gsma_camara_smoke.py --target local \
  --local-base http://127.0.0.1:8085 \
  --tests nv,simswap,otp,cdr \
  --report /tmp/sas_local_smoke.json
```

Expected exit `0` for the lab dist. NV may return `devicePhoneNumberVerified:false` when
no binding evidence exists; OTP validation reads the lab log-only code.

### Live GSMA sandbox (blocked until browser values are supplied)

Cloudflare blocks curl/headless automation. Copy the template outside the repo and fill it
from a real browser session:

```bash
cp scripts/gsma.env.template /tmp/gsma.env
```

Required values: `GSMA_API_ROOT`, `GSMA_TOKEN_URL`, `GSMA_CLIENT_ID`,
`GSMA_CLIENT_SECRET` or preissued tokens, `GSMA_TEST_NUMBER`, and enabled scopes. NV
usually requires a user-bound 3-legged token (`GSMA_NV_TOKEN`, `GSMA_DISCOVERY_TOKEN`);
with a bound token the request body should be `{}`.

```bash
python3 scripts/gsma_camara_smoke.py --target gsma --env-file /tmp/gsma.env \
  --tests token,simswap,nv --timeout 20 --report /tmp/gsma_live.json
```

Do not run live OTP unless the sandbox explicitly confirms it will not send a real SMS;
the script refuses GSMA OTP without `--allow-send`.

## 0e. CAMARA ICM OAuth Phase 3 — manual runbook (verified 2026-09-11 · re-run 2026-10-01 with ES256 tokens)

Independent of §0b–§0d. No Diameter simulator is needed because the SAS uses the memory/pilot
transports. Run `java -jar dist/quarkus-run.jar` directly so the `-D` system properties win
over the config; **do not use `dist/run.sh`** here. The lab helper
`scripts/sas_oauth_jwt_tool.py` creates an RSA key and `clients.json`, and signs RS256 assertions.

**P0 — stop old processes, set the environment:**

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/silent-authentication/main
pkill -f "$PWD/dist/quarkus-run.jar" || true
export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"
export PATH="$JAVA_HOME/bin:$PATH"
J="$JAVA_HOME/bin/java"
BASE=http://localhost:8085
CID=bank-backend
TYPE='urn:ietf:params:oauth:client-assertion-type:jwt-bearer'
decode() {
  python3 -c 'import base64,json,sys; p=sys.argv[1].split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p)))' "$1"
}
"$J" -version
```
→ expect: `openjdk version "25…"` (zulu).

**P1 — automated regression:**

```bash
/usr/bin/mvn -o -B test -Dquarkus.profile=lab
python3 harness/run_hardness.py
python3 harness/run_hardness.py --mutations
python3 harness/preflight_prod.py --selftest
```
→ expect: `791` tests pass · `38/38` gates · H24 `10` + H25 `11` + H28 `8` mutations caught ·
`45/45` preflight scenarios detected.

**P2 — build/package the dist:**

```bash
/usr/bin/mvn -o -B clean package -DskipTests -Dquarkus.profile=lab
./scripts/package-dist.sh
```
→ expect: `dist/quarkus-run.jar` exists (and `dist-ecs/` when packaging `all`).

**P3 — create the lab client key + registry:**

```bash
rm -rf /tmp/sas-oauth-test
python3 scripts/sas_oauth_jwt_tool.py keygen --client-id "$CID" --out /tmp/sas-oauth-test
KEY=/tmp/sas-oauth-test/client.key
```
→ expect: prints `private_key=...client.key`, `clients_json=...clients.json`, `kid=...`.

**P4 — start the SAS with mandatory `private_key_jwt`:**

```bash
export SAS_OAUTH_CLIENTS_JSON="$(cat /tmp/sas-oauth-test/clients.json)"
nohup "$J" \
  -Dsas.security.token-validation-enabled=true \
  -Dsas.security.hmac-secret=k1 \
  -Dsas.oauth.secret=k1 \
  -Dsas.oauth.public-base-url="$BASE" \
  -Dsas.oauth.require-client-auth=true \
  -Dsas.oauth.clients-json="$SAS_OAUTH_CLIENTS_JSON" \
  --add-modules jdk.sctp \
  -jar dist/quarkus-run.jar >/tmp/sas-oauth.log 2>&1 &
echo $! >/tmp/sas-oauth.pid
for i in {1..60}; do curl -fs "$BASE/camara/health" >/dev/null && break; sleep 1; done
curl -s "$BASE/camara/health"
```
→ expect: `{"status":"UP"}`.

**P5 — discovery metadata:**

```bash
curl -s "$BASE/.well-known/oauth-authorization-server" | python3 -m json.tool
```
→ expect: `token_endpoint_auth_methods_supported` = `["private_key_jwt"]` (client auth required);
`grant_types_supported` contains `authorization_code`, `urn:openid:params:grant-type:ciba`,
`client_credentials`, `urn:ietf:params:oauth:grant-type:jwt-bearer`.

**P6 — CIBA + `private_key_jwt` happy path:**

```bash
BCA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/bc-authorize")
AUTH=$(curl -s -X POST "$BASE/bc-authorize" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$BCA" \
  -d 'scope=number-verification:verify' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["auth_req_id"])')
echo "$AUTH"
```
→ expect: a non-empty `auth_req_id` is printed.

```bash
TCA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token")
AT=$(curl -s -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$TCA" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
decode "$AT"
```
→ expect: the payload has `token_use: user`, `phone_number: +251911111111`, `aud` (lab
`http://127.0.0.1:8085/camara`); the token header is `{"typ":"at+jwt","alg":"ES256","kid":…}`
— access tokens are ES256-signed since 2026-10-01 and verified via `/jwks.json`.

```bash
curl -s -X POST "$BASE/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $AT" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `"devicePhoneNumberVerified":true`, `"decision":"APPROVE"`, `"score":100`.

**P7 — the CIBA access token is single-use:** re-run exactly the P6 verify command
→ expect: `401`, `UNAUTHENTICATED`, `token already used (single-use)`.

**P8 — `auth_req_id` replay:**

```bash
TCA2=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token")
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH" \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$TCA2"
```
→ expect: `400`, `invalid_grant`, `auth_req_id is invalid or was already exchanged`.

**P9 — 2-legged `client_credentials`:**

```bash
CCA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token")
TOK_C=$(curl -s -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$CCA" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
decode "$TOK_C"
```
→ expect: `token_use: client`, `client_id: bank-backend`, no `phone_number`.

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK_C" \
  -H 'X-Sas-Amr: mobile' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `403`, `NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK`,
the message says the token carries no user phone-number binding.

**P10 — JWT bearer, subject `tel:`:**

```bash
JBA=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" \
  --aud "$BASE/token" --sub 'tel:+251911111111' \
  --scope 'dpv:FraudPreventionAndDetection number-verification:verify')
TOK_J=$(curl -s -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
decode "$TOK_J"
```
→ expect: `token_use: user`, `phone_number: +251911111111`.

```bash
curl -s -X POST "$BASE/number-verification/v2/verify" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK_J" \
  -H 'X-Sas-Amr: mobile' -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'
```
→ expect: `true`, `APPROVE`, `score: 100`.

**P11 — JWT bearer negatives:**

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA" \
  --data-urlencode 'scope=number-verification:verify'
```
→ expect: `400`, `invalid_request`, scope must not be sent on the JWT bearer token request.

```bash
JBA_NO_PURPOSE=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" \
  --aud "$BASE/token" --sub 'tel:+251911111111' --scope 'number-verification:verify')
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA_NO_PURPOSE"
```
→ expect: `400`, `invalid_scope`, `a dpv purpose scope is required`.

```bash
JBA_BAD_SUB=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" \
  --aud "$BASE/token" --sub 'user@example.com' \
  --scope 'dpv:FraudPreventionAndDetection number-verification:verify')
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=$JBA_BAD_SUB"
```
→ expect: `400`, `invalid_grant`, `sub must be tel:<E.164> or operatortoken:<token>`.
The `operatortoken:` path needs an operator token from sas-ecs: CIBA/Wi-Fi with an operator
token is run manually in §0a (A5–A7); JWT bearer `operatortoken:` stays unit-tested; JWT bearer `tel:` is manual.

**P12 — client-authentication negatives:**

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/bc-authorize" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d "client_id=$CID" -d 'scope=number-verification:verify'
```
→ expect: `401`, `invalid_client`, `client authentication is required`.

```bash
curl -s -w '\n%{http_code}\n' -X POST "$BASE/bc-authorize" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$BCA" \
  -d 'scope=number-verification:verify'
```
→ expect: `401`, `invalid_client`, `JWT assertion jti has already been used`.

```bash
BAD_CLIENT=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id wrong-client --aud "$BASE/token")
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$BAD_CLIENT"
```
→ expect: `401`, `invalid_client`, `unknown client: wrong-client`.

```bash
EXPIRED=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/token" --ttl -5)
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$EXPIRED"
```
→ expect: `401`, `invalid_client`, `JWT assertion has expired`.

```bash
WRONG_AUD=$(python3 scripts/sas_oauth_jwt_tool.py sign --key "$KEY" --client-id "$CID" --aud "$BASE/bc-authorize")
curl -s -w '\n%{http_code}\n' -X POST "$BASE/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=number-verification:verify' \
  -d "client_id=$CID" -d "client_assertion_type=$TYPE" \
  --data-urlencode "client_assertion=$WRONG_AUD"
```
→ expect: `401`, `invalid_client`, `JWT assertion audience is invalid`.

**P13 — cleanup:**

```bash
kill "$(cat /tmp/sas-oauth.pid 2>/dev/null)" 2>/dev/null || true
pkill -f "$PWD/dist/quarkus-run.jar" || true
pgrep -f "$PWD/dist/quarkus-run.jar" || echo 'SAS stopped'
```

## 0f. TS.43 entitlement — EAP-AKA over MAP on sas-ecs (verified 2026-10-01, with pcap)

A **different** track from §2–§4: no Diameter. **sas-ecs** is the EAP server (D6
**Shape S**); it fetches vectors from the **HLR over MAP `sendAuthenticationInfo`** (SSN 6)
and confirms the number with **MAP `sendImsi`** (SSN 3 — TS 29.002 puts `sendImsi` on the
OAM SSN, *not* the HLR SSN), over **its own SCTP association** (`ss7-ecs.json`, local port
2907). The token it issues is an operator token (JWE) that **sas-camara** redeems exactly once.

> ⚠️ **Open P1 blocker:** the EAP-AKA codec is not RFC 4187 yet (see
> `docs/design/sas-two-binary-architecture.md` §9). The lab card shares the server's codec,
> so this track proves **the MAP legs and the token path**, not handset interoperability.

```
UE (lab card)          sas-ecs :8095                          HLR simulator (jSS7) :2906 / ctrl :8087
  GET /ts43/challenge ─► EntitlementSbb
                          │ MAP SAI (TC-BEGIN, SSN 6) ────────► sendAuthenticationInfo
                          │◄──────────────────── TC-END ─────── UMTS quintet (RAND,XRES,CK,IK,AUTN)
  ◄── EAP-Request/AKA-Challenge
  POST /ts43/respond ──► AT_MAC → AT_RES
                          │ MAP SendIMSI (SSN 3) ─────────────► sendImsi(+251911111111)
                          │◄──────────────────────────────────── IMSI of that number
  ◄── operator token (JWE)
bank ── POST /camara/number-verification/v2/verify  (Bearer operatortoken:…, X-Sas-Access-Tech: WIFI) ──► sas-camara :8085
```

### 0f.1 Build + 3 processes

```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/zulu-25"; export PATH="$JAVA_HOME/bin:$PATH"
(cd sas-jss7-testapp && /usr/bin/mvn -o -q package -DskipTests)     # simulator (standalone build)
./scripts/package-dist.sh all                                       # dist/ + dist-ecs/

# (1) HLR simulator — SCTP :2906, peer = the sas-ecs association (2907), control :8087.
#     Run it in its own directory: jSS7 writes *.xml state files into the CWD.
mkdir -p /tmp/hlr-sim && (cd /tmp/hlr-sim && java --add-modules jdk.sctp \
  -jar "$OLDPWD/sas-jss7-testapp/target/sas-jss7-testapp.jar" \
  --listen-port 2906 --peer-port 2907 --http-port 8087) &

# (2) sas-ecs — MAP SAI for vectors, MAP SendIMSI for the number
(cd dist-ecs && SAS_TRANSPORT_AUTHVECTOR=map SAS_BINDING_SOURCE_ORDER=map-smi \
   SAS_TRANSPORT_JSS7_CONFIG=$PWD/configs/ss7-ecs.json ./run.sh) &

# (3) sas-camara — redeems the operator token (clean config, see the upgrade note in §0a A0)
(cd dist && ./run.sh) &
```

The sas-ecs log must show (otherwise see §8):

```
[map-verifier] jSS7 stack started — HLR GT=251911000000 (PSI+SAI, no ATI)
[ECS] auth-vector transport = MAP SAI (TS 29.002)
[binding] SendIMSI ready on the shared jSS7 stack — HLR GT=251911000000
[ECS] binding source map-smi = MAP SendIMSI (TS 29.002)
=== sas-ecs ready — EntitlementSbb bound to Ts43RequestEvent, token format=operator-jwe ===
```

### 0f.2 Run the demo (one command)

```bash
./scripts/ts43-lab-demo.sh http://127.0.0.1:8095 http://127.0.0.1:8087 \
    655010000000001 +251911111111 http://127.0.0.1:8085
```

Verified output (2026-10-01):

```
── UE → SAS    GET /ts43/challenge      reqId=ts43-…  challenge=AQEAKRcB…
── HLR sim     …                        {"subscribers":[{"imsi":"655010000000001","msisdn":"+251911111111","attached":true,"vectors":1}],…}
── UE          EAP-Response/AKA-Challenge (AT_MAC + AT_RES)
── UE → SAS    POST /ts43/respond       token=eyJlcGsiOnsia3R5IjoiRUMi…      (JWE)
── bank → CAMARA  POST /camara/number-verification/v2/verify (Wi-Fi path, operator token)
                                        {"devicePhoneNumberVerified":true}
── bank → CAMARA  replay the same operator token (single use)
                                        HTTP 401 (expected 401)
── DEMO OK — EAP-AKA on sas-ecs (MAP SAI + SendIMSI), operator token redeemed once on sas-camara
```

A 5th argument `-` stops after the token (when sas-camara is not running).

### 0f.3 MAP on the wire — pcap

```bash
scripts/ecs-map-smoke.sh      # runs (1)+(2)+(3) + the demo + the 0f.4 matrix in a user netns, no root
P=target/ecs-map-smoke/ecs-map-smoke.pcap
tshark -r $P -q -z io,phs                 # sctp → m3ua → sccp → tcap → gsm_map
tshark -r $P -Y gsm_map                   # each invoke/returnResult
curl -s http://127.0.0.1:8087/messages    # simulator log (manual run)
```

Expected (demo, exactly 2 dialogs, in order):

```
1 → 2  GSM MAP  invoke sendAuthenticationInfo
2 → 1  GSM MAP  returnResultLast sendAuthenticationInfo
1 → 2  GSM MAP  invoke sendIMSI 251911111111
2 → 1  GSM MAP  returnResultLast sendIMSI
```
Simulator log: `OUT sendAuthenticationInfo | ReturnResultLast | 1 quintuplet(s) imsi=655****01`,
then `OUT sendImsi | ReturnResultLast | msisdn=251911111111 imsi=655****01`. No ATI and no
SRI-SM (gate H25 bans both on the entitlement path).

### 0f.4 Fail-closed (automated in `ecs-map-smoke.sh`, verified 2026-10-01 on sas-ecs)

```bash
ECS=http://127.0.0.1:8095
# (a) another SIM answers → AT_MAC mismatch; NO SendIMSI dialog
CJ=$(curl -s "$ECS/ts43/challenge?imsi=655010000000001&msisdn=%2B251911111111")
R=$(java -cp sas-entitlement/target/classes et.restlink.sas.entitlement.lab.LabSimCardMain \
     655010000000002 "$(jq -r .challenge <<<"$CJ")")
curl -s -X POST $ECS/ts43/respond -H 'Content-Type: application/json' \
  -d "{\"reqId\":\"$(jq -r .reqId <<<"$CJ")\",\"imsi\":\"655010000000001\",\"msisdn\":\"+251911111111\",\"response\":\"$R\"}"
# → {"code":"EAP_FAILED","message":"AT_MAC mismatch"}

# (b1) POST the same reqId again → the recorded answer (idempotent, no new dialog)
# (b2) a reqId with no challenge → {"code":"EAP_FAILED","message":"no outstanding challenge for this reqId"}

# (c) unknown IMSI → no vector
curl -s "$ECS/ts43/challenge?imsi=655010000009999&msisdn=%2B251911111111"
# → {"code":"ENTITLEMENT_UNAVAILABLE","message":"no authentication vector available"}

# (d) claim a number the HLR does not know → EAP OK, the NUMBER is refused, no token
#     (pcap: invoke sendIMSI 251900000000 → returnError)
# → {"code":"EAP_FAILED","message":"network did not confirm the claimed number"}

# (e) starve SAI → dies at the Challenge
curl -X POST -d '{"vectors":0}' http://127.0.0.1:8087/state    # → no authentication vector available
curl -X POST -d '{"vectors":1}' http://127.0.0.1:8087/state

# (f) detach → the HLR answers returnError to SAI
curl -X POST -d '{"attached":false}' http://127.0.0.1:8087/state # → no authentication vector available
curl -X POST -d '{"attached":true}' http://127.0.0.1:8087/state
```

| Input | Result (sas-ecs) | MAP on the wire |
|---|---|---|
| right IMSI + number | token (JWE); CAMARA redeem → `true`, replay → `401` | SAI → result, SendIMSI → result |
| another SIM answers | `AT_MAC mismatch` | SAI only, **no** SendIMSI |
| same `reqId` again | the recorded answer | no new dialog |
| `reqId` without a challenge | `no outstanding challenge for this reqId` | — |
| IMSI unknown to the HLR | `no authentication vector available` | SAI → returnError |
| number unknown to the HLR | `network did not confirm the claimed number`, no token | SAI → result, SendIMSI → returnError |
| `vectors=0` | `no authentication vector available` | SAI → returnError |
| `attached=false` | `no authentication vector available` | SAI → returnError |

### 0f.5 Automated tests for this track

```bash
mvn -o test -pl sas-ecs-host,sas-operator-adapter -am \
    -Dtest='EntitlementSessionsTest,LabSimAkaCardTest,SubscriberBindingResourceAdaptorTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

`LabSimAkaCardTest` only proves that **the lab card and the server agree with each other**
(same codec) — not RFC 4187 conformance (the P1 blocker above).

## 1. Build

> **Pre-M3** for §1–§4: transports belong to sas-ecs now (§00.2). The CAMARA-only steps (SIM Swap/OTP surfaces, CIBA) still apply to sas-camara.

```bash
(cd sas-diameter-testapp && /usr/bin/mvn -B clean package)      # test-app jar ~26 MB
(cd sas-jss7-testapp && /usr/bin/mvn -B -o package -DskipTests) # HLR simulator (track §0f)
/usr/bin/mvn -B clean package -DskipTests -Dquarkus.profile=lab  # 7-module root reactor →
                                                                 #   sas-host/target/quarkus-app     (sas-camara)
                                                                 #   sas-ecs-host/target/quarkus-app (sas-ecs)
```

> **`-Dquarkus.profile=lab` is MANDATORY** for building the two binaries: some properties
> are fixed at build time (e.g. sas-camara's `quarkus.datasource.db-kind`) and baked per
> profile. Without the flag the fast-jar **silently fails to boot** (stops right after
> `MicroSleeContainer started`, the log only shows `Failed to load config value…`).
> `scripts/package-dist.sh` always builds with it (prod artifact: `-Dquarkus.profile=prod`).

## 2. Start the 3 operator simulator instances (S6a + SWx + Gx)

```bash
# Instance 1: S6a HSS (Diameter SCTP :3868) + control UI :8086 — cellular (ULR/ULA)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar &

# Instance 2: SWx = operator 3GPP AAA ↔ HSS (SCTP :3869) + control UI :18086 — lab verifier
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3869 --web-port 18086 &

# Instance 3: PCRF Gx (SCTP :3870) + control UI :28086 — resolver binding (required when
# sas-camara runs with -Dsas.transport.resolver=sd in §3; without it scenario ① answers NO_BINDING)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 3870 --web-port 28086 &
```

> The lab HSS accepts **one inbound SCTP association per listen port** → SWx needs its own
> port; sas-camara points there with `-Dsas.transport.diameter.swx.peer-port=3869`. (Lab:
> the `swxverifier` RA stands in for the operator leg. Production: sas-camara verifies the
> Wi-Fi path with the entitlement server's **operator token**, freshness via Sh UDR/SNR,
> and never opens SWx.)

## 3. Start sas-camara with corsac transports

```bash
cd sas-host && java \
  -Dsas.transport.s6a=corsac \
  -Dsas.transport.swx=corsac \
  -Dsas.transport.diameter.swx.peer-port=3869 \
  -Dsas.transport.resolver=sd \
  -Dsas.transport.sd.peer-port=3870 \
  -jar target/quarkus-app/quarkus-run.jar &
```

Wait for `Peer is up for Association [name=s6a-sas…]`, `[name=swx-sas…]` — usually ~15–20 s,
but up to ~3 minutes on a multi-homed host: the simulator accepts loopback sources only and
rejects handshakes from LAN/IPv6 addresses (sim log: `Received connect request from non
provisioned … address. Closing Channel`) until a client retry picks 127.0.0.1.
`sas.transport.resolver=sd` enables the PCRF Gx binding probe (:3870 — needs instance 3 in
§2); drop that line for the memory pilot resolver. Resolver choices: `memory | cgnat | radius | sd`.

Scenario ④ also needs **sas-ecs** (the entitlement server, :8095):
`(cd sas-ecs-host && java -Dquarkus.profile=lab --add-modules jdk.sctp -jar target/quarkus-app/quarkus-run.jar &)`
— or `dist-ecs/run.sh`. (The old `-Dsas.entitlement.hmac-secret` is no longer needed: the
token is a JWE operator token.)

## 4. Scenarios

### ① S6a LTE happy path

The resolver is seeded with `10.20.30.40 → +251911111111` (IMSI `655010000000001`) — with
`resolver=sd` that binding lives in the Gx BindingRegistry of instance :3870
(`POST http://127.0.0.1:28086/api/binding`). The registry is keyed by **IP, upsert
replaces** (one binding per IP): POSTing the same IP with another MSISDN **replaces** the
seed — later verifies resolve to the new number and usually answer `false` (the lab
verifier does not know that subscriber). `AMBIGUOUS_BINDING` on sd only happens when the
CCA carries ≥2 distinct E.164 numbers (the lab test app cannot produce it; CGNAT/RADIUS
backends hit it on a duplicate IP:port:ts).

> ⚠️ **Real expectation with `s6a=corsac`**: `CorsacS6aVerifierBackend` fails closed on the
> SIM-swap dimension (the lab test app has **no** Sh UDR handler → no freshness source) ⇒
> scenarios ①/①b answer `false` / `SIM_SWAP_SUSPECT` although CCR→CCA and ULR→ULA both
> return 2001 — fail-closed working as designed (not a bug). For ①/①b `true`: run
> sas-camara without `-Dsas.transport.s6a=corsac` (memory pilot transport).

CAMARA primary path:

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false}   (CAMARA-pure boolean)
#              (CCR→CCA 2001 + ULR→ULA 2001, but no Sh UDR freshness → veto)
# memory transport (no -Dsas.transport.s6a) → {"devicePhoneNumberVerified":true}
```

Opt-in assurance detail (score/factors for the bank's own risk decision) + risk class:

```bash
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer demo' \
  -H 'x-correlator: t1b' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -H 'X-Sas-Access-Tech: LTE' \
  -H 'X-Sas-Assurance-Detail: true' -H 'X-Sas-Risk-Class: TRANSFER' \
  -d '{"phoneNumber":"+251911111111"}'
# s6a=corsac → {"devicePhoneNumberVerified":false,"reqId":"…","decision":"FALLBACK",
#     "assurance":{"score":70,"level":"FALLBACK","threshold":80,"riskClass":"TRANSFER",
#       "factors":{"ipBindingFresh":{"value":1.0,…},"reachable":{"value":1.0,…},
#         "notSimSwapped":{"value":0.0,…},"locationPlausible":{"value":1.0,…}}},
#     "fallbackReason":"SIM_SWAP_SUSPECT"}   — the CDR still keeps the full flow (look up by reqId)
# memory transport → decision APPROVE, score 100 — threshold per risk class
```

Discovery (returns the bound number; resolver only, no Verifier needed):

```bash
curl -s http://localhost:8085/number-verification/v2/device-phone-number \
  -H 'Authorization: Bearer demo' -H 'x-correlator: t1c' -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555'
# → {"devicePhoneNumber":"+251911111111"}
```

### ② Fail-closed — subscriber detached

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":false}'
# verify again as in ① → ULA answers 5421 → {"devicePhoneNumberVerified":false}
```

### ③ Fail-closed — subscriber barred (ULR/ULA)

```bash
curl -s -X POST http://127.0.0.1:8086/api/subscriber -H 'Content-Type: application/json' \
     -d '{"identity":"655010000000001","attached":true,"barred":true}'
# verify again → ULA 2001 + Subscriber-Status=ODB → {"devicePhoneNumberVerified":false}
```

Reset to defaults: `curl -X POST http://127.0.0.1:8086/api/reset`

### ④ TS.43 Wi-Fi path — operator token issued by sas-ecs, redeemed by sas-camara

> Lab: sas-camara's `swxverifier` RA opens MAR/SAR to SWx :3869 to stand in for the
> 3GPP AAA↔HSS leg. Production: no SWx — identity comes from the operator token (the
> entitlement server already ran EAP-AKA), freshness from read-only **Sh UDR/SNR**.

```bash
TOKEN=$(curl -s -X POST http://localhost:8095/entitlement/issue \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"+251911111111","imsi":"655010000000001","eapMethod":"EAP-AKA"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")

curl -s -X POST http://localhost:8085/verify \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer operatortoken:$TOKEN" \
  -H 'x-correlator: t4' -d '{}'
# → true   (lab corsac: MAR→MAA items=1, SAR→SAA on :3869)
```

> Re-verified 2026-10-01 with **memory** SWx (`true`, replay `401`, see §0a A5–A6). With
> **corsac** SWx to :3869 it has **not been re-run** since the split: the new token carries
> no IMSI, so the MAR is keyed by MSISDN.

### ⑤ Token single-use

Reusing `$TOKEN` from ④ → `401` (sas-camara's `sas_operator_token_jti` ledger; one atomic
INSERT on `(iss, jti)`, also correct with several instances on one database).

### ⑥ SIM Swap — CAMARA SimSwap v2.1.0 (`/sim-swap/v2`)

The same read-only evidence the Verifier scores as `notSimSwapped` (MAP
`lastUpdateLocation` → Sh UDR → SWx), exposed as its own CAMARA API for banks. No AIR/AIA
(never burns the AuC SQN), no IDR/IDA, no interconnect ATI (FS.11). **Fail-closed:** no
evidence ⇒ `404 IDENTIFIER_NOT_FOUND`, never `swapped:false`.

```bash
# 2-legged (lab: token validation off) — number in the body
curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw1' -d '{"phoneNumber":"+251911111111"}'
# → {"swapped":false}      (seeded SIM change 10 days ago, outside the default maxAge=240h)

curl -s -X POST http://localhost:8085/sim-swap/v2/check \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw2' -d '{"phoneNumber":"+251911111111","maxAge":2400}'
# → {"swapped":true}       (maxAge in HOURS, spec 1..2400)

curl -s -X POST http://localhost:8085/sim-swap/v2/retrieve-date \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: sw3' -d '{"phoneNumber":"+251911111111"}'
# → {"latestSimChange":"2026-08-30T…Z"}   (RFC 3339; no monitoredPeriod)

# Error codes per CAMARA commonalities
#   unknown number / no evidence  → 404 IDENTIFIER_NOT_FOUND
#   no phoneNumber (2-legged)     → 422 MISSING_IDENTIFIER
#   maxAge=0 or 2401              → 400 OUT_OF_RANGE (never clamped)
#   no Authorization              → 401 UNAUTHENTICATED
```

3-legged (sas-camara with validation as in §4b): identity comes from the access-token
binding, body `{}`; scope `sim-swap:check` / `sim-swap:retrieve-date` (the `sim-swap` family
grants both). Sending `phoneNumber` with a bound token → `422 UNNECESSARY_IDENTIFIER`; a
token of another family → `403 PERMISSION_DENIED`; token reuse → `401`. Commands: §0b
Step C7; SIM Swap with a CIBA token obtained from an operator token: §0a A7.

### ⑦ OTP SMS — CAMARA OneTimePasswordSMS v1.1.1 (`/one-time-password-sms/v1`)

**The FALLBACK branch**: when `/verify` answers FALLBACK the bank falls back to an SMS OTP.
sas-camara only **orchestrates** (mints the OTP, keeps the state, validates) — the SMS
itself is sent by the operator's SMSC (Restlink does not wholesale SMS). Lab:
`sas.otp.sms-delivery=log` → the message (**with the OTP**) is written to the log and
**sent nowhere**. Prod: the surface is OFF (`sas.otp.enabled=false`); preflight `PRO-29`
refuses a lab sender.

```bash
# 1) send-code — message is a TEMPLATE, must contain {{code}}, ≤160 chars
curl -s -X POST http://localhost:8085/one-time-password-sms/v1/send-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp1' \
  -d '{"phoneNumber":"+251911111111","message":"{{code}} is your Restlink code"}'
# → 200 {"authenticationId":"<36-char uuid>"}   (the OTP is NEVER in the body)

# 2) read the OTP from the lab log (lab only — which is why prod may not use the log sender)
grep 'LAB-SMS' /tmp/sas-otp.log | tail -1
# → WARN LabLogSmsDelivery - [SAS][LAB-SMS — NOT SENT] to=+251****11 chars=28 text="921540 is your Restlink code"

# 3) validate-code wrong → 400 ONE_TIME_PASSWORD_SMS.INVALID_OTP
#    the 3rd wrong one (sas.otp.max-attempts) → 400 …VERIFICATION_FAILED (attempt burned,
#    after that even the RIGHT code answers VERIFICATION_FAILED)
#    right code → 204 (no body); reusing the authenticationId → 404 NOT_FOUND
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:8085/one-time-password-sms/v1/validate-code \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer lab' \
  -H 'x-correlator: otp2' -d '{"authenticationId":"<uuid>","code":"921540"}'
# → 204
```

Verified fail-closed branches: no `{{code}}` / message >160 → `400 INVALID_ARGUMENT`; more
than `sas.otp.max-codes-per-number` (3) sends to ONE number within `rate-window` →
`403 …MAX_OTP_CODES_EXCEEDED` (other numbers unaffected); operator refusal
(`NOT_ALLOWED`/`BLOCKED` from the delivery seam) → `403 …PHONE_NUMBER_*`; no delivery route
→ `500 INTERNAL_ERROR` and **no** `authenticationId`; `sas.otp.enabled=false` (or the key
missing) → `404 NOT_FOUND` on both endpoints.

3-legged: one scope `one-time-password-sms:send-validate` covers both endpoints;
`phoneNumber` must **equal** the token binding, otherwise `403 PERMISSION_DENIED` (no OTP is
even minted). Full commands: §0b Step C8.

### ⑧ CDR/audit for SimSwap + OTP

Each ⑥/⑦ request writes **one** API CDR row, keyed by `x-correlator`, to the durable CSV
file `dist/logs/sas.cdr` (log4j logger `SAS_CDR`) and to the DB ledger shown at
`/admin/cdr`. `operation` is `SIMSWAP` or `OTP`; 2xx → `COMPLETED`, 4xx/5xx → `FAILED` with
`http=` and `code=` in `detail`.

```bash
grep -E 'sw1|otp1|otp2' dist/logs/sas.cdr
```

The dashboard merges freshly queued rows with persisted ones, so history survives a
restart. Privacy rules smoke-tested: the MSISDN is masked before the CDR port; **no** OTP
plaintext, SMS text or raw MSISDN/IMSI. OTP rows keep only `authenticationId`,
`messageChars`, delivery/result; SimSwap rows keep `action`, `maxAgeHours`, `swapped`, and
`evidence=ABSENT` when failing closed.

### ⑨ TS.43 entitlement over MAP on sas-ecs — no Diameter needed

A scenario of its own, run **without** the 3 instances of §2. Full runbook + pcap: §0f.

```bash
scripts/ecs-map-smoke.sh     # HLR sim + sas-ecs (MAP) + sas-camara + demo + fail-closed matrix, with pcap
```

Expectation table: §0f.4.

## 4b. Money loop — operator Auth Server (CAMARA CIBA)

This is the **revenue layer**: sas-camara issues a user-bound token (the phone number is
bound into the token) and only then does the bank call `/verify` to compare. Run
sas-camara with validation on (add the corsac transport flags from §3 to run it alongside
Diameter; CIBA on memory transports works on its own):

```bash
java -Dsas.security.token-validation-enabled=true \
     -Dsas.security.hmac-secret=k1 -Dsas.oauth.secret=k1 \
     -jar sas-host/target/quarkus-app/quarkus-run.jar &
```

```bash
# 1) The bank asks for an auth_req_id (cellular anchor: the resolver maps IP:port -> MSISDN)
curl -s -X POST http://localhost:8085/bc-authorize \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'X-Sas-Src-Ip: 10.20.30.40' -H 'X-Sas-Src-Port: 55555' \
  -d 'scope=number-verification:verify'
# → {"auth_req_id":"…","expires_in":120}

# 2) Exchange it for a token (CIBA grant; one auth_req_id = ONE token)
curl -s -X POST http://localhost:8085/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:openid:params:grant-type:ciba' \
  --data-urlencode "auth_req_id=$AUTH"
# → {"access_token":"<JWS ES256 typ=at+jwt, phone_number=+E164, aud>","expires_in":300,…}

# 3) /verify compares the claimed number with the number BOUND in the token
curl -s -X POST http://localhost:8085/number-verification/v2/verify \
  -H "Authorization: Bearer $AT" -H 'X-Sas-Amr: mobile' \
  -H 'X-Sas-Assurance-Detail: true' \
  -d '{"phoneNumber":"+251911111111"}'   # match → true + assurance
#    phoneNumber other than the bound one → false ; token reuse → 401 single-use
```

Access tokens are **ES256** (`typ=at+jwt`) and verified via `/jwks.json`;
`-Dsas.oauth.secret=k1` only matters for the old lab HS256 mode. Wi-Fi track: replace
`X-Sas-Src-Ip/Port` with `login_hint=operatortoken:<tk>` using a token issued by
**sas-ecs** (§0a A7).

## 4c. NV Authorization Code + PKCE (network authentication)

The flow CAMARA NV (and the GSMA Open Gateway sandbox) uses: the device calls `/authorize`
over its cellular bearer → sas-camara resolves `IP:port:ts → MSISDN` → issues a one-time
`code` (60 s) → the bank backend exchanges `code` + `code_verifier` for an ES256 access
token (`amr=["mno"]`) + a pairwise ID token. Commands + expectations: §0a A8–A9.

## 5. Observe the signalling

```bash
curl -s http://127.0.0.1:8086/api/messages     # S6a messages
curl -s http://127.0.0.1:18086/api/messages    # SWx (operator AAA↔HSS, lab) messages
curl -s http://127.0.0.1:28086/api/messages    # Gx (PCRF) messages — resolver binding
curl -s http://127.0.0.1:28086/api/binding     # Gx binding registry (10.20.30.40 → +251911111111)
curl -s http://127.0.0.1:8087/messages         # HLR simulator (sas-ecs MAP SAI / SendIMSI)
```

Or open `http://127.0.0.1:8086/` in a browser — the table refreshes every 2 s: time,
command, session-id, result-code, key AVPs (`user=… rat=EUTRAN`, `vectors=N`…).

Pcaps (no root, run in a user netns):

```bash
scripts/split-smoke.sh    && tshark -r target/split-smoke/split-smoke.pcap -Y http.request
scripts/ecs-map-smoke.sh  && tshark -r target/ecs-map-smoke/ecs-map-smoke.pcap -Y gsm_map
```

## 6. Expectation matrix

| Scenario | Signalling | Result |
|---|---|---|
| LTE happy (resolver=sd, s6a=corsac) | CCA 2001 + ULA 2001, no Sh UDR | `false` + `SIM_SWAP_SUSPECT` (correct fail-closed) |
| LTE happy (memory transport) | pilot resolver/verifier | `true` |
| Detached | ULA **5421** | `false` |
| Barred | ULA 2001 + Subscriber-Status ODB | `false` |
| Gx unknown IP | CCA 5030 | `false` (NO_BINDING) |
| Valid operator token (issued by sas-ecs, redeemed by sas-camara) | lab: MAA items≥1 + SAA 2001 | `true` |
| Token replay | — (blocked before Diameter, ledger) | `401` |
| Wrong/missing amr | — | `403` |
| Body without phoneNumber/hashed | — | `400 INVALID_ARGUMENT` |
| Assurance detail not opted in | — | plain boolean response (CAMARA-pure) |
| SIM Swap `/check` (10-day seed, default `maxAge` 240h) | — (reads evidence, no Diameter) | `{"swapped":false}` |
| SIM Swap `/check` `maxAge=2400` | — | `{"swapped":true}` |
| SIM Swap `/retrieve-date` | — | `{"latestSimChange":"…Z"}` |
| SIM Swap unknown number / no evidence | — | `404 IDENTIFIER_NOT_FOUND` (fail-closed) |
| SIM Swap 3-legged + `phoneNumber` in body | — | `422 UNNECESSARY_IDENTIFIER` |
| OTP SMS `/send-code` (lab sender `log`) | — (no signalling) | `200 {"authenticationId":…}`, the OTP only in the log |
| OTP SMS `/validate-code` right code | — | `204` (reuse → `404`) |
| OTP SMS 3 wrong codes | — | `INVALID_OTP`, `INVALID_OTP`, `VERIFICATION_FAILED` |
| OTP SMS more than 3 codes/number/hour | — | `403 MAX_OTP_CODES_EXCEEDED` |
| OTP SMS 3-legged, number ≠ bound number | — | `403 PERMISSION_DENIED` (no OTP minted) |
| `/token` CIBA with `scope` · replayed `auth_req_id` · wrong/expired/replayed client assertion | — (manual §0e) | `400 invalid_request` · `400 invalid_grant` · `401 invalid_client` |
| expired `auth_req_id` · pending binding of another client | — (unit-tested) | `400 expired_token` · `400 invalid_grant` |
| `client_credentials` against NV `/verify` | — (manual §0e) | 2-legged `token_use=client`; `/verify` → `403 NUMBER_VERIFICATION.USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` |
| JWT bearer `tel:` · missing purpose · bad `sub` · form `scope` | — (manual §0e) | user-bound token · `400 invalid_scope` · `400 invalid_grant` · `400 invalid_request` |
| JWT bearer `operatortoken:` | — (unit-tested) | user-bound token |
| NV Authorization Code + PKCE (§0a A8) | resolver BOUND | `302 code` → ES256 access token `amr=["mno"]` + pairwise ID token → `/verify` `true` |
| code reuse · no PKCE · unregistered `redirect_uri` (§0a A9) | — | `400 invalid_grant` · `302 error=invalid_request` · `400`, no redirect |
| CIBA `login_hint=operatortoken:` (§0a A7) | GET sas-ecs `/jwks.json` (cached) | ES256 access token → SIM Swap `{"swapped":false}`; token reuse → `403` |
| Surface isolation (§0a A10) | — | CAMARA has no `/ts43`, `/entitlement`; ECS has no `/token`, discovery → `404` |
| Entitlement over MAP on sas-ecs (§0f) | SAI → result, SendIMSI → result | JWE token; CAMARA `true`, replay `401` |
| Entitlement fail-closed (§0f.4) | SAI/SendIMSI → returnError | `no authentication vector available` / `network did not confirm the claimed number` |

## 7. Other tests in the tree

```bash
mvn -o -B test -Dquarkus.profile=lab     # from the repo root: 791 tests / 9 modules, no network
python3 harness/run_hardness.py          # 38/38 gates (H1–H28)
python3 harness/run_hardness.py --mutations    # H24 10/10 + H25 11/11 + H28 8/8
python3 harness/preflight_prod.py --service all   # verdict for THIS environment (exit = #failed checks)
python3 harness/preflight_prod.py --selftest      # 45/45 misconfiguration scenarios detected (camara + ecs)

scripts/split-smoke.sh                   # two binaries: operator token, CIBA, NV auth code, isolation — pcap
scripts/ecs-map-smoke.sh                 # sas-ecs ↔ HLR over MAP + fail-closed matrix — pcap
mvn -o test -pl sas-ecs-host,sas-operator-adapter -am \
    -Dtest='EntitlementSessionsTest,LabSimAkaCardTest,SubscriberBindingResourceAdaptorTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

`mvn -o test` must be green **before** any lab smoke. `LabSimAkaCardTest` only proves that
the lab card and the server agree with each other (same codec), **not** RFC 4187
conformance — the EAP-AKA codec is still a P1 blocker
(`docs/design/sas-two-binary-architecture.md` §9).

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `UnsupportedClassVersionError` running a jar | the default `java` is zulu-8 (shim) | use the full zulu-25 path |
| A binary does not boot, silent after `MicroSleeContainer started`, the log is all `Failed to load config value…` | built without `-Dquarkus.profile=lab` (build-time properties are baked per profile) | rebuild: `mvn -o -B clean package -DskipTests -Dquarkus.profile=lab` |
| `dist/run.sh` with `SAS_JAVA_OPTS=-Dsas.transport…` but the transport stays memory | that key **is** in `configs/application.properties`; the file loaded through `quarkus.config.locations` beats `-D` | edit the config file (for keys **absent** from the file, `-D` still works, e.g. `sas.oauth.clients-json`) |
| After `package-dist.sh` the dist still runs the old config (e.g. `/authorize` → `NO_BINDING`, resolver=sd) | existing `configs/*` are **never overwritten**; the new defaults are in `*.new` | `diff dist/configs/application.properties{,.new}` and merge, or package elsewhere (`SAS_DIST_DIR=…`) |
| `/authorize` → `error=access_denied … NO_BINDING` | no `X-Sas-Src-Port` (or a tuple the resolver does not know) — the CGNAT IP+port+ts rule | send both `X-Sas-Src-Ip` and `X-Sas-Src-Port` (lab: `10.20.30.40:55555`) |
| `/authorize` → `400 unknown client_id` / `/token` → `unknown client` | `sas.oauth.clients-json` not loaded | pass it via `SAS_JAVA_OPTS=-Dsas.oauth.clients-json=…` (§0a A1) or env `SAS_OAUTH_CLIENTS_JSON` (prod) |
| `/authorize` with `X-Sas-Src-*` headers still `access_denied` in prod | prod does not trust the headers (`trust-src-header=false`) and uses the connection source | set `SAS_AUTHORIZE_TRUST_SRC_HEADER=true` only behind an edge proxy that overwrites them |
| Operator token → `401`/`403` at sas-camara right after issue | issuer not in `issuers-json`; JWKS not reachable (prod: HTTPS only); token expired (≤300 s) or already used | read the log line `[SAS] operator token rejected: <reason>` |
| First token after restarting sas-ecs is refused | the lab uses an ephemeral signing key → new `kid`; sas-camara refetches the JWKS on an unknown `kid` but **at most once per 30 s per issuer** | retry after 30 s, or configure a fixed `sas.ecs.signing-jwk` |
| `/entitlement/exchange` → `404 legacy exchange is disabled` | prod turns the ECS-local redeem off (`legacy-exchange-enabled=false`) — sas-camara is the only redeemer | redeem at sas-camara (`/verify`, `/bc-authorize`, `/token` jwt-bearer) |
| `Peer is up` is very slow (up to ~3 min), sim log `Received connect request from non provisioned … address` | multi-homed host: the SCTP client tries LAN/IPv6 sources, the sim accepts loopback only | wait for a retry to pick 127.0.0.1; nothing to do |
| Verify always `false`, the HSS receives nothing | SCTP not "Peer is up" yet | wait; check the 3 associations |
| Verify `false` + `SIM_SWAP_SUSPECT` although CCA/ULA are 2001 | `s6a=corsac` fails closed on SIM swap (the lab has no Sh UDR handler) | by design; for `true` use the memory transport |
| Discovery `403 USER_NOT_AUTHENTICATED_BY_MOBILE_NETWORK` | the binding resolves to an MSISDN the lab verifier does not know (e.g. after POST `/api/binding` replaced the seed) | `POST http://127.0.0.1:28086/api/reset` to re-seed |
| Scenario ① `NO_BINDING` | the Gx instance :3870 is missing (resolver=sd) | start instance 3 from §2 |
| SWx timeout although the HSS sees MAR/MAA | 2 links with one origin-host on one port | move SWx to its own port (`swx.peer-port`) |
| `/sim-swap/v2/*` answers `404 IDENTIFIER_NOT_FOUND` for a seeded number | no binding-age source wired any more: `sas.transport.map=jss7` **and** `s6a`/`swx`=corsac (real Sh UDR/SNR not there yet — open item) | fail-closed by design; keep the MAP transport on memory for a `swapped` demo |
| `/sim-swap/v2/check` answers `403 PERMISSION_DENIED` with a valid token | the token lacks the `sim-swap` scope family | ask for a token with `scope=sim-swap:check` (or `sim-swap:retrieve-date`) |
| `/one-time-password-sms/v1/*` answers `404 NOT_FOUND` to everything | `sas.otp.enabled` missing/`false` in the loaded config | add the `sas.otp.*` block (enabled=true, sms-delivery=log) and restart — prod keeps it OFF on purpose (`PRO-29`) |
| No OTP to validate | the lab sender writes the OTP to the **log**, never the body | `grep 'LAB-SMS' <log>` |
| `403 MAX_OTP_CODES_EXCEEDED` after one OTP | the in-memory rate window still holds earlier sends | restart, or use another number |
| `500 INTERNAL_ERROR` on send-code | `sas.otp.sms-delivery` is not `log` and no real operator adapter exists | lab: set `log`; prod: keep the surface OFF until an SMSC/SGd adapter (TS 29.338) exists |
| No `dist/logs/sas.cdr` | the jar was run directly without `-Dsas.log.dir`; the appender falls back to `target/logs/sas.cdr` | use `dist/run.sh`, or look in `target/logs/sas.cdr` |
| No CDR lines on the console | the `SAS_CDR` logger is routed `additivity=false` into the CSV file | read `dist/logs/sas.cdr` or `/admin/cdr` |
| `/admin/cdr` misses older history | `sas.cdr.db.enabled=false`, the lab DB was deleted, or not persisted yet | enable the DB CDR, keep `dist/data/`; the CSV file stays durable |
| sas-ecs has no association to the HLR sim | the sim waits for another peer port (default `--peer-port` 2905 is sas-camara's) | run the sim with `--peer-port 2907` (the local port of `ss7-ecs.json`) |
| `/ts43/challenge` answers `no authentication vector available` while the log shows `[auth-vector] SAI sent` | the simulator did not answer, or the MAP service is not `activate()`d; an unknown IMSI / vectors=0 / detached give the same answer | `curl -s :8087/messages`; the `ecs-map-smoke.sh` pcap |
| Unknown IMSI: `no authentication vector available` only after ~2 s although the pcap shows `returnError` immediately | the SAI `returnError` does not complete the request early — it waits for the 2 s SAI budget (still fail-closed) | small open item: map `returnError` to an immediate failure |
| `[binding] SendIMSI send failed … No AS found for routing message … si=3` | the SS7 config routes SSN 6 only; `sendImsi` rides **SSN 3 (OAM)** | add the route + `ssn: 3` service (present in `ss7-ecs.json`) |
| `Ss7ConfigException: Cannot load SCTP backend NETTY_KERNEL` | `org.mobicents.protocols.sctp:sctp-impl` missing from the runtime classpath | it ships through `sas-operator-adapter`; rebuild the dist |
| `Ss7ConfigException: F-Stack sidecar socket missing: data/sctp-fstack.sock` | the config still defaults to DPDK/F-Stack | add `"backend": "netty_kernel"` to the `sctp` block |
| `MAPException: Cannot create MAPDialog… is not activated` | this jSS7 fork does **not** activate services after `start()` | `MAPServiceMobility/Oam.activate()` after start (already done) |
| `NullPointerException … mapApplicationContext is null` in `addSendImsiRequest` | AC `version3` requested for `imsiRetrievalContext`, which **only has version 2** | use `MAPApplicationContextVersion.version2` |
| `/ts43/respond` answers `network did not confirm the claimed number` | SendIMSI is a **number-driven** source: it confirms a claim, it cannot find a number | send `msisdn` in the request; without a discovery source (`sh-udr`, `swx-sar` not implemented yet) failing closed is correct |
| Two MAP stacks in one JVM, only one leg works | both compete for the same SCTP local port; the losing stack has no association | one stack per binary; sas-ecs uses its own port 2907 |
| The HLR simulator receives nothing when started from the repo root | jSS7 writes `*.xml` state files into the CWD | run it from its own directory, or clean `*-sccp_*.xml` (git-ignored) |
| Log `[cdr-db] persist failed ... Unique index` | the same `x-correlator` reused in the CDR DB (`correlation_id` UNIQUE) | use a fresh `x-correlator` per call, or reset the lab DB |
| Ports 8085/8095 still taken after a script | a lab JVM is still running (e.g. started by hand for §0a) | `ss -ltnp | grep -E ':(8085|8095) '` and kill that PID; do not `pkill -f` with a pattern that also appears in your own shell's command line |
