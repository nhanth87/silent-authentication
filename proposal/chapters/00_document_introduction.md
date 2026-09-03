# Document Introduction — How to Read This Proposal

**Proposal title:** Network-Side Silent Authentication for Ethiopian Government, Banking, and Digital Public Services  
**Submitted by:** Restlink (Value-Added Services Partner)  
**Network operator context:** Ethio Telecom  
**Companion presentation:** `Restlink_Silent_AuthProposal_v3.pptx` (28 slides; generated as *Silent Auth Mix v3*)  
**Document classification:** Confidential — Government & Financial Sector  
**Version:** 1.0 — July 2026  
**Target length:** ~50 Word pages (assembled via `scripts/build_proposal_docx.py`)

---

## 0.1 Purpose of This Proposal

This document is a **formal technical and commercial proposal** to the Government of Ethiopia, Ethio Telecom, the National Bank of Ethiopia (NBE), and participating commercial banks. It recommends deployment of a **Silent Authentication Service (SAS)** as a **Value-Added Service (VAS) adapter** on Ethio Telecom infrastructure, operated and integrated by **Restlink** for government portals and financial institution backends.

The proposal addresses a structural weakness in Ethiopia's accelerating digitisation: **proof of possession of a mobile phone number is overwhelmingly implemented as SMS one-time password (OTP) delivery**. SMS OTP is familiar, interoperable across handsets, and inexpensive to integrate. It is also **exposed to signalling-layer interception**, **SIM-swap account takeover**, **artificial inflation of traffic (AIT)**, and **real-time OTP relay phishing**—threats documented in independent security research and operator fraud programmes worldwide.

Silent authentication replaces the SMS delivery step for eligible sessions by verifying that the device currently attached to the cellular network owns the claimed MSISDN, using **operator-internal** correlation of live data bearer (IP, port, timestamp) with HLR/HSS subscriber state under **GSMA interconnect security** norms (**FS.11** for SS7 MAP; **FS.19** for Diameter S6a). The application-facing contract aligns with **CAMARA Number Verification (NV)** and GSMA Open Gateway patterns. Residual authentication steps up to passkey, TOTP, or **operator-billed SMS OTP** on the Ethio Telecom SMSC when evidence is insufficient—a **fail-closed** design.

This proposal is intended to support **investment decisions**, **regulatory review**, **operator partnership negotiation**, and **integrator technical onboarding**. It is not a marketing brochure: it includes MAP/Diameter message flows, finite-state machine (FSM) timeout budgets, GSMA document cross-references, and illustrative commercial modelling clearly labelled where projections are used.

---

## 0.2 Intended Audience and Reading Paths

Different stakeholders enter the material with different obligations. The table below recommends **primary chapters** and **optional depth** for each audience. All readers should begin with Chapter 0 (this introduction) and Chapter 1 (Executive Summary).

| Audience | Primary concern | Read first | Read for depth | Skim unless engaged |
|----------|-----------------|------------|----------------|---------------------|
| **Ministry of Innovation and Technology (MInT)** | e-Gov trust, citizen UX, Digital Ethiopia 2025 | Ch. 1, 3, 4, 11 | Ch. 2b, 7, 9 | Ch. 5–6 (signalling detail) |
| **National Bank of Ethiopia (NBE)** | Strong customer authentication, ATO, payment integrity | Ch. 1, 2, 2b, 9, 10 | Ch. 4, 6, 8 | Ch. 5 (MAP opcodes) |
| **Ethio Telecom — executive** | Revenue neutrality, VAS partnership, core asset use | Ch. 1, 3, 10, 11 | Ch. 7, 8 | Ch. 5–6 |
| **Ethio Telecom — core / signalling engineering** | FS.11/FS.19 deployment, dialog integrity, firewall | Ch. 5, 6, 8, 9, 12 | Ch. 4, 7 | Ch. 10 |
| **Commercial bank CIO / CISO** | Integration effort, fraud reduction, SMS cost | Ch. 1, 4, 10, 11 | Ch. 2b, 7, 9 | Ch. 8 (GSMA index) |
| **Bank integration engineers** | API contract, SDK, fallback behaviour | Ch. 4, 5, 6, 7, 12 | Ch. 8, 9 | Ch. 2 (macro fraud) |
| **Development finance / policy (World Bank, UNDP, ITU programmes)** | Digital public infrastructure, inclusion, cyber risk | Ch. 1, 2b, 3, 11 | Ch. 9, 10 | Ch. 5–6 |
| **INSA / national CERT** | Signalling abuse, interconnect posture | Ch. 2, 2b, 8, 9 | Ch. 5, 6 | Ch. 10 |

### 0.2.1 Executive path (≤45 minutes)

1. **Chapter 0** — document map and definitions (this chapter).  
2. **Chapter 1** — problem, solution, dual-strategy architecture, KPIs.  
3. **Chapter 2b** — case studies and international statistics (skim tables).  
4. **Chapter 3** — Ethiopia market and Restlink positioning.  
5. **Chapter 11** — implementation roadmap and pilot structure.

### 0.2.2 Technical path (half-day workshop)

1. Chapters **4–6** — solution overview, message flows, FSM and timeouts.  
2. Chapters **7–8** — CAMARA API mapping and GSMA FASG security catalogue.  
3. **Chapter 9** — compliance, data handling, threat–control matrix.  
4. **Chapter 12** — appendices, open items, glossary.

### 0.2.3 Commercial / procurement path

1. Chapters **1, 3, 10** — value proposition, stakeholder economics, pricing model.  
2. **Chapter 11** — phases, SLAs, acceptance criteria.  
3. **Chapter 2b** — fraud cost context for business case (tables marked *ILLUSTRATIVE* are modelling inputs, not audited operator figures).

---

## 0.3 Relationship to the Presentation Deck

The companion deck **`Restlink_Silent_AuthProposal_v3.pptx`** contains **28 slides** synthesising the **business narrative** (citizen and e-Gov persona, commercial model, value proposition) with **technical depth** (two-stage Resolver/Verifier, ATI/PSI/SAI, Diameter S6a, FSM timeouts, CAMARA and GSMA tables). The written proposal **expands** every slide into narrative chapters with citations, sequence diagrams, and procurement-ready detail. The deck is suitable for **steering committees and bilateral meetings**; this document is suitable for **due diligence, security review, and contract annexes**.

### 0.3.1 Slide-to-chapter mapping (28 slides)

| Slide # | Deck title (short) | Primary written chapter(s) | Section / topic in proposal |
|--------:|----------------------|----------------------------|-----------------------------|
| 1 | Title | 0, 1 | Cover metadata, executive framing |
| 2 | Agenda | 0 | Document structure (§0.4) |
| 3 | Why Ethiopia, why now | 3 | §3.1–3.3 market urgency |
| 4 | The SMS OTP problem | 2, 2b | §2.3 SS7; §2b.2 case narratives |
| 5 | Government digital services | 3 | §3.3 e-Government; Fayda binding |
| 6 | Threats Silent Auth removes | 2, 2b, 8 | Threat taxonomy; FF.09; Strategy A |
| 7 | **Section:** The solution | 4 | Solution overview divider |
| 8 | Two-stage design (Resolver + Verifier) | 4, 5, 6 | Hard constraint: IP→MSISDN not on MAP |
| 9 | End-to-end message flow | 5 | Full sequence; bank BE → SAS → HLR |
| 10 | ATI deep dive | 5 | MAP AnyTimeInterrogation; FS.11 Cat.1 |
| 11 | PSI · SAI · Diameter S6a | 5, 6 | Verifier opcodes; SIM-swap freshness |
| 12 | Architecture & pocket (adapter) | 3, 4, 10 | VAS layer; no SMSC competition |
| 13 | Fallback SMS | 4, 6 | Fail-closed; Strategy B residual OTP |
| 14 | SAS FSM + timeouts | 6 | RESOLVING→VERIFYING→SCORING→APPROVED |
| 15 | Security checklist | 8, 9 | FS.11/19/36; mTLS; no MSISDN to app |
| 16 | Bank integration + jSS7 | 5, 11, 12 | SDK steps; coral-valley MAP classes |
| 17 | **Section:** Standards & APIs | 7, 8 | CAMARA + GSMA block |
| 18 | CAMARA APIs table | 7 | NV, SIM Swap, OTP SMS, KYC Match |
| 19 | CAMARA ↔ Restlink SAS | 7 | API equivalence to /verify pipeline |
| 20 | GSMA FASG index | 8 | FS.07–FS.36, SG.22, FF.09 |
| 21 | FS.11 MAP categories | 8 | Cat 1 block; PSI/SAI deployment rules |
| 22 | Strategy A vs B + standards | 8, 9 | Replace OTP vs protect OTP |
| 23 | Open Gateway positioning | 7, 3 | Ethio Telecom as network truth source |
| 24 | Value for government & banks | 1, 10 | Conversion, cost, fraud posture |
| 25 | Ethio Telecom partnership + roadmap | 11, 10 | Phases 1–3; revenue share option |
| 26 | Open items | 12 | Resolver source, Diameter S6a client, policy store |
| 27 | Call to action | 1, 11 | Pilot ask |
| 28 | Thank you | 0 | Closing; reference list in Ch. 12 |

### 0.3.2 How slide sections map to proposal parts

| Deck section (slides) | Proposal part | Chapters |
|----------------------|---------------|----------|
| Context & problem (1–6) | **Part I — Case for change** | 0, 1, 2, 2b, 3 |
| Solution & engineering (7–16) | **Part II — Technical design** | 4, 5, 6 |
| Standards (17–23) | **Part III — Standards & security** | 7, 8, 9 |
| Value & execution (24–28) | **Part IV — Commercial & delivery** | 10, 11, 12 |

Readers who attended a deck presentation should use the slide number in the table above to locate the **authoritative expanded treatment** in the written chapters. Where the deck shows a single bullet, the proposal typically provides a **table, diagram, or cited statistic**.

---

## 0.4 Document Structure Map

The assembled Word document (`Restlink_Silent_Auth_Proposal_v3.docx`) concatenates chapters in the order below. Page counts are **approximate** at standard A4 formatting (~500 words/page); actual pagination depends on table and diagram rendering.

| Order | File | Chapter title | Approx. pages | Primary content |
|------:|------|---------------|---------------:|-----------------|
| 0 | `00_document_introduction.md` | Document Introduction | 4–6 | How to read; definitions; deck mapping |
| 1 | `01_executive_summary.md` | Executive Summary | 4–5 | Decision summary; dual strategy; KPIs |
| 2 | `02_fraud_landscape.md` | Global Fraud Landscape | 6–8 | SS7, SIM swap, AIT, Ethiopia motivation |
| 3 | `02b_case_studies_and_un_data.md` | Case Studies & UN/ITU Data | 8–10 | Narratives; international stats; e-Gov comparison |
| 4 | `03_ethiopia_market.md` | Ethiopia Market Context | 6–8 | Ethio Telecom; Fayda; banks; Restlink role |
| 5 | `04_solution_overview.md` | Solution Overview | 5–6 | SAS architecture; Resolver/Verifier; fallback |
| 6 | `05_message_flows.md` | Message Flows | 6–8 | ATI, PSI, SAI, S6a ULR/ULA + Sh UDR; sequence diagrams |
| 7 | `06_sas_fsm_timeouts.md` | FSM, Timeouts & Dialog Anchor | 4–5 | 300 ms / 2 s / 3 s budgets; fail-closed |
| 8 | `07_camara_open_gateway.md` | CAMARA & Open Gateway | 5–6 | NV, SIM Swap, TS.43; API mapping |
| 9 | `08_gsma_fasg_security.md` | GSMA FASG Security | 6–7 | FS.11 categories; Strategy B controls |
| 10 | `09_security_compliance.md` | Security & Compliance | 5–6 | Data handling; regulatory alignment |
| 11 | `10_commercial_model.md` | Commercial Model | 4–5 | Pricing; operator revenue neutrality |
| 12 | `11_implementation_roadmap.md` | Implementation Roadmap | 4–5 | Pilot; phases; acceptance tests |
| 13 | `12_appendices.md` | Appendices | 4–6 | Glossary; references; open items |
| — | Charts from `assets/` | Embedded figures | 2–3 | Fraud stats; ROI (*ILLUSTRATIVE*) |
| | **Total** | | **~50** | |

### 0.4.1 Cross-reference conventions

| Convention | Meaning |
|------------|---------|
| *cited* | Figure or claim traceable to a named public source (year in text or footnote) |
| *estimated* | Reasoned projection from partial public data or sector interviews |
| *ILLUSTRATIVE* | Modelling scenario for discussion; not audited operator or government statistics |
| **Strategy A** | Replace OTP with silent network verification (CAMARA NV / Restlink `/verify`) |
| **Strategy B** | Protect residual SMS OTP (Home Routing, SS7/Diameter/5G firewall per FS.11/19/36) |
| **FALLBACK** | SAS outcome when evidence insufficient; integrator must step up (OTP, passkey, branch) |
| **APPROVED** | SAS outcome when Resolver + Verifier evidence meets policy threshold |

### 0.4.2 External reference documents (project repository)

| Path | Role |
|------|------|
| `docs/design/silent-auth-standard-flow.md` | Authoritative SAS flow and timeout design |
| `docs/design/unified-identity-sms-security-architecture.md` | Strategy A + B unified architecture |
| `docs/research/gsma-fs-index.md` | GSMA FASG document index |
| `docs/research/sms-channel-protection.md` | SMS Home Routing and firewall research |
| `slides/Restlink_Silent_AuthProposal_v3.pptx` | 28-slide executive + technical deck |
| `proposal/assets/fraud_stats.json` | Chart data; mix of *cited* and *estimated* |

---

## 0.5 Definitions and Roles

### 0.5.1 Restlink — VAS adapter (not an operator)

**Restlink** is the **Value-Added Services integrator** proposing to deploy and operate the Silent Authentication Service **above** Ethio Telecom core network functions. Restlink:

| Attribute | Definition |
|-----------|------------|
| **Legal/commercial role** | Enterprise VAS provider contracting with banks, ministries, and payment agencies |
| **Technical role** | Hosts SAS application logic, CAMARA API façade, integrator SDKs, and policy engine |
| **Network role** | Consumes **operator-internal** interfaces (PGW session binding, HLR/HSS queries) under Ethio Telecom governance |
| **Explicit non-role** | Does **not** operate a competing SMSC; does **not** terminate international SMS interconnect; does **not** displace Ethio Telecom A2P SMS revenue |
| **Billing model** | Charges integrators per successful `/verify` (or CAMARA NV equivalent); optional revenue share with Ethio Telecom |
| **Fallback SMS** | When SAS returns FALLBACK, OTP SMS is sent via **Ethio Telecom SMSC** with **existing** enterprise SMS billing |

The **adapter pattern** preserves operator ownership of subscriber truth (HLR/HSS/UDM, PGW, SMSC) while allowing multiple integrators to share one CAMARA-aligned verification contract—analogous to content billing gateways or mobile financial service hubs, but for **authentication evidence** rather than media or ledger entries.

### 0.5.2 Ethio Telecom — network operator and source of truth

**Ethio Telecom** is Ethiopia's primary mobile network operator and the **authoritative custodian** of subscriber identity binding (MSISDN ↔ IMSI), live session attachment (PGW/GGSN), and SMS delivery infrastructure. For silent authentication:

| Function | Operator responsibility |
|----------|-------------------------|
| HLR/HSS/UDM | Subscriber profile; PSI/SAI/ULR/Sh UDR responses |
| PGW/GGSN/PCRF/CGNAT | Resolver input: IP + port + timestamp → MSISDN |
| SMSC | Fallback OTP only; unchanged commercial relationship with banks |
| SS7/Diameter/5G border | FS.11/FS.19/FS.36 firewall; SMS Home Routing |
| Partnership | Hosts or private-interconnects Restlink SAS; approves MAP/Diameter allow lists |

Ethio Telecom is **not** asked to become an application security vendor. Restlink absorbs integrator onboarding, SLAs, and CAMARA contract normalisation.

### 0.5.3 Government audience — digital public services and policy

**Government audience** in this proposal comprises:

| Entity | Interest in silent auth |
|--------|-------------------------|
| **MInT** | Single e-Gov gateway; citizen UX; Digital Ethiopia 2025 KPIs |
| **National ID Programme (Fayda / NIDP)** | Binding digital ID credentials to live mobile possession |
| **Ethiopian Revenue and Customs Authority (ERCA)** | e-tax filing integrity; refund fraud prevention |
| **Ministries (civil registration, health, education, social protection)** | Life-event and benefit fraud; SIM-swap theft of entitlements |
| **INSA** | National cyber and signalling security posture |

Government integrators typically require **higher assurance** for benefit disbursement and identity recovery than consumer login, implemented via **risk-based step-up** (silent auth for routine access; passkey or in-person verification for high-impact transactions)—without changing the underlying SAS contract.

### 0.5.4 Banking and financial sector audience

**Banking audience** comprises **NBE** as regulator and **commercial banks** operating mobile banking (Commercial Bank of Ethiopia, Awash, Dashen, Bank of Abyssinia, Cooperative Bank of Oromia, and others). Interests include:

| Topic | Silent auth relevance |
|-------|----------------------|
| **Strong customer authentication** | Replace weak SMS OTP where cellular evidence exists |
| **Account takeover (ATO)** | Reduce SIM-swap and SS7 OTP harvest success rate |
| **Operational cost** | Lower enterprise SMS OTP spend on silent happy path |
| **Telebirr / wallet interoperability** | Shared MSISDN identity layer across payment rails |
| **Open Gateway portability** | CAMARA NV skill transfer to other markets |

NBE electronic payment rules and fraud reporting expectations align with **documented controls** (fail-closed SAS, SIM-swap cooldown, audit logs)—detailed in Chapter 9.

### 0.5.5 Core technical terms (glossary preview)

| Term | Definition |
|------|------------|
| **MSISDN** | Mobile station international subscriber directory number (E.164 phone number) |
| **IMSI** | International mobile subscriber identity (SIM subscription identifier) |
| **SAS** | Silent Authentication Service operated by Restlink on operator infrastructure |
| **Resolver** | Stage 1: map cellular IP:port:timestamp to MSISDN via PGW/PCRF/CGNAT |
| **Verifier** | Stage 2: confirm subscriber live/reachable via PSI/SAI or Diameter S6a |
| **CAMARA NV** | GSMA Open Gateway Number Verification API family |
| **FS.11 Cat.1** | GSMA rule: operations such as ATI **blocked** on international interconnect |
| **AIT** | Artificial inflation of traffic—fraudulent generation of billable SMS events |
| **ATO** | Account takeover—unauthorised access via stolen credentials or SIM |

Full glossary: Chapter 12.

---

## 0.6 Scope, Assumptions, and Exclusions

### 0.6.1 In scope

| Item | Description |
|------|-------------|
| Silent authentication via **IP-matching** (cellular data bearer) | Phase 1 pilot core |
| MAP verifier messages (PSI, SAI; ATI intra-network where policy permits) | 2G/3G footprint |
| Diameter S6a verifier (ULR/ULA) + Sh UDR | 4G/5G subscribers |
| CAMARA-aligned HTTPS API to integrators | NV primary; SIM Swap signal Phase 2 |
| Fallback orchestration to operator SMS OTP | No alternate SMSC |
| Dual-strategy documentation (Strategy A + B) | Complementary, not either/or |
| Pilot roadmap with measurable KPIs | See Chapter 1, 11 |

### 0.6.2 Out of scope (initial phases)

| Item | Rationale |
|------|-----------|
| GSMA TS.43 EAP-AKA SIM-based NV2 on Wi-Fi | Phase 3; documented as roadmap |
| Device hardware attestation (SafetyNet / Play Integrity) | Integrator optional layer |
| Fayda biometric verification itself | NIDP scope; SAS complements possession proof |
| Replacement of Ethio Telecom core HLR/SMSC | Not required for VAS adapter |
| Cross-operator verification (MNP off-net) | Home PLMN only in Phase 1 |

### 0.6.3 Key assumptions

| # | Assumption | If false |
|---|------------|----------|
| A1 | Ethio Telecom provides Resolver feed (PGW, PCRF, or CGNAT) with ≤60 s freshness | Resolver coverage reduced; more FALLBACK |
| A2 | Restlink SAS deployed inside operator trust domain | FS.11 Cat.1 compliance at risk |
| A3 | Integrators implement fail-closed FALLBACK handling | Soft-pass would reintroduce fraud |
| A4 | Strategy B progresses in parallel for residual OTP | OTP path remains interceptable |
| A5 | Pilot integrators include ≥1 bank and ≥1 e-Gov portal | Cross-sector evidence for scale decision |

---

## 0.7 Evidence Standards and Statistical Labelling

This proposal mixes **peer and industry research**, **regulator and development-agency publications**, and **deployment projections**. Every quantitative table in Chapters 2 and 2b marks each row:

| Label | Reader interpretation |
|-------|----------------------|
| **cited** | Use for external communication only with source citation intact |
| **estimated** | Directionally correct; validate against Ethiopian operator data before budgeting |
| **ILLUSTRATIVE** | Scenario modelling (ROI, pilot KPIs, Ethiopian incident rates where public aggregation absent) |

Restlink does **not** represent *ILLUSTRATIVE* figures as Ethio Telecom management accounts or NBE supervised entity returns. Pilot contracts should replace illustrations with **measured baseline OTP volume, fraud tickets, and SMS unit cost** from each integrator.

---

## 0.8 Confidentiality, Versioning, and Feedback

This document is classified **Confidential — Government & Financial Sector**. Distribution is limited to named stakeholders in Ethio Telecom, MInT, NBE, pilot banks, and authorised Restlink personnel. Technical annexes describing MAP global title allow lists or Resolver IP schemas may be **RESTRICTED** in separate operator-only supplements.

| Version | Date | Change summary |
|---------|------|----------------|
| 1.0 | July 2026 | Initial full proposal assembly; 28-slide deck alignment |

Comments and red-line review should reference **chapter and section numbers** (e.g., §5.3.2) rather than Word page numbers to avoid ambiguity across PDF exports.

---

## 0.9 Summary

This proposal recommends **network-side silent authentication** as digital public infrastructure for Ethiopia: a **Restlink VAS adapter** on **Ethio Telecom** truth sources, **CAMARA-aligned** toward integrators, **GSMA FS.11/19-compliant** toward the core, and **commercially neutral** toward operator SMS revenue. Read **Chapter 1** for the decision summary, **Chapter 2b** for international case evidence, **Chapters 4–6** for engineering truth, and **Chapters 10–11** for partnership and pilot terms. Use the **28-slide deck** for executive sessions and this document for **due diligence and implementation**.

---

*Next chapter: [Chapter 1 — Executive Summary](01_executive_summary.md)*
