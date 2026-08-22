# 3GPP TS 29.002 — Mobile Application Part (MAP) for Silent Auth

Reference notes for the **SS7 / MAP** side of the silent-authentication stack — the
**Verifier** stage that turns an `IP:port:ts`-resolved MSISDN/IMSI into an assurance signal
(live, not SIM-swapped, location plausible). Extracted 2026-08-19 from the normative spec.

- **Spec:** 3GPP TS 29.002 V19.1.0 (2025-12)
- **Title:** Digital cellular telecommunications system (Phase 2+) (GSM); Universal Mobile
  Telecommunications System (UMTS); Mobile Application Part (MAP) specification
- **Source artefact:** `https://www.3gpp.org/ftp/Specs/archive/29_series/29.002/29002-j10.zip`
  (contains `29002-j10.docx`, ASN.1 modules, SDL diagrams). Not committed — re-fetch for
  normative text.

---

## 1. Where MAP sits

MAP is the application layer for 2G/3G core-network signalling. It is carried over **TCAP**
→ **SCCP** → **MTP** on the **SS7** stack and speaks **ASN.1** (PER/BER). It is the protocol
the SAS Verifier uses to talk to the **own** HLR/HSS for subscriber state, location and
authentication vectors.

```
Bank App → Bank Backend → SAS ──Resolver──► MSISDN/IMSI
                                └─Verifier─► MAP op ▸ HLR  (intra-network only)
                                └─Policy───► APPROVED | FALLBACK
```

TS 29.002 defines the operations and data types; the *procedures* (who calls whom, errors,
timers) are in TS 23.018 / TS 23.060. The parts that matter for silent auth: **subscriber
state/location enquiry** (PSI/ATI), **authentication-vector retrieval** (SAI), and the
**SMS** operations that protect the OTP fallback path.

---

## 2. The three Verifier questions → MAP operations

| Question the SAS Verifier must answer | MAP op | FS.11 class | Note |
|---------------------------------------|--------|-------------|------|
| Attached / reachable, and where? | `provideSubscriberInfo` (PSI) | Cat 2.1 | Preferred; state+location from VLR/SGSN/MME |
| Same, HLR-forced (any-time) | `anyTimeInterrogation` (ATI) | **Cat 1** | **Intra-network only** — never on interconnect |
| Credential fresh (not SIM-swapped)? | `sendAuthenticationInfo` (SAI) | Cat 3.2 | Auth vectors vs a stored reference |
| Where does the fallback SMS OTP route? | `sendRoutingInfoForSM` (SRI-SM) | Cat 2 | Home Routing / FW anchor |
| Does location agree with the IP binding? | PSI/ATI `locationInformation*` | — | Compare to Resolver IP-geo window |

Rule retained from the design docs: **ATI is interconnect Category 1** — prefer PSI and keep
any ATI strictly intra-network against the own HLR.

---

## 3. Operation reference (codes from `local:` in clause 17)

| Operation | Code | Direction / purpose | Silent-auth relevance |
|-----------|------|---------------------|------------------------|
| `updateLocation` | 2 | VLR→HLR: register subscriber | Mobility context (liveness/roaming) |
| `cancelLocation` | 3 | HLR→VLR: delete record | New VLR ⇒ potential active takeover |
| `purgeMS` | 67 | VLR/SGSN→HLR: subscriber gone | `msPurged` reachability evidence |
| `sendIdentification` | 55 | Request IMSI from VLR | MSISDN→IMSI resolution (CS) |
| `updateGprsLocation` | 23 | SGSN→HLR | PS-side liveness |
| **`provideSubscriberInfo`** | **70** | HLR→VLR/SGSN/MME enquiry | **Primary Verifier probe (preferred)** |
| **`anyTimeInterrogation`** | **71** | gsmSCF→HLR forced enquiry | **Intra-net only** (Cat 1); fallback probe |
| **`sendAuthenticationInfo`** | **56** | Request auth vectors | **SIM-swap / freshness signal** |
| `insertSubscriberData` | 7 | HLR→VLR subscribe push | Subscription change → re-score authority |
| `restoreData` | 57 | VLR→HLR after restart | Fault recovery (not a swap) |
| `sendIMSI` | 58 | MSISDN → IMSI | Binding check |
| `sendRoutingInfoForSM` | 45 | GMSC/SMSC→HLR routing info | OTP-fallback Home Routing anchor |
| `mo-ForwardSM` | 46 | MSC→SMSC (mobile-originated) | MO-side origin checks |
| `mt-ForwardSM` | 44 | SMSC→MSC (mobile-terminated) | MT-spoof / delivery correlation |
| `sendRoutingInfoForGprs` | 24 | SGSN-side routing info | PS routing |

Codes verified against `OPERATION ::= { … CODE local:n }` in the ASN.1 modules of
`29002-j10.docx`. The enquiry operations use `--Timer m` (medium).

---

## 4. Key IEs for the SAS Verifier

### 4.1 `provideSubscriberInfo` (PSI)

```asn.1
ProvideSubscriberInfoArg ::= SEQUENCE {
    imsi               [0] IMSI,
    lmsi               [1] LMSI OPTIONAL,
    requestedInfo      [2] RequestedInfo,
    extensionContainer [3] ExtensionContainer OPTIONAL, ...,
    callPriority       [4] EMLPP-Priority OPTIONAL }
```

Response carries `SubscriberInfo`:

```asn.1
SubscriberInfo ::= SEQUENCE {
    locationInformation     [0] LocationInformation OPTIONAL,   -- 2G/3G CS
    subscriberState         [1] SubscriberState OPTIONAL,
    locationInformationGPRS [3] LocationInformationGPRS OPTIONAL,
    ps-SubscriberState      [4] PS-SubscriberState OPTIONAL,
    imei                    [5] IMEI OPTIONAL,
    lastUE-ActivityTime     [10] Time OPTIONAL,
    lastRAT-Type            [11] Used-RAT-Type OPTIONAL,
    eps-SubscriberState     [12] PS-SubscriberState OPTIONAL,
    locationInformationEPS  [13] LocationInformationEPS OPTIONAL,
    locationInformation5GS  [16] LocationInformation5GS OPTIONAL, ... }
```

`requestedInfo` selects exactly what to return (minimise data — privacy):

```asn.1
RequestedInfo ::= SEQUENCE {
    locationInformation   [0] NULL OPTIONAL,
    subscriberState       [1] NULL OPTIONAL,
    currentLocation       [3] NULL OPTIONAL,
    requestedDomain       [4] DomainType OPTIONAL,   -- cs-Domain / ps-Domain
    imei                  [6] NULL OPTIONAL,
    mnpRequestedInfo      [7] NULL OPTIONAL,
    servingNodeIndication [10] NULL OPTIONAL,
    localTimeZoneRequest  [12] NULL OPTIONAL, ... }
```

### 4.2 Subscriber state (reachability input)

```asn.1
SubscriberState ::= CHOICE {
    assumedIdle        [0] NULL,
    camelBusy          [1] NULL,
    netDetNotReachable     NotReachableReason,
    notProvidedFromVLR [2] NULL }

PS-SubscriberState ::= CHOICE {
    notProvidedFromSGSNorMME        [0] NULL,
    ps-Detached                     [1] NULL,
    ps-AttachedNotReachableForPaging [2] NULL,
    ps-AttachedReachableForPaging    [3] NULL,
    ps-PDP-ActiveNotReachableForPaging [4] PDP-ContextInfoList,
    ps-PDP-ActiveReachableForPaging    [5] PDP-ContextInfoList,
    netDetNotReachable                 NotReachableReason }

NotReachableReason ::= ENUMERATED { msPurged(0), imsiDetached(1),
                                    restrictedArea(2), notRegistered(3) }
```

Scoring: `attached / pdp-active + reachable` → strong "live"; `msPurged` → **not** on
network ⇒ FALLBACK.

### 4.3 `sendAuthenticationInfo` (SAI) — SIM-swap freshness

```asn.1
SendAuthenticationInfoArg ::= SEQUENCE {
    imsi                     [0] IMSI,
    numberOfRequestedVectors NumberOfRequestedVectors,   -- INTEGER (1..5)
    immediateResponsePreferred [1] NULL OPTIONAL,
    requestingNodeType       [3] RequestingNodeType OPTIONAL,   -- vlr|sgsn|mme
    requestingPLMN-Id        [4] PLMN-Id OPTIONAL,
    numberOfRequestedAdditional-Vectors [5] ... OPTIONAL,
    additionalVectorsAreForEPS [6] NULL OPTIONAL, ... }

SendAuthenticationInfoRes ::= [3] SEQUENCE {
    authenticationSetList         AuthenticationSetList OPTIONAL,      -- 2G/3G vectors
    eps-AuthenticationSetList [2] EPS-AuthenticationSetList OPTIONAL,  -- EPC-AV{RAND,XRES,AUTN,KASME}
    ueUsageType               [3] UE-UsageType OPTIONAL }
```

SIM-swap strategy: store the **last-seen auth-vector set / freshness timestamp** per IMSI; a
later request returning a *new* set is a swap indicator. Request a small
`numberOfRequestedVectors` (e.g. 1) to keep the probe cheap.

---

## 5. Security mapping (must not regress — see design checklist)

- **No interconnect ATI** — FS.11 Cat 1; Verifier queries the **own** HLR only; PSI is the
  Cat 2.1-approved alternative.
- **Fail-closed** — absent `subscriberState`, missing auth vectors, or `netDetNotReachable`
  ⇒ FALLBACK, never soft-approve.
- **One dialog per stage** — bounded TCAP dialog; abort on timeout (no dialog leaks).
- **Point-in-time binding** — treat `locationInformation` + `lastUE-ActivityTime` as a
  snapshot at `ts`; CGNAT still requires IP+port+ts.
- **Privacy** — IMSI/IMEI/location stay on bank backend / SAS; the app only sees the boolean
  outcome.
- **`restoreData` ≠ swap** — a VLR restart triggers `restoreData`, not a credential change.

---

## 6. Source artefact

`29002-j10.zip` extracted to `/tmp/3gpp/29002/` during research (full-text dump
`/tmp/3gpp/29002_full.txt`). Re-fetch for normative ASN.1 before any jSS7
(`MAPServiceMobility`) wiring.