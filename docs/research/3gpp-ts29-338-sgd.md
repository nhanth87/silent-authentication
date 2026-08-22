# 3GPP TS 29.338 — Diameter SGd (SMS over LTE/5G)

Reference notes for the **SGd** interface — the Diameter path for **SMS transfer** between the
MME/SGSN and the SMS service nodes (SMS-IWMSC / SMS-GMSC). This is the route the **fallback SMS
OTP** rides on LTE/EPC (and, via the SMF/SMSF, on 5G).

- **Spec:** 3GPP TS 29.338 (Diameter-based protocols to support SMS capability on MME/SGSN)

---

## 1. Why it's in the silent-auth stack

Silent auth's happy path sends **no SMS**. But the design is **fail-closed → FALLBACK**, and one
fallback is the firewalled SMS OTP. Knowing exactly which signalling route that OTP takes is what
lets Strategy B (SMS Home Routing + SS7/Diameter/5G firewall, see `sms-channel-protection.md`)
protect it. SGd is the LTE/5G counterpart of MAP `sendRoutingInfoForSM` / `mt-ForwardSM`.

| OTP route | Access | Signalling |
|-----------|--------|------------|
| 2G/3G | SS7/MAP | SRI-SM + MT-FSM (TS 29.002) |
| **LTE/EPC** | **SGd** | MO/MT-Forward-Short-Message (TS 29.338) |
| 5G | SMSF | Nsms / Namf (TS 23.502 + TS 29.540) |

---

## 2. Command reference

| Command | Purpose |
|---------|---------|
| `MO-Forward-Short-Message-Request/Answer` (MO-FSM) | UE → SMSC via MME/SGSN |
| `MT-Forward-Short-Message-Request/Answer` (MT-FSM) | SMSC → UE via MME/SGSN |

The SMS payload rides Diameter AVP containers; the SGd peer is the **SMS-IWMSC/GMSC** (legacy
SMS core) — an interworking point that a Diameter Edge Agent (DEA, FS.19) must police.

---

## 3. Security mapping (fallback path)

- **Home Routing** — keep SGd SMS termination on the home network (no foreign-node MT).
- **DEA filtering** — FS.19: SGd must be behind the Diameter Edge Agent; block
  cross-operator MT-FSM / SMS spoof (mirror FS.11 SMS categories).
- **Fail-closed unaffected** — OTP fallback only fires when silent auth cannot; it never
  *raises* assurance.
- **Privacy** — no change: OTP fallback is the bank's channel, SAS still returns a boolean.

---

## 4. Source artefact

`https://www.3gpp.org/ftp/Specs/archive/29_series/29.338/` (DOCX; not committed).