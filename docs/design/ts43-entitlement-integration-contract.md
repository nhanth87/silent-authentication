# TS.43 entitlement integration contract — operator proposal (draft)

Status: **proposal draft** — to be agreed with Ethio Telecom / the hosting operator. This
document defines the interfaces and obligations between the **operator network**
(3GPP AAA + HSS) and **Restlink SAS** for the Wi-Fi / non-cellular silent-auth path
(GSMA **TS.43** Service Entitlement Configuration, EAP-AKA-based).

Companion wire-level spec: [`ts43-eapaka-wire-protocol.md`](ts43-eapaka-wire-protocol.md).

---

## 1. Parties and what each side owns

| Responsibility | Owner | Notes |
|----------------|-------|-------|
| SIM credential, EAP-AKA / EAP-AKA' termination | **Operator 3GPP AAA** | RFC 5448; never in SAS |
| Auth vectors + non-3GPP profile | **Operator HSS** | served over SWx (TS 29.273) |
| EAP carriage over Wi-Fi (SWm / RADIUS) | **Operator** | untrusted/trusted WLAN |
| Entitlement issuance + token mint | **Restlink SAS** | `/entitlement/issue` |
| Token consumption → CAMARA `/verify` | **Restlink SAS** | bank backend facing |
| Bank backend call to `/verify` | Bank | `devicePhoneNumberVerified` boolean |

Restlink is the **adapter layer above the operator**; it does not take SMS/interconnect
revenue and does not terminate subscriber authentication.

---

## 2. Reference specifications

- 3GPP TS 33.402 — EAP-AKA' for non-3GPP access.
- 3GPP TS 29.273 — SWm (AAA ↔ WLAN) and SWx (AAA ↔ HSS), Diameter apps 16777264 / 16777265.
- RFC 4187 (EAP-AKA) / RFC 5448 (EAP-AKA') / RFC 5779 (Diameter EAP application).
- GSMA TS.43 — Service Entitlement Configuration.
- CAMARA NumberVerification v2.1.0 — northbound `/verify` contract.
- GSMA FS.11 — SS7/SIGTRAN security (own-HSS-only category rules).

---

## 3. Integration points (one picture)

The SAS needs exactly **one** operator-side hook: after a successful EAP-AKA the
operator's 3GPP AAA (or an operator entitlement server function that the AAA hands off
to) calls the SAS `POST /entitlement/issue`. Everything downstream is Restlink.

```
Device ──EAP-AKA──► Operator 3GPP AAA ──SWx──► Operator HSS      (operator-owned)
                          │  on EAP success
                          ▼
                 SAS POST /entitlement/issue ──► {token}          (Restlink)
                          │
                 Bank backend POST /verify (Bearer operatortoken:{tk})
                          └──► { devicePhoneNumberVerified: bool }
```

---

## 4. Interface A — EAP-AKA termination (operator obligation)

The operator agrees to expose, or already runs, a **3GPP AAA** that:

- terminates **EAP-AKA'** (RFC 5448) for Wi-Fi / non-3GPP access;
- fetches auth vectors from the **own HSS** over **SWx** (`MAR`/`MAA`, `SAR`/`SAA`);
- is reachable by the SAS only for *consumption*, never for probing another operator
  (FS.11 Category 1 — no interconnect AAA);
- returns a **strict success/failure** outcome. Sync-failure, stale vectors, missing
  entitlement or timeout must surface as failure (fail-closed).

The SAS does **not** implement an EAP-AKA peer in production; the lab
`sas-diameter-testapp` and `EapAkaDemoPeer` exist only to emulate the operator AAA for a
POC. The operator may choose to deploy a **TS.43 Entitlement Server** in front of the
AAA (spec-recommended) or add the `/entitlement/issue` call directly to its AAA
integration — from the SAS's perspective the contract is identical.

---

## 5. Interface B — entitlement issuance (`POST /entitlement/issue`)

Called by the operator AAA integration **after** EAP-AKA succeeds.

```
POST /entitlement/issue
X-Api-Key: <machine key>                    # exchanged out-of-band
X-Sas-Attestation-Ts: <epoch-ms>            # required when attestation is enabled
X-Sas-Attestation-Mac: <hex-hmac>           # required when attestation is enabled
Content-Type: application/json

{ "msisdn": "+2519...", "imsi": "655010000000001", "eapMethod": "EAP-AKA" }
```

Response: `{ "token": "<tk>", "expiresInSeconds": 300 }`.

**Attestation**: the MAC proves the caller is the AAA that just authenticated the
subscriber (`msisdn|imsi|eapMethod|ts`, HMAC-SHA256 with a pre-shared secret, ±60 s,
single-use). Errors: `401 ATTESTATION_INVALID`, `401 ATTESTATION_EXPIRED`,
`401 ATTESTATION_REPLAY`, `503 ATTESTATION_MISCONFIGURED`.

**Token**: `base64url(payload) "." base64url(HMAC-SHA256(payload, secret))`, payload
`{msisdn, imsi, eapMethod, iat, exp, jti}`. TTL ≤ **300 s** (CAMARA single-use ceiling);
`eapMethod ∈ {EAP-AKA, EAP-AKA'} ` only.

---

## 6. Interface C — token consumption (bank backend)

The bank backend obtains the token through the CIBA back-channel and presents it to the
SAS. Two equivalent forms, both **single-use**:

1. `POST /entitlement/exchange` `{ "token": "<tk>" }` → `{msisdn, imsi, eapMethod, valid}`.
2. `POST /verify` (or `/number-verification/v2/verify`) with
   `Authorization: Bearer operatortoken:<tk>` → `{ "devicePhoneNumberVerified": true }`.

On the `/verify` Wi-Fi path the token binding is the claimed identity (`accessTech=WIFI`);
invalid / expired / replayed tokens answer `401 UNAUTHENTICATED`.

---

## 7. Security and compliance requirements

| Requirement | Contract |
|-------------|----------|
| Transport | Bank → SAS and AAA → SAS are **mTLS** in production (no plain HTTP). |
| Machine auth | API key (and/or mTLS client cert) on `/issue` and `/exchange`. |
| Attestation | Optional-but-strongly-recommended AAA HMAC on `/issue` (audit gap B1). |
| Single-use | Token and attestation are consume-once; a replay is rejected (`401`). |
| Lifetime | Token TTL ≤ 300 s, never refreshable. |
| Fail-closed | Missing/stale/swapped evidence never approves; timeout ⇒ FALLBACK. |
| Privacy | IMSI/MSISDN only server-side; the device/app sees a boolean. |
| Own-HSS only | SWx/S6a/SWm target the operator HSS/AAA; **no interconnect ATI/AAA** (FS.11). SAS's production verify leg is operator REST (CAMARA NV / SIM Swap) + read-only Sh UDR/SNR. |
| SIM-swap | Claimed-IMSI mismatch ⇒ `SIM_SWAP_SUSPECT` (lab: SWx verify; production: CAMARA SIM Swap / Sh UDR age). |

Compliance anchors: GSMA FS.11 (own-network interrogation only), CAMARA NumberVerification
(user-number binding + single-use bearer), GSMA TS.43 (entitlement token semantics).

---

## 8. Timeouts (dialog-anchor, fail-closed)

| Stage | Budget | On expiry |
|-------|--------|-----------|
| EAP-AKA session | operator AAA policy | EAP failure |
| SWx MAR/MAA | 2 s | abort, FALLBACK |
| `/entitlement/issue` attestation | ±60 s | `ATTESTATION_EXPIRED` |
| token TTL | ≤ 300 s | `401 INVALID_TOKEN` |
| `/verify` Wi-Fi verify leg (lab SWx / prod CAMARA NV + Sh) | 2 s | `WIFI_NOT_READY` / `VERIFY_TIMEOUT` |

---

## 9. Feasibility and open questions

To be resolved with the operator before a pilot:

1. **AAA placement** — does Ethio Telecom run a 3GPP AAA today, or is this a new build?
2. **Entitlement server** — deploy a spec-aligned TS.43 Entitlement Server, or wire the
   `/entitlement/issue` call into the existing AAA/HLR integration?
3. **SWm carriage** — RADIUS vs Diameter EAP application at the WLAN edge; who owns the
   access-network binding (RFC 5448) for untrusted Wi-Fi?
4. **Attestation secret** — pre-shared HMAC key vs mTLS-only; key rotation procedure.
5. **Bearer-declaration evidence** — how does the operator prevent a device from *claiming*
   an EAP success that never happened (the `accessTech` honesty problem, P-H8)?
6. **SIM-swap freshness** — source of the last-IMSI-change timestamp for the Wi-Fi path.

None of these block the lab POC; they gate production UAT.

---

## 10. Rollout checklist (proposal)

1. Operator agrees to expose (or build) the 3GPP AAA + own-HSS SWx path.
2. Restlink delivers the `/entitlement/issue` + `/verify` (operatortoken) surface.
3. Joint UAT: `EapAkaDemoPeer` + `sas-diameter-testapp` prove the loop, then swap in the
   real AAA with a test SIM.
4. Agree attestation secret + mTLS + key rotation.
5. Production gate: `harness/preflight_prod.py` clean (HTTPS-only, mTLS, real transports,
   PostgreSQL); H22/H23/H24 gates pass.
6. Pilot with one Ethiopian bank, fallback SMS OTP as the safety net while TS.43 coverage grows.