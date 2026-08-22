# 3GPP TS 23.018 / 23.060 — MAP procedures & TCAP dialog/timer lifecycle

Reference notes for the **procedure layer** that governs *when* the MAP operations in
`3gpp-ts29-002-map.md` are invoked and how their TCAP dialogs are bounded. TS 29.002 gives the
ASN.1/operations; these specs give the dialog state machines and timers the SAS Verifier must
honour to avoid dialog leaks.

- **TS 23.018** — Basic call handling; location management (HLR/VLR) procedures.
- **TS 23.060** — GPRS; mobility management (SGSN/HLR) procedures, attach/detach, PDP context.

---

## 1. The one thing silent auth depends on

The SAS Verifier opens **one bounded TCAP dialog per stage** (PSI/ATI/SAI) and must **abort on
timeout**. The authoritative timer is the **TC-TIMER** (transaction component timer) and the
MAP dialogue is wrapped in a TCAP structured dialogue (`TC-BEGIN/TC-CONTINUE/TC-END/TC-ABORT`).

| Concept | Spec | SAS usage |
|---------|------|-----------|
| TCAP structured dialogue | Q.771 / TS 29.002 | one dialogue per PSI/ATI/SAI probe |
| **TC-TIMER** | Q.774 (referenced by 23.018/23.060) | bound the probe; expiry ⇒ `TC-ABORT`, FALLBACK |
| location update procedure | TS 23.018 / 23.060 | `cancelLocation`/`updateLocation` = takeover signal |
| GMM attach/detach | TS 23.060 | `updateGprsLocation`, `purgeMS` = liveness |

---

## 2. Dialog hygiene rules (must not regress)

- **One dialog per stage** — never multiplex a second probe onto the same TCAP dialogue.
- **Bounded lifetime** — MAP budget 2 s (design table); on expiry issue `abort()` and FALLBACK.
- **No dialog leak** — every `TC-BEGIN` must end in `TC-END` or `TC-ABORT`; track per-`reqId`.
- **Point-in-time** — state returned is a snapshot at `ts`; never treat "latest" as binding.

---

## 3. Source artefact

`https://www.3gpp.org/ftp/Specs/archive/23_series/23.018/` and `.../23.060/` (DOCX; not
committed).