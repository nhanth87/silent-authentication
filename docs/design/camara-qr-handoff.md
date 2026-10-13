# CAMARA NV v2 — Desktop → phone QR hand-off

> Restlink extension to the CAMARA Number Verification surface, implemented on
> `nv-2`. The endpoints live under `{apiRoot}/qr-session/v1`.
>
> This is **not** a CAMARA-published API. It exists because a customer signing in
> on a desktop cannot be authenticated by the cellular IP-match path at all.

---

## 1. The problem

The whole product rests on one premise: the address the SAS sees was assigned by
the operator's PGW/GGSN, so it can be attested to an MSISDN.

A desktop breaks that premise. It has no SIM. Its browser sends a LAN or VPN
address that no PGW ever handed out, and the only honest answer is
`false`/`FALLBACK` — forever, for every customer, on every desktop login.

The way out is to move the proof to the device that *does* have the SIM. The bank
renders a QR code, the customer scans it with their phone, and the phone becomes
the device that proves the number.

```
[Desktop browser]              [sas-camara]                    [Phone]
  user logs in  ─────────────►  POST /qr-session/v1
                                 → {sessionId, qrPayload}
  renders QR  ◄────────────────   (payload carries only sessionId)
                                 ◄──────────── POST /qr-session/{id}/claim
                                                      (cellular bearer, or TS.43 token)
                                 runs the ONE verify FSM ──► sas-ecs ──► HLR/HSS
                                 verdict stored
  polls status  ─────────────►  GET /qr-session/{id}
  ◄────────────── {RESOLVED, devicePhoneNumberVerified, …}
  login succeeds only on an explicit APPROVED
```

---

## 2. Operations

### `POST /qr-session/v1`

Opens the hand-off. Authenticated exactly like `/verify` — a single-use NV
bearer, or a GSMA TS.43 `operatortoken:<tk>` (checked first, because that form
is not a JWT and would otherwise read as a malformed bearer).

| Field | Required | Notes |
|---|---|---|
| `phoneNumber` | no | E.164 the bank wants proven. Omit for the **discovery** variant, where the phone states which number it wants proven. |
| header `Authorization` | yes | single-use NV bearer, or `Bearer operatortoken:<tk>` |
| header `x-correlator` | no | `^[a-zA-Z0-9-_:;./<>{}]{0,256}$`, echoed on every response |
| header `X-Api-Key` | yes (tenant) | quota gate |

```json
{
  "sessionId": "56f79f10-0bee-4c62-8821-798bb603ac46",
  "qrPayload": "silentauth://qr-session/56f79f10-0bee-4c62-8821-798bb603ac46",
  "expiresInSeconds": 120
}
```

The payload carries **only the opaque id**: the phone cannot read the bank
session, the claimed number, or any billing context out of the QR image.

### `POST /qr-session/v1/{id}/claim`

The phone claims the session. This runs the **same** `VerifyRequestEvent` the
IP-match path runs, so the verdict comes from the one FSM — a QR code adds a
delivery mechanism, never a second way to be approved.

| Field | Required | Notes |
|---|---|---|
| `accessTech` | no | the phone's own bearer declaration: `GS_2G3G` \| `LTE` \| `NR` \| `WIFI` \| `FIXED` |
| `operatorToken` | no | GSMA TS.43 temporary token — the Wi-Fi variant |
| `msisdn` | no | claimed number, for the discovery variant; must be E.164, and may not differ from the number the bank asked about |
| header `X-Sas-Src-Ip` / `-Port` | no | the bearer tuple; production mints these at the edge, lab falls back to `10.20.30.40:55555` |

```json
{
  "sessionId": "56f79f10-…",
  "state": "RESOLVED",
  "devicePhoneNumberVerified": true,
  "fallbackReason": null
}
```

### `GET /qr-session/v1/{id}`

What the bank polls. **Requires the tenant API key of the tenant that opened the
session** (`X-Api-Key`), because a session id is not a secret: it is rendered on
the customer's screen and travels in a URL, and URLs end up in proxy logs,
browser history and referrers. An unauthenticated read would turn any of those
into a way to learn whether a given bank login succeeded.

The NV bearer cannot serve here — it is single-use and already spent at create.
The tenant key is the one credential both hops carry. Under
`sas.security.enforce-api-keys=false` (lab) the check is a no-op, exactly as at
`/session-tuple`.

```json
{
  "sessionId": "56f79f10-…",
  "state": "RESOLVED",
  "devicePhoneNumberVerified": false,
  "fallbackReason": "SIM_SWAP_SUSPECT",
  "decision": "FALLBACK",
  "expiresInSeconds": 119
}
```

`state` is `PENDING` | `RESOLVED` | `EXPIRED`. **Anything other than `RESOLVED`
is "not authenticated"** — there is no default-approve reading of this object.

---

## 3. Which claim path applies

| Phone on | Sends | Server does |
|---|---|---|
| cellular data | `{"accessTech":"LTE"}` | IP-match: resolver → MAP PSI/SAI / S6a, same as `/verify` |
| cellular data, discovery | `{"msisdn":"+251…","accessTech":"NR"}` | as above, number supplied by the phone |
| Wi-Fi | `{"accessTech":"WIFI","operatorToken":"<tk>"}` | operator token redeemed at sas-ecs (EAP-AKA), then SWx/evidence |
| Wi-Fi, no token | `{"accessTech":"WIFI"}` | **`400 ACCESS_TECH_NOT_CELLULAR`** — a Wi-Fi address cannot be attested to an MSISDN |

---

## 4. Fail-closed properties

Measured against the packaged dists with `scripts/qr-request-matrix.py` (53
request shapes) and `scripts/qr-tenant-probe.py` (10 checks in the enforced
prod shape). Both exit non-zero on an unexpected status.

| Situation | Answer |
|---|---|
| Session unknown, or past its 120 s budget | `404 NOT_FOUND` |
| Second handset on the same code, or five at once | `409 ALREADY_CLAIMED` (exactly one wins) |
| Phone declares Wi-Fi/fixed and offers no operator token | `400 ACCESS_TECH_NOT_CELLULAR` |
| Phone declares a bearer name outside the contract | `400 INVALID_ARGUMENT` — never guessed |
| Phone's `msisdn` is not E.164 | `400 INVALID_ARGUMENT` — refused, not ignored |
| Phone asks about a number other than the bank's | `422 MISMATCHING_IDENTIFIER` |
| Bank states a number that its operator token contradicts | `422 UNNECESSARY_IDENTIFIER` |
| Operator token invalid, expired or already used | `401 UNAUTHENTICATED` |
| NV bearer or operator token replayed | `401 UNAUTHENTICATED` (single use) |
| NV bearer without `number-verification:verify` | `403 PERMISSION_DENIED` |
| Discovery session and the phone supplies no number | `422 MISSING_IDENTIFIER` |
| Verifier FSM times out (5 s) or throws | `false` + `TIMEOUT` / `NETWORK_ERROR` |
| Poll with no credentials (enforced) | `401 UNAUTHENTICATED` |
| Poll with another tenant's key | `403 PERMISSION_DENIED` |
| Poll by the owning tenant | `200` |
| Bank polls before the phone claims | `200 PENDING`, `devicePhoneNumberVerified: null` |
| Bank polls after expiry | `404` — an expired session is never approvable |
| Unknown request property | `400 INVALID_ARGUMENT`, naming the property |
| Body that cannot be bound at all (JSON array, truncated, wrong scalar type) | `400`, **with no body** — see below |
| Wrong method on any path | `405`, no body |

### Known framework boundary: an unbindable body has no error body

A request body that Jackson cannot bind — an array where an object belongs, a
document truncated mid-value, a wrong scalar type — answers `400` with an empty
body. Quarkus REST short-circuits these before the JAX-RS exception-mapper chain
runs; an `ExceptionMapper` cannot intercept them, and no `quarkus.*` switch
exists in 3.37.3 to change it. **This is not specific to the hand-off:
`POST /verify` behaves identically**, so the two surfaces stay consistent.

An *unknown property* is the exception and does carry a body
(`CamaraUnrecognizedPropertyExceptionMapper`), because `fail-on-unknown-properties`
surfaces as a distinct failure the chain does see. That is the malformed request a
real bank client is most likely to send.

Every error the SAS itself raises carries `status`, `code` and `message`.

### Two design points that are easy to get wrong

- **The claim gate never resets.** `beginClaim` is a compare-and-set per session
  id, so a replayed `claim` after a completed hand-off is refused rather than
  answered twice, and five handsets racing one code yield exactly one verdict.
- **The operator token is checked before the bearer gate.** The TS.43 path
  legitimately declares `WIFI`; gating on the bearer first would make the Wi-Fi
  variant permanently unreachable.

## 5. Layer split (gate H25)

The hand-off lives in the CAMARA gateway and **signals no core network**. It
submits a `VerifyRequestEvent` and reads the verdict back; every subscriber fact
still arrives through `SubscriberService` → sas-ecs. A QR code does not widen
what the gateway can see.

The session store holds only what the *bank* supplied (the number it wants
proven) plus the verdict the FSM returned. It is not identity state, it does not
outlive the request, and it starts no thread — expiry is lazy, like
`EntitlementSessions` (H24).

---

## 6. Device SDKs

| Module | Class | Role |
|---|---|---|
| `ue-sdk` | `QrSessionClient` | core, JVM: `parsePayload`, `claim`, `status`, over the `Connector` seam |
| `ue-sdk-android` | `QrSessionClient` | claim through `CellularBearer`, i.e. `Network.openConnection(URL)` |
| `ue-sdk-ios` | `QrSessionClient` | observes the path via `NWPathMonitor`, fails closed on Wi-Fi |
| `ue-sdk-web` | `QrSessionClient` | desktop half only: `create`, `status`, `poll` — a browser cannot claim |

The QR payload is validated to be a UUID before it authorises anything, on every
platform. `parsePayload` refuses `silentauth://qr-session/../../etc/passwd` and
a right-length string with the wrong separators.

---

## 7. What the request matrix found

The first matrix authenticated every `create` with an operator token, which
silently made every session a *specific-number* session — so the bearer path
that production actually uses was never exercised. Fixing that exposed five
real defects, all fixed with tests:

| # | Defect | Answer now |
|---|---|---|
| 1 | `GET /{id}` needed no credential, and any tenant's key read any session | tenant key required; another tenant gets `403` |
| 2 | A phone could claim a *different* number than the bank asked about; the bank's number was verified and that verdict handed to the phone | `422 MISMATCHING_IDENTIFIER` |
| 3 | A claim presenting a valid operator token was read as a JWT and refused | same precedence as `create` |
| 4 | A phone's `msisdn` that could not be normalised was silently ignored on a specific-number session | `400 INVALID_ARGUMENT` |
| 5 | `/camara/qr-session/v1` did not exist, so an `apiRoot`-configured client got an HTML 404 | pure alias resource added |

## 8. Verified on the packaged dists

SS7/MAP backend, HLR simulator on SCTP :2906:

```
1) POST /qr-session/v1                 → {"sessionId":"56f79f10-…","qrPayload":"silentauth://qr-session/56f79f10-…","expiresInSeconds":120}
2) GET  /qr-session/v1/{id}            → {"state":"PENDING","devicePhoneNumberVerified":null,…}
3) POST /qr-session/v1/{id}/claim      → {"state":"RESOLVED","devicePhoneNumberVerified":false,"fallbackReason":"SIM_SWAP_SUSPECT"}
4) GET  /qr-session/v1/{id}            → {"state":"RESOLVED","decision":"FALLBACK",…}
```

with the claim driving real MAP traffic on the wire:

```
sendImsi               | ReturnResultLast | msisdn=251911111111 imsi=655****01
provideSubscriberInfo  | ReturnResultLast | attached idle+LAI
sendAuthenticationInfo | ReturnResultLast | 1 quintuplet(s) imsi=655****01
```

TS.43 variant on the same dists (ECS in lab-evidence mode, testflow §00.5):

```
POST /qr-session/v1/{id}/claim  {"accessTech":"WIFI","operatorToken":"<tk>"}
  → {"state":"RESOLVED","devicePhoneNumberVerified":true,"fallbackReason":null}
GET  /qr-session/v1/{id}
  → {"state":"RESOLVED","devicePhoneNumberVerified":true,"decision":"APPROVED",…}
```

Reusing that same token on a second session: `401 UNAUTHENTICATED` — single use.

On the **live** transport the result is `false`/`SIM_SWAP_SUSPECT`, because
`EcsBootstrap.labSimChange()` deliberately refuses to borrow the lab's invented
binding age when a real transport is configured. That is the intended
behaviour, not a defect: the QR hand-off cannot invent evidence the core did not
provide.
