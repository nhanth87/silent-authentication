# Chapter 9 — Security, Privacy, and Compliance

**Restlink Silent Authentication for Ethiopia**  
**Document:** Proposal Chapter 09  
**Version:** 1.0 · July 2026

---

## 9.1 Purpose and scope

This chapter consolidates the **security, privacy, and regulatory compliance** requirements for the Restlink Silent Authentication pilot in Ethiopia. It provides an implementable checklist derived from the SAS design constraints, GSMA FASG signalling rules (Chapter 8), and CAMARA Open Gateway security profile (Chapter 7). It also maps the programme at a high level to **United Nations** and **ITU** cybersecurity guidance applicable to critical digital identity infrastructure.

The objective is to give security reviewers, regulators, and integration partners a single reference for deployment invariants, data-handling rules, and audit evidence — without substituting for operator-specific security assessments or national legal counsel.

---

## 9.2 Security design principles

| Principle | Definition | Implementation |
|-----------|------------|----------------|
| **Fail-closed** | Missing or ambiguous evidence never approves authentication | SAS FSM: any stage failure → `FALLBACK`, never soft-pass |
| **Defence in depth** | Application-layer silent auth plus signalling-layer OTP protection | Strategy A + Strategy B (Chapter 8) |
| **Least privilege** | Each component accesses only the subscriber signals required for its function | Verifier: PSI/ULR + Sh UDR only; no broad HSS export |
| **Operator-internal signalling** | MAP/Diameter queries never originate from or traverse untrusted interconnect | SAS deployed inside Ethio Telecom network |
| **Privacy by design** | MSISDN/IMSI minimisation; backend-only exposure | No subscriber identifiers to mobile app |
| **Auditability** | Every `/verify` decision traceable via `reqId` | Structured logs; no PII in app-facing errors |

---

## 9.3 Deployment security checklist

The following checklist is **mandatory** for pilot go-live. Items marked *(operator)* are Ethio Telecom responsibilities; items marked *(Restlink)* are Restlink responsibilities; *(joint)* requires coordinated implementation.

### 9.3.1 Signalling and network placement

| # | Control | Detail | Owner | Standard |
|---|---------|--------|-------|----------|
| S-01 | **No interconnect ATI** | `AnyTimeInterrogation` is FS.11 Category 1 — blocked on interconnect. SAS Verifier queries **own HLR/HSS intra-network only** | *(joint)* | FS.11 Cat 1 |
| S-02 | PSI preferred over ATI | Use ProvideSubscriberInfo (Cat 2.1) as primary 2G/3G verifier; ATI only if operator policy permits intra-net | *(Restlink)* | FS.11 Cat 2.1 |
| S-03 | Intra-network Diameter only | S6a ULR/ULA + Sh UDR from SAS to own HSS; no interconnect-originated subscriber queries | *(Restlink)* | FS.19 |
| S-04 | SAS inside operator trust zone | Restlink SAS hosts in operator DMZ / trusted VAS segment; no public SS7/Diameter exposure | *(joint)* | FS.31 |
| S-05 | SMS Home Routing confirmed | Residual OTP traverses Home Routing before pilot | *(operator)* | FS.11, TS 23.040 |
| S-06 | SS7 FW Double MAP rule | Block TCAP Begin with multiple MAP components (except documented pair) | *(operator)* | FS.11 CVD-2018-0015 |
| S-07 | DEA deployed for LTE SMS path | Diameter edge filter on S6a/S6c/SGd | *(operator)* | FS.19 |
| S-08 | Cross-generation policy consistency | FS.11 + FS.19 + FS.36 rules aligned per roaming partner | *(operator)* | FS.21 |

### 9.3.2 API and transport security

| # | Control | Detail | Owner | Standard |
|---|---------|--------|-------|----------|
| S-09 | **mTLS bank → Restlink** | Mutual TLS on all `/verify` and CAMARA API calls; client certificate per bank/e-Gov backend | *(Restlink)* | Open Gateway profile |
| S-10 | **reqId idempotency** | Every request carries unique `reqId`; SAS deduplicates retries; one MAP/Diameter dialog per stage per `reqId` | *(Restlink)* | SAS design |
| S-11 | Anti-replay window | `ts` (timestamp) validated within configurable skew window (e.g. ±60 s) | *(Restlink)* | SAS design |
| S-12 | OAuth 2.0 / OIDC | Client credentials or JWT bearer for API authorisation layer above mTLS | *(Restlink)* | CAMARA / Open Gateway |
| S-13 | TLS 1.2+ only | No deprecated cipher suites on API endpoints | *(Restlink)* | Industry baseline |
| S-14 | Rate limiting per client | Per-bank/e-Gov rate limits on `/verify`; coordinated with operator signalling rate policy | *(joint)* | FS.31 |

### 9.3.3 Authentication logic and dialog lifecycle

| # | Control | Detail | Owner |
|---|---------|--------|-------|
| S-15 | **Fail-closed scoring** | `assurance < threshold` → `FALLBACK`; never approve on partial evidence | *(Restlink)* |
| S-16 | CGNAT disambiguation | Require `srcIP + srcPort + ts`; reject if Resolver returns >1 MSISDN | *(Restlink)* |
| S-17 | Point-in-time binding | Resolver read keyed to request `ts`, not "latest session" | *(Restlink)* |
| S-18 | Dialog leak prevention | Every MAP dialog bounded by TC timer (2 s); timeout → `abort()`; no hung HSS queries | *(Restlink)* |
| S-19 | Total SAS budget ≤ 3 s | Resolver 300 ms; MAP/Diameter 2 s; total 3 s → `FALLBACK` | *(Restlink)* |
| S-20 | SIM-swap cooldown | Downgrade/block when `lastUpdateLocation` age < configured cooldown | *(Restlink)* |
| S-21 | Spoofed GT defence | Verifier trusts only responses from own HSS; ignore interconnect-originated answers | *(Restlink)* | FS.11 §3.3.4 |

### 9.3.4 Privacy and data handling

| # | Control | Detail | Owner |
|---|---------|--------|-------|
| S-22 | **MSISDN not to mobile app** | Verified MSISDN returned to **bank backend only** over mTLS; mobile app receives login token from its own backend | *(joint)* |
| S-23 | IMSI not exposed externally | IMSI used internally by Verifier; not included in CAMARA API responses to apps | *(Restlink)* |
| S-24 | Minimal response fields | Return only `{match, assurance, reqId, fallbackRecommended}` plus optional verified MSISDN to backend | *(Restlink)* |
| S-25 | Log redaction | Application logs exclude IMSI; MSISDN hashed or tokenised in operational logs | *(Restlink)* |
| S-26 | Consent and purpose limitation | CAMARA consent framework for KYC Match / Scam Signal; NV login scoped to stated purpose | *(joint)* |
| S-27 | Data retention limits | `reqId` audit records retained per regulatory minimum; no indefinite HSS query cache | *(Restlink)* |
| S-28 | No cross-border subscriber export | Subscriber queries and results remain within Ethiopia operator network | *(joint)* |

---

## 9.4 Privacy model

### 9.4.1 Data flows and exposure boundaries

| Data element | Mobile app | Bank backend | Restlink SAS | Ethio Telecom core |
|--------------|------------|--------------|-------------|-------------------|
| `srcIP`, `srcPort`, `ts` | Collects | Forwards in `/verify` | Consumes | PGW session store |
| `claimedMSISDN` | May display (user-entered) | Forwards | Validates | — |
| Verified MSISDN | **Must not receive** | Receives (mTLS) | Produces | Source of truth |
| IMSI | **Must not receive** | **Must not receive** | Internal only | HLR/HSS |
| `assurance`, `match` | Via bank login API | Receives | Produces | — |
| Location / VLR / MME | **Must not receive** | **Must not receive** | Internal scoring only | HLR/HSS |

### 9.4.2 Rationale

Phone numbers and IMSIs are **personal data** under applicable data-protection frameworks. The mobile application runs on a device that may be shared, rooted, or observed by malicious software. Returning MSISDN or IMSI to the app would expand the attack surface and violate the Open Gateway principle that network-held identity is disclosed only to **authorised, authenticated backend services** under contract and consent.

The bank backend already knows the customer's claimed identity from the login context. SAS confirms or refutes that claim server-to-server. The app needs only a boolean login outcome from its backend.

---

## 9.5 Operational security

| Area | Requirement |
|------|-------------|
| **Key management** | mTLS certificates rotated per operator PKI policy; HSM-backed keys for production |
| **Secrets** | No HSS/PGW credentials in application config plaintext; vault or operator secret store |
| **Monitoring** | Alert on `/verify` error rate spikes, MAP dialog timeout rate, unusual per-client volume |
| **Incident response** | Documented procedure to suspend bank client certificate without affecting other tenants |
| **Penetration testing** | Pre-pilot test of API layer; operator-led signalling security assessment (FS.11/19) |
| **Vulnerability management** | Patch cadence for SAS runtime; dependency scanning |

---

## 9.6 Compliance alignment — UN and ITU (high level)

The following mapping is **non-exhaustive** and intended for proposal-level assurance. Formal compliance certification is out of scope for this document.

### 9.6.1 UN cybersecurity guidance

| UN instrument / body | Relevant principle | Restlink alignment |
|---------------------|-------------------|----------------------|
| **UN GGE norms (2015/2021)** | Protect critical infrastructure; responsible state and private-sector behaviour | Operator-owned signalling border; VAS does not weaken interconnect controls |
| **UN ESCAP / digital identity frameworks** | Trusted digital identity for inclusive finance and e-Government | CAMARA-standard NV for phone-number identity; fail-closed assurance |
| **UN Principles on Personal Data Protection and Privacy** (2018) | Lawfulness, purpose limitation, minimisation, security, accountability | MSISDN backend-only; minimal API fields; audit via `reqId`; consent for extended APIs |
| **Sustainable Development Goals (SDG 9, 16)** | Resilient infrastructure; effective institutions | Reduced OTP fraud; standards-based interoperable identity layer |

### 9.6.2 ITU cybersecurity guidance

| ITU reference | Relevant principle | Restlink alignment |
|---------------|-------------------|----------------------|
| **ITU-T X.1051 / X.1055** (security management / resilience) | Information security management for telecom organisations | SAS fail-closed FSM; bounded timeouts; dialog lifecycle management |
| **ITU-T X.800 / X.814** (security architecture) | Defence in depth; security domains and trust boundaries | Three-layer model: app / VAS / operator core; mTLS trust boundary |
| **ITU-T E.164 / numbering integrity** | Number portability and identity accuracy | Number Recycling check; SIM Swap signal |
| **ITU-D cybersecurity capacity models** | Developing-country digital transformation security | Open Gateway VAS model leveraging operator network without duplicating core |
| **ITU-T SG17 (identity and trust)** | Identity management in NGN / mobile ecosystems | CAMARA NV + TS.43 SIM credential path |

### 9.6.3 GSMA and 3GPP (programme-specific)

| Framework | Role in compliance story |
|-----------|-------------------------|
| **GSMA Open Gateway / CAMARA** | App-layer API contract and security profile |
| **GSMA FASG (FS.07–FS.36, SG.22, FF.09)** | Signalling-layer interconnect and SMS protection |
| **GSMA TS.43** | SIM-based entitlement and EAP-AKA silent auth |
| **3GPP TS 33.501** | 5G security architecture (SEPP, N32) |
| **3GPP TS 23.040** | SMS Home Routing |

---

## 9.7 Regulatory considerations — Ethiopia (high level)

| Topic | Consideration | Programme response |
|-------|---------------|-------------------|
| **Financial sector identity** | National Bank of Ethiopia expectations for strong customer authentication in digital banking | Silent Auth as step-up / login factor; documented assurance levels; OTP fallback preserved |
| **e-Government identity** | Citizen portal authentication tied to registered mobile number | CAMARA NV with optional KYC Match for onboarding |
| **Telecommunications regulation** | VAS provider operates under operator agreement | Restlink as Ethio Telecom VAS partner; no independent signalling |
| **Data protection** | Personal data processing obligations | Minimisation checklist (§9.3.4); backend-only MSISDN; retention limits |
| **Lawful intercept** | Operator obligation independent of VAS | SAS design does not impede operator lawful intercept capabilities |

Specific legal review against Ethiopian statute and regulator guidance is recommended before national rollout.

---

## 9.8 Assurance levels and step-up policy

| Assurance | Typical evidence | Bank action (configurable) |
|-----------|------------------|----------------------------|
| **HIGH** | Fresh IP binding + reachable subscriber + no recent SIM swap + location plausible | Approve login silently |
| **MEDIUM** | Partial evidence (e.g. stale binding, borderline swap age) | Approve low-value; step-up for transfers |
| **LOW** | Weak or conflicting evidence | Force `FALLBACK` — OTP / Passkey / TOTP |
| **FALLBACK** | Resolver/Verifier timeout, Wi-Fi-only without TS.43, CGNAT ambiguity | Bank triggers alternate MFA |

High-value transactions (e.g. wire transfer above threshold) may require `HIGH` assurance regardless of login assurance, or mandatory Passkey step-up — bank-configurable via policy API.

---

## 9.9 Audit and evidence

| Event | Logged fields | Retention |
|-------|---------------|-----------|
| `/verify` request | `reqId`, clientId, `ts`, result (`match`, `assurance`), latency, stage outcomes | Per regulatory minimum (proposed 12 months) |
| MAP/Diameter dialog | `reqId`, opcode, result code, duration; **no IMSI in clear text** | Shorter operational window (proposed 90 days) |
| Security incident | GT block, certificate revocation, anomalous volume | Incident record lifecycle |

Auditors should be able to reconstruct any authentication decision from `reqId` without accessing live HSS data.

---

## 9.10 Pre-go-live sign-off matrix

| Review area | Reviewer | Sign-off criterion |
|-------------|----------|-------------------|
| Signalling placement | Ethio Telecom security | S-01 through S-08 verified |
| API security | Bank CISO / e-Gov IT security | S-09 through S-14; pen test complete |
| SAS logic | Restlink engineering + operator | S-15 through S-21; FSM test suite pass |
| Privacy | Legal / DPO | S-22 through S-28; DPIA if required |
| Fallback OTP path | Ethio Telecom + Restlink | Home Routing + SG.22 policy active |
| Compliance narrative | Programme sponsor | This chapter + Chapters 7–8 accepted |

---

## 9.11 Summary

Restlink Silent Authentication is designed for **fail-closed, operator-internal, privacy-minimising** deployment. The non-negotiable controls are: **no interconnect ATI**, **mTLS with reqId idempotency**, **MSISDN never returned to the mobile app**, and **Strategy B protection for every residual OTP**. These align with GSMA FASG signalling security, CAMARA Open Gateway API practice, and high-level UN/ITU principles for trustworthy digital identity in telecom networks. Ethiopian regulatory specifics require dedicated legal review; this chapter provides the technical and architectural foundation for that review.

---

*References: `docs/design/silent-auth-standard-flow.md` §8; `docs/design/unified-identity-sms-security-architecture.md`; Chapters 7–8 of this proposal; GSMA FS.11; CAMARA Open Gateway security profile.*
