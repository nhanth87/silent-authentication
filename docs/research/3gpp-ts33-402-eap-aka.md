# 3GPP TS 33.402 — EAP-AKA / Wi-Fi silent auth (SWm/SWx, 3GPP AAA)

Reference notes for the **Wi-Fi / non-cellular** leg of silent authentication. This is the
3GPP signalling anchor for **GSMA TS.43** (Service Entitlement Configuration) SIM-based
silent auth — the path that works on **Wi-Fi and browsers with no mobile data**.

- **Spec:** 3GPP TS 33.402 (Security aspects of non-3GPP accesses)
- **Peers:** WLAN AN ← **SWm** → 3GPP AAA Server ← **SWx** → HSS (+ **STa**/SWa at the access edge)
- **Root of trust:** the SIM credential via **EAP-AKA**/**EAP-AKA'** — not the PGW IP binding.

---

## 1. Why this matters for silent auth

The IP-match method (Resolver + MAP/Diameter Verifier) **fails on Wi-Fi-only** devices — there
is no PGW IP↔MSISDN binding. TS.43/EAP-AKA closes that gap: the device authenticates with the
SIM (`EAP-AKA`) against the operator AAA, which proves possession of the claimed MSISDN without
cellular data. This is the "SIM-Based Authentication" referenced by CAMARA NumberVerification.

| Path | Root of trust | Needs cellular data? | 3GPP anchor |
|------|---------------|----------------------|-------------|
| IP-match | PGW IP↔MSISDN + MAP/Diameter | **Yes** | TS 29.002 / 29.272 |
| **TS.43 EAP-AKA** | SIM credential | **No** | **TS 33.402** SWm/SWx |
| S6a/S6d | HSS state + EPS vectors | Yes (LTE attach) | TS 29.272 |

---

## 2. Reference points (SWm / SWx / SWa / STa)

| Interface | Peers | Protocol | Role in silent auth |
|-----------|-------|----------|---------------------|
| **SWm** | WLAN AN ↔ 3GPP AAA | Diameter (`s6b` app) | Carries EAP-AKA authentication exchange |
| **SWx** | 3GPP AAA ↔ HSS | Diameter | Fetch auth vectors (AV) + subscription profile |
| **SWa** | Untrusted WLAN ↔ 3GPP AAA | Diameter | Tunnelled auth (untrusted non-3GPP) |
| **STa** | Trusted non-3GPP ↔ 3GPP AAA | Diameter | Trusted access auth |

The **3GPP AAA Server** terminates EAP-AKA and queries the HSS over SWx — the same
"own HSS only" trust rule applies (no cross-operator probing).

---

## 3. EAP-AKA flow (silent-auth slice)

```
Device (SIM) ──EAP-AKA──► WLAN AN ──SWm(Diameter)──► 3GPP AAA ──SWx──► HSS
                                                    (AV: RAND,AUTN,RES,XRES)
```
The AAA derives the master session key from the SIM's `K`; a successful run proves the device
holds the SIM bound to the claimed IMSI/MSISDN. The entitlement server (GSMA TS.43) then issues
a short-lived **temporary token** that the backend exchanges (CIBA / JWT-Bearer) — see
`camara-number-verification.md`.

---

## 4. Security mapping (same fail-closed rules)

- **Own HSS only** — SWx probes the operator HSS; no interconnect AAA.
- **Fail-closed** — EAP-AKA sync-failure / stale AV / missing entitlement ⇒ FALLBACK.
- **Single-use** — the TS.43 temporary token is one-time, ≤ 300 s, no refresh (CAMARA rule).
- **Privacy** — IMSI/MSISDN stay server-side; the device/app sees a boolean outcome.

---

## 5. Source artefact

Normative text: `https://www.3gpp.org/ftp/Specs/archive/33_series/33.402/` (DOCX; not committed).
Re-fetch before wiring the entitlement-server / AAA path (open item in the architecture doc).