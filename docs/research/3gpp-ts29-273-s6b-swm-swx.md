# 3GPP TS 29.273 — Diameter S6b / SWm / SWx (EPS + non-3GPP AAA)

Reference notes for the **Diameter** interfaces between the PGW/3GPP-AAA and the HSS. This spec
is the transport detail behind both the **Resolver data-plane anchor** (S6b) and the **Wi-Fi
silent-auth path** (SWm/SWx, see `3gpp-ts33-402-eap-aka.md`).

- **Spec:** 3GPP TS 29.273 (Evolved Packet System; 3GPP EPS AAA interfaces)
- **Applications:** S6b (`16777272`), SWm (`16777264`), SWx (`16777265`), STa/SWa.

---

## 1. Interface table

| Interface | Peers | Purpose | Silent-auth relevance |
|-----------|-------|---------|------------------------|
| **S6b** | PGW ↔ 3GPP AAA | SGi-LAN session / bearer auth + accounting | Resolver data-plane anchor (PGW binding) |
| **SWx** | 3GPP AAA ↔ HSS | EAP-AKA auth vectors + subscription | Wi-Fi (TS.43) Verifier |
| **SWm** | WLAN AN ↔ 3GPP AAA | EAP-AKA transaction carriage | Wi-Fi (TS.43) transport |
| STa | Trusted non-3GPP ↔ AAA | Trusted WLAN auth | (adjacent) |
| SWa | Untrusted ↔ AAA | Untrusted WLAN auth | (adjacent) |

---

## 2. Key point for the resolver boundary

The architecture invariant "**MAP/Diameter cannot map IP → MSISDN**" is satisfied here: the
IP-binding belongs to the **S6b/SGi data plane** (PGW accounting), while the *identity* check
belongs to MAP/S6a/SWx. S6b is the Resolver-side evidence, not a Verifier probe.

```
IP:port:ts ──Resolver(PGW S6b/SGi accounting)──► MSISDN/IMSI
            ──Verifier(MAP PSI/ATI/SAI or S6a ULR/AIR or SWx EAP-AKA)──► assurance
```

---

## 3. Security mapping

- **Own HSS only** — SWx/S6b query the operator AAA/HSS, never interconnect.
- **Fail-closed** — missing S6b binding / missing SWx AV ⇒ FALLBACK.
- **Point-in-time** — treat any S6b session/accounting record as a snapshot at `ts`
  (CGNAT ⇒ IP+port+ts still required).
- **Dialog hygiene** — bounded Diameter transaction per stage; timeout ⇒ abort.

---

## 4. Source artefact

`https://www.3gpp.org/ftp/Specs/archive/29_series/29.273/` (DOCX; not committed). Re-fetch for
normative ABNF before wiring a jDiameter S6b/SWx client.