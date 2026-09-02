# sas-diameter-testapp — HSS / 3GPP AAA simulator for the SAS lab

Standalone operator-side **HSS + 3GPP AAA + PCRF (Gx)** Diameter simulator
(corsac-diameter, the same stack the SAS client uses) so the full silent-auth
loop can be tested locally:

```
browser/web ──POST /verify──► SAS (Quarkus, :8085) ──S6a / SWx / Gx──► this test app (:3868)
```

R&D lab tooling only — never production.

## What it serves

| App | Command | Code | Answer | Spec |
|-----|---------|------|--------|------|
| S6a  | Update-Location-Request    | 316 | ULA: success + Subscription-Data (`Subscriber-Status`), or error result-code | TS 29.272 §5.2.2.2 |
| S6a  | Authentication-Information | 318 | AIA: fabricated E-UTRAN vectors — **legacy, SAS no longer sends AIR** (read-only Sh UDR is the freshness path) | TS 29.272 §5.3.2 |
| S6a  | Insert-Subscriber-Data     | 319 | IDA ack — **legacy, HSS-initiated push, not on the verify path** | TS 29.272 §5.2.2.4 |
| SWx  | Multimedia-Auth            | —   | MAA: EAP-AKA SIP-Auth-Data-Item(s) honouring the same vector-count state; stamps `lastEapAuthSuccess` | TS 29.273 §6.2.2 |
| SWx  | Server-Assignment          | —   | SAA ack + Non-3GPP-User-Data + 3GPP-AAAServerName | TS 29.273 §6.3.2 |
| SWx  | Push-Profile               | —   | PPA ack | TS 29.273 §6.6.2 |
| Gx   | Credit-Control-Request (I) | 272 | CCA: `2001` + Subscription-Id (MSISDN, IMSI when known) for the CCR's Framed-IP-Address binding, or `5030` when no binding is provisioned | TS 29.212 §5.3.1/§5.6.2 (+ lab deviation: answer-side Subscription-Id as unknown AVP 443, mandatory bit clear) |

Result-code policy (fail-closed on the SAS side by design). corsac marks
Experimental-Result disallowed on these answers, so error values ride the base
Result-Code:

| Scenario | Result-Code |
|----------|-------------|
| known + attached + not barred | `2001` DIAMETER_SUCCESS |
| unknown user                  | `5001` DIAMETER_ERROR_USER_UNKNOWN (TS 29.272 §7.2.6) |
| detached UE                   | `5421` DIAMETER_ERROR_UNKNOWN_EPS_SUBSCRIPTION / USER_NO_NON_3GPP_SUBSCRIPTION |
| barred                        | `2001` with `Subscriber-Status = OPERATOR_DETERMINED_BARRING` → SAS fails closed |
| `authVectorsAvailable = 0`    | `2001` but empty EAP-AKA vector set → SAS fails closed (MAR-empty; S6a freshness is Sh UDR, not AIR) |
| Gx framed IP without binding  | `5030` DIAMETER_USER_UNKNOWN (RFC 4006 §8.4, referenced by TS 29.212) |
| handler exception             | `3002` DIAMETER_UNABLE_TO_DELIVER (fail-safe, never crashes) |

Identity: requests are matched by the Username AVP as IMSI or MSISDN; Gx
binding lookups are keyed by Framed-IP-Address against the IP-binding registry.

## Gx IP bindings

The simulated PCRF side answers CCR binding lookups from a small IP →
{msisdn, imsi} registry. Seeded default: `10.20.30.40` → `+251911111111` /
`655010000000001` (matches the SAS demo resolver).

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/binding`        | GET    | list bindings `{"bindings":[{ip,msisdn,imsi}]}` |
| `/api/binding`        | POST   | upsert `{"ip":"10.20.30.40","msisdn":"+251911111111","imsi":"655010000000001"}`; remove with `{"ip":"…","clear":true}` |
| `/api/binding/{ip}`   | DELETE | remove one binding |

## Build and run

```bash
# mise's mvn shim is pinned to zulu-8 — set JAVA_HOME explicitly
cd sas-diameter-testapp
JAVA_HOME=~/.local/share/mise/installs/java/zulu-25 mvn clean package
java -jar target/sas-diameter-testapp.jar                       # defaults below
java -jar target/sas-diameter-testapp.jar --diameter-port 13868 --web-port 18086
```

Flags: `--diameter-port N` (default 3868), `--web-port N` (default 8086),
`--bind ADDR` (default 127.0.0.1), `--tcp` (use TCP instead of SCTP — the SAS
client is SCTP-only, so keep SCTP for the real loop).

Port layout: Diameter SCTP listen = 3868, control web UI = 8086, SAS Quarkus
HTTP = 8085 (see `sas/src/main/resources/application.properties`).

## Control web UI

Open `http://127.0.0.1:8086/` — live Diameter message table (last 500, polled
every 2 s), subscriber state panel and Gx IP-binding panel.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/messages`   | GET  | ring buffer JSON array (time, direction, command, session, result, details) |
| `/api/subscriber` | GET  | subscriber states |
| `/api/subscriber` | POST | update fields: `{"identity":"655010000000001","attached":true,"barred":false,"authVectorsAvailable":1,"subscribedRat":"EUTRAN"}` |
| `/api/reset`      | POST | clear buffer + restore defaults |
| `/api/health`     | GET  | `{"status":"up","diameterListening":true}` |

Defaults: one demo subscriber IMSI `655010000000001` / MSISDN `+251911111111`,
attached, not barred, 1 auth vector.

## Point the SAS at it

In `sas/src/main/resources/application.properties` (or env equivalents):

```properties
# LTE path (X-Sas-Access-Tech: LTE)
sas.transport.s6a=corsac
# Wi-Fi path (X-Sas-Access-Tech: WIFI)
sas.transport.swx=corsac
sas.transport.diameter.peer-host=127.0.0.1
sas.transport.diameter.peer-port=3868
```

Enable **one** of `s6a`/`swx` at a time for a clean loop: each enabled backend
opens its own client link to the same peer port, and the simulator serves one
inbound association per listen port (scenario isolation matches the access-tech
you exercise anyway).

> Known upstream caveat (sas/, not touched here): the corsac backends in
> `CorsacS6aVerifierBackend` / `CorsacSwxVerifierBackend` pass their bind and
> connect addresses swapped in `NetworkManager.addLink(...)` relative to
> corsac's own client wiring (bind must be the local slot, the server address
> the remote slot) — until corrected, the SAS side will not dial out.

## End-to-end example loop

```bash
# 1. start the simulator (test ports)
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar \
     --diameter-port 13868 --web-port 18086 &

# 2. start the SAS pointed at it
cd sas && JAVA_HOME=~/.local/share/mise/installs/java/zulu-25 mvn quarkus:dev \
     -Dquarkus.http.port=8085 \
     -Dsas.transport.s6a=corsac \
     -Dsas.transport.diameter.peer-port=13868 &

# 3. bank-app call (defaults resolve 10.20.30.40:55555 -> demo subscriber)
curl -s -X POST http://localhost:8085/verify \
     -H 'Authorization: Bearer lab' -H 'X-Sas-Amr: mobile' \
     -H 'Content-Type: application/json' -H 'X-Sas-Access-Tech: LTE' \
     -d '{"phoneNumber":"+251911111111"}'
# -> {"devicePhoneNumberVerified":true}

# 4. failure scenarios via the simulator UI/API, then re-run step 3:
curl -s -X POST http://127.0.0.1:18086/api/subscriber \
     -d '{"identity":"655010000000001","authVectorsAvailable":0}'
#   zero vectors      -> SAS fails closed -> {"devicePhoneNumberVerified":false}
curl -s -X POST http://127.0.0.1:18086/api/subscriber \
     -d '{"identity":"655010000000001","attached":false}'
#   detached (5421)   -> false
curl -s -X POST http://127.0.0.1:18086/api/subscriber \
     -d '{"identity":"999999999999999"}'   # not provisioned -> use another identity
#   unknown user (5001): verify with a claimed number that resolves to no HSS entry -> false
curl -s -X POST http://127.0.0.1:18086/api/reset        # back to defaults
```

Watch the exchanges live at `http://127.0.0.1:18086/`.

## Layout

```
src/main/java/et/restlink/testapp/
├── Main.java                 # args + wiring + shutdown hook
├── SubscriberState.java      # per-subscriber mutable lab state
├── HssSimulator.java         # registry keyed by IMSI/MSISDN + defaults/reset
├── BindingRegistry.java      # Gx IP → {msisdn,imsi} bindings (seeded default)
├── MessageLog.java           # last-500 ring buffer (records)
├── diameter/
│   ├── HssDiameterServer.java# corsac stack, listening link, provider wiring
│   ├── Answers.java          # result codes, random material, logging helpers
│   ├── S6aHandler.java       # ULR server listener (TS 29.272)
│   ├── SwxHandler.java       # MAR/SAR/PPR server listener (TS 29.273)
│   └── GxHandler.java        # CCR binding lookups → CCA + Subscription-Id
├── web/
│   ├── ControlWebServer.java # JDK HttpServer endpoints (+ /api/binding)
│   ├── Pages.java            # single-page UI (vanilla JS, ET-flag accents)
│   └── Json.java             # minimal JSON escape/parse (no dependency)
└── eap/
    └── EapAkaDemoPeer.java   # EAP-AKA peer: simulated EAP -> /entitlement/issue -> /verify
```

## EAP-AKA demo peer (POC leg)

`EapAkaDemoPeer` emulates the operator 3GPP AAA on the entitlement leg so the whole
EAP-AKA → SWx → token → verify loop can be driven end-to-end in the lab. It does **not**
speak Diameter — the SWx leg is exercised by the SAS's own corsac SWx client against this
simulator. The peer covers the *shape* of EAP-AKA plus the SAS entitlement + redeem calls:

1. prints a simulated EAP-AKA success (fabricated RAND/AUTN/RES — no SIM crypto);
2. `POST /entitlement/issue` on the SAS (optionally with the AAA attestation HMAC);
3. `POST /verify` with `Authorization: Bearer operatortoken:<tk>` to prove single-use.

Run it against a live SAS (build the jar first, then):

```bash
java -cp target/sas-diameter-testapp.jar et.restlink.testapp.eap.EapAkaDemoPeer \
     --sas-url http://127.0.0.1:8085 --api-key lab \
     --msisdn +251911111111 --imsi 655010000000001 \
     --eap-method EAP-AKA [--attestation-secret shared-secret] [--no-redeem]
```

The redeem step returns `devicePhoneNumberVerified:true` only when the SAS Wi-Fi SWx
verifier passes — i.e. it has a seeded Wi-Fi subscriber (`InMemorySwxVerifierBackend`), or
`--sas.transport.swx=corsac` is pointed at this simulator's SWx service. Wire protocol:
`docs/design/ts43-eapaka-wire-protocol.md`; operator contract:
`docs/design/ts43-entitlement-integration-contract.md`.
## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, signed builds + license key, security advisories, L1/L2 SLA, training and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
