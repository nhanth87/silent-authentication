# Chapter 12 — Appendices

**Restlink Silent Authentication for Government & Banks (Ethiopia)**  
**Document:** Proposal §12  
**Classification:** Commercial-in-Confidence  
**Version:** 1.0 (Draft)  
**Date:** 2026-07-20

---

## Appendix A — Glossary

| Term | Expansion | Definition in this proposal |
|------|-----------|----------------------------|
| **ATI** | AnyTimeInterrogation | SS7 MAP operation querying subscriber state and location from the HLR. **FS.11 Category 1 on interconnect** — permitted **intra-network only** in Restlink deployment. |
| **PSI** | ProvideSubscriberInfo | SS7 MAP operation returning subscriber state, location, and related data. FS.11 Category 2.1 — primary 2G/3G verifier message in Phase 1. |
| **SAI** | SendAuthenticationInfo | SS7 MAP operation retrieving authentication vectors; used to infer SIM / IMSI change freshness for swap detection. FS.11 Category 3.2. |
| **HLR** | Home Location Register | 2G/3G subscriber database; authoritative for CS/PS subscription state. Queried by MAP Verifier inside operator network. |
| **HSS** | Home Subscriber Server | LTE/EPC and 5G NSA subscriber database; functional successor to HLR. Queried via Diameter S6a in Phase 2. |
| **PGW** | Packet Data Network Gateway | 4G user-plane gateway; maintains bearer session binding (IP ↔ IMSI/MSISDN). Primary Resolver data source for IP-match silent auth. |
| **CGNAT** | Carrier-Grade NAT | IPv4 address sharing; multiple subscribers may share one public IP. Requires **IP + source port + timestamp** for Resolver disambiguation. |
| **CAMARA** | — | Linux Foundation / GSMA telco API project. Defines Number Verification, SIM Swap, OTP SMS, and related northbound APIs. Restlink exposes CAMARA-shaped adapters over SAS. |
| **FASG** | Fraud and Security Group | GSMA working group publishing FS-series interconnect security PRDs (FS.07, FS.11, FS.19, etc.). |
| **SAS** | Silent Authentication Service | Restlink component: Resolver + Verifier + Policy engine; implements `/verify` and CAMARA adapters. |
| **NV / NV2** | Number Verification (v2) | CAMARA API family for verifying that a device session matches a phone number. NV2 extends coverage and assurance semantics. |
| **TS.43** | — | GSMA Technical Specification — Service Entitlement Configuration. Defines SIM-based silent auth via **EAP-AKA** (works on Wi-Fi). Phase 3. |
| **MAP** | Mobile Application Part | SS7 application layer for 2G/3G network signalling (mobility, SMS, supplementary services). |
| **Diameter** | — | AAA protocol used in LTE/5G core (e.g. S6a between MME/AMF and HSS/UDM). |
| **S6a** | — | Diameter interface between MME and HSS; carries ULR for location updates (Sh carries UDR for read-only data). |
| **Sh UDR / SNR** | User Data Request / Subscribe-Notifications Request | Read-only subscriber-data read used for 4G/5G SIM-swap freshness. |
| **MSISDN** | Mobile Station International Subscriber Directory Number | Public telephone number (E.164) associated with SIM subscription. |
| **IMSI** | International Mobile Subscriber Identity | Private SIM identifier; never returned to mobile app — bank backend only. |
| **OTP** | One-Time Password | SMS-delivered code; **fallback** when silent path unavailable. Billed via Ethio Telecom SMSC. |
| **ATO** | Account Takeover | Fraud class where attacker gains control of victim account; target risk metric for ROI modelling. |
| **VAS** | Value-Added Service | Operator-hosted or operator-partnered service layer above core network. Restlink positions as VAS adapter. |
| **FALLBACK** | — | SAS policy outcome when evidence insufficient — triggers step-up MFA (OTP, Passkey, TOTP). Fail-closed; never soft-pass. |
| **DEA** | Diameter Edge Agent | Interconnect border element filtering Diameter per FS.19; operator-owned (Strategy B — protect residual OTP). |
| **SEPP** | Security Edge Protection Proxy | 5G interconnect border node (N32); operator-owned per FS.36. |
| **Home Routing** | SMS Home Routing | SMS delivery architecture keeping MT SMS within home operator network; mitigates SS7 SRI-SM intercept (FS.11). |
| **Open Gateway** | GSMA Open Gateway | Programme aligning operator APIs (CAMARA) for developer access. Ethiopia positioning in §10. |
| **reqId** | Request identifier | Idempotency key on `/verify`; deduplicates client retries. |

---

## Appendix B — jSS7 class reference (coral-valley)

Reference implementation path prefix:

`worktrees/jSS7/coral-valley/jSS7/map/map-impl/src/main/java/org/restcomm/protocols/ss7/map/`

### B.1 Mobility / subscriber information (Silent Auth Verifier — Phase 1)

| Class | Package suffix | MAP operation | Role in SAS |
|-------|----------------|---------------|-------------|
| `AnyTimeInterrogationRequestImpl` | `service/mobility/subscriberInformation/` | ATI | Intra-network subscriber interrogation (secondary to PSI) |
| `AnyTimeInterrogationResponseImpl` | `service/mobility/subscriberInformation/` | ATI response | Parse subscriberInfo, location, state |
| `ProvideSubscriberInfoRequestImpl` | `service/mobility/subscriberInformation/` | PSI | **Primary** 2G/3G reachability + location query |
| `ProvideSubscriberInfoResponseImpl` | `service/mobility/subscriberInformation/` | PSI response | Subscriber state for assurance scoring |
| `SendAuthenticationInfoRequestImpl` | `service/mobility/authentication/` | SAI | SIM-swap freshness / IMSI change signal |
| `SendAuthenticationInfoResponseImpl` | `service/mobility/authentication/` | SAI response | Auth vector metadata for swap heuristics |
| `MAPServiceMobilityImpl` | `service/mobility/` | Mobility service | Service entry for mobility operations |
| `MAPDialogMobilityImpl` | `dialog/` | TCAP dialog | Dialog lifecycle; timeout → `abort()` |

**Dialog anchor:** `MAPProviderImpl` → `MAPServiceMobility` — one dialog per verifier stage; 2 s TC timer.

### B.2 SMS service (Strategy B — residual OTP protection; operator / future)

Path: `service/sms/`

| Class | MAP operation | Role |
|-------|---------------|------|
| `SendRoutingInfoForSMRequestImpl` | SRI-SM | Home Routing probe; IMSI + correlationID fields |
| `SendRoutingInfoForSMResponseImpl` | SRI-SM response | Router address substitution |
| `CorrelationIDImpl` | Correlation ID IE | MT Home Routing correlation |
| `LocationInfoWithLMSIImpl` | Location + LMSI | Hardened correlation key |
| `MtForwardShortMessageRequestImpl` | MT-FSM | Validate correlated MT delivery |
| `MoForwardShortMessageRequestImpl` | MO-FSM | MO origin checks |
| `InformServiceCentreRequestImpl` | InformSC | SMSC signalling scrutiny |
| `MAPServiceSmsImpl` | SMS service | SMS MAP service hook point |
| `MAPDialogSmsImpl` | SMS dialog | SMS TCAP dialog management |

### B.3 TCAP layer (Double MAP mitigation)

| Component | Package | Role |
|-----------|---------|------|
| TCAP dialog inspection | `org/restcomm/protocols/ss7/tcap` | Detect multiple MAP components in `TCBegin` (FS.11 CVD-2018-0015) |

### B.4 Diameter (Phase 2 — planned mirror of MAP verifier)

| Module | Status | S6a messages |
|--------|--------|--------------|
| jDiameter client | **Open item** | ULR/ULA (S6a) + UDR/SNR (Sh) |
| Parity requirement | Match MAP assurance outputs | subscriberReachable, authInfoAge |

---

## Appendix C — Open items

Items below require resolution before or during the phase indicated. Status as of proposal v1.0.

| ID | Item | Phase | Owner | Notes |
|----|------|-------|-------|-------|
| OI-01 | Resolver source: PGW RADIUS accounting vs PCRF Gx/Sd vs CGNAT flow log | 1 | Ethio Telecom + Restlink | Blocks WP1.2 |
| OI-02 | jDiameter S6a client module (mirror jSS7 MAP verifier) | 2 | Restlink engineering | See Appendix B.4 |
| OI-03 | CAMARA Number Verification adapter: SAS `/verify` ↔ NV contract mapping | 1 | Restlink engineering | OpenAPI 3.1 draft in integration pack |
| OI-04 | Assurance weights + per-risk thresholds (e-Gov login vs bank transfer) | 1–2 | Restlink + tenant risk | Config-driven; no code deploy per tune |
| OI-05 | Shared identity-policy / rate-limit store (SAS + signalling FW) | 2 | Ethio Telecom + Restlink | Prevent legacy OTP abuse when silent blocked |
| OI-06 | TS.43 entitlement server hosting model (operator vs co-manage) | 3 | Ethio Telecom product | Feasibility gate G3 |
| OI-07 | SMS Router / SS7 FW product confirmation for Strategy B | 1 (parallel) | Ethio Telecom | jSS7 classes Appendix B.2 if COTS unavailable |
| OI-08 | Exact enterprise SMS A2P tariff for ROI refresh | Commercial | Ethio Telecom | Replace ILLUSTRATIVE $0.042/msg |
| OI-09 | NBE supervisory guidance letter for network-assisted auth | 1 | Restlink regulatory | Observer at G0 |
| OI-10 | iOS SDK entitlement / carrier privilege constraints | 3 | Restlink + Apple enterprise | May affect Wi-Fi path UX |
| OI-11 | 5G SA SMSF + SEPP path validation (FS.36) | 3 | Ethio Telecom | SAS unchanged at API layer |
| OI-12 | Disaster recovery site (secondary DC) | 2 | Restlink + operator | RPO/RTO in §11.5 WP-X.5 |
| OI-13 | Pen-test firm and scope (API + MAP injection) | 1 | Joint | Before pilot go-live |
| OI-14 | Fraud analytics baseline (ATO rate per bank) | 1 | Bank tenants | Required for ROI validation |

---

## Appendix D — References

Bibliographic style: Author/Organisation. *Title*. Version/Number, Publisher, Year. [Online]. Available: URL. [Accessed: Date].

### D.1 GSMA Fraud & Security Group (FASG)

GSMA. *FS.11 — SS7 Interconnect Security Monitoring and Firewall Guidelines*. Version 4.0, GSMA Fraud and Security Group, 2018. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/wp-content/uploads/2019/02/FS.11-v4.0.pdf. [Accessed: 20 July 2026].

GSMA. *FS.19 — Diameter Interconnect Security*. Version 2.0, GSMA Fraud and Security Group, 2020. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/ (members access). [Accessed: 20 July 2026].

GSMA. *FS.07 — SS7 and SIGTRAN Network Security*. GSMA Fraud and Security Group, 2017. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/ (members access). [Accessed: 20 July 2026].

GSMA. *FS.21 — Interconnect Signalling Security Recommendations*. GSMA Fraud and Security Group, 2019. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/ (members access). [Accessed: 20 July 2026].

GSMA. *FS.36 — 5G Interconnect Security*. GSMA Fraud and Security Group, 2022. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/ (members access). [Accessed: 20 July 2026].

GSMA. *SG.22 — SMS Firewall Best Practices and Policies*. GSMA Fraud and Security Group, 2018. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/ (members access). [Accessed: 20 July 2026].

GSMA. *FF.09 — SMS Fraud*. GSMA Fraud and Security Group, 2017. [Online]. Available: https://www.gsma.com/solutions-and-impact/technologies/security/ (members access). [Accessed: 20 July 2026].

### D.2 GSMA identity & Open Gateway

GSMA. *TS.43 — Service Entitlement Configuration*. Version 12.0, GSMA, 2024. [Online]. Available: https://www.gsma.com/solutions-and-impact/industry-services/device-services/service-entitlement-configuration/. [Accessed: 20 July 2026].

GSMA. *Open Gateway*. GSMA, ongoing. [Online]. Available: https://www.gsma.com/solutions-and-impact/gsma-open-gateway/. [Accessed: 20 July 2026].

Meta Platforms, Inc. and Mobile Operators. *The Promise of Silent Authentication APIs*. White paper, GSMA/Meta, 2026. [Online]. Available: https://www.gsma.com/ (industry circulation). [Accessed: 20 July 2026].

### D.3 CAMARA

Linux Foundation. *CAMARA Project*. CAMARA, ongoing. [Online]. Available: https://camaraproject.org/. [Accessed: 20 July 2026].

CAMARA. *Number Verification API*. Release version per CAMARA catalogue, Linux Foundation, 2025–2026. [Online]. Available: https://github.com/camaraproject/NumberVerification. [Accessed: 20 July 2026].

CAMARA. *SIM Swap API*. Release version per CAMARA catalogue, Linux Foundation, 2025–2026. [Online]. Available: https://github.com/camaraproject/SIMSwap. [Accessed: 20 July 2026].

### D.4 3GPP

3GPP. *Technical Specification 23.040 — Technical realization of the Short Message Service (SMS)*. Release 17, 3GPP, 2022. [Online]. Available: https://www.3gpp.org/ftp/Specs/archive/23_series/23.040/. [Accessed: 20 July 2026].

3GPP. *Technical Specification 29.337 — Diameter based SMS procedures*. Release 17, 3GPP, 2022. [Online]. Available: https://www.3gpp.org/ftp/Specs/archive/29_series/29.337/. [Accessed: 20 July 2026].

3GPP. *Technical Specification 33.501 — Security architecture and procedures for 5G System*. Release 17, 3GPP, 2022. [Online]. Available: https://www.3gpp.org/ftp/Specs/archive/33_series/33.501/. [Accessed: 20 July 2026].

### D.5 ITU

International Telecommunication Union. *Recommendation E.164 — The international public telecommunication numbering plan*. ITU-T, latest revision. [Online]. Available: https://www.itu.int/rec/T-REC-E.164. [Accessed: 20 July 2026].

International Telecommunication Union. *Recommendation Q.767 — Application of the ISDN user part of CCITT signalling System No. 7 for international ISDN interconnections*. ITU-T (SS7 ISUP context). [Online]. Available: https://www.itu.int/rec/T-REC-Q.767. [Accessed: 20 July 2026].

### D.6 International development & fraud context

United Nations Office on Drugs and Crime (UNODC). *Comprehensive Study on Cybercrime*. UNODC, 2013 (updated materials ongoing). [Online]. Available: https://www.unodc.org/unodc/en/organized-crime/cybercrime.html. [Accessed: 20 July 2026].

World Bank. *Digital Ethiopia 2025*. World Bank Group, 2019. [Online]. Available: https://documents.worldbank.org/en/publication/documents-reports/documentdetail/612981551016992000/digital-ethiopia-2025. [Accessed: 20 July 2026].

World Bank. *Global Financial Inclusion Database (Findex)*. World Bank, 2024 edition. [Online]. Available: https://worldbank.org/en/publication/globalfindex. [Accessed: 20 July 2026].

European Union Agency for Cybersecurity (ENISA). *Signalling Security in Telecom SS7/Diameter/5G Networks*. ENISA, 2021. [Online]. Available: https://www.enisa.europa.eu/publications/signalling-security-telecom-ss7-diameter-5g-networks. [Accessed: 20 July 2026].

### D.7 Internal design artefacts (this programme)

Restlink. *Silent Authentication — Banking Flow Design*. Repository: `docs/design/silent-auth-flow.md`, 2026.

Restlink. *Unified Identity & SMS Security Architecture*. Repository: `docs/design/unified-identity-sms-security-architecture.md`, 2026.

Restlink. *GSMA FASG Document Index*. Repository: `docs/research/gsma-fs-index.md`, 2026.

Restlink. *SMS Channel Protection Research*. Repository: `docs/research/sms-channel-protection.md`, 2026.

---

## Appendix E — Document control

### E.1 Revision history

| Version | Date | Author | Summary of changes |
|---------|------|--------|-------------------|
| 0.1 | 2026-07-18 | Restlink | Initial outline from Supermemory seed |
| 0.5 | 2026-07-19 | Restlink | Commercial model + roadmap draft |
| **1.0** | **2026-07-20** | **Restlink** | **Proposal release: §10–§12 for government + bank submission** |

### E.2 Approval record (draft)

| Role | Name | Signature | Date |
|------|------|-----------|------|
| Author | Restlink Proposal Team | — | 2026-07-20 |
| Technical reviewer | — | Pending | — |
| Commercial approver | — | Pending | — |
| Ethio Telecom counterpart | — | Pending | — |

### E.3 Distribution list

| Recipient | Copy type | Purpose |
|-----------|-----------|---------|
| Ethio Telecom — Enterprise / VAS | Controlled | Partnership evaluation |
| National Bank of Ethiopia (observer) | Controlled | Supervisory awareness |
| Ministry of Innovation & Technology | Controlled | e-Gov alignment |
| Pilot commercial bank(s) | Controlled | Integration planning |
| Restlink executive | Master | Authorisation |

### E.4 Related documents

| Doc ID | Title | Location |
|--------|-------|----------|
| RESTLINK-SA-01 | Executive summary (§1–§3) | `proposal/chapters/` |
| RESTLINK-SA-10 | Commercial model | `proposal/chapters/10_commercial_model.md` |
| RESTLINK-SA-11 | Implementation roadmap | `proposal/chapters/11_implementation_roadmap.md` |
| RESTLINK-SA-12 | Appendices (this document) | `proposal/chapters/12_appendices.md` |
| RESTLINK-SA-ROI | Illustrative ROI dataset | `proposal/assets/roi_illustrative.json` |
| RESTLINK-SA-DES | Technical design pack | `docs/design/` |
| RESTLINK-SA-DECK | Mix v3 presentation | `slides/Restlink_Silent_Auth_Mix_v3.pptx` |

### E.5 Classification & retention

- **Classification:** Commercial-in-Confidence until executed MSAs permit wider distribution.
- **Retention:** 7 years from last contract activity or as required by Ethiopian commercial law.
- **ILLUSTRATIVE data:** All financial projections in §10 and `roi_illustrative.json` must be re-validated against signed tariffs before binding bids.

### E.6 Conventions

| Convention | Meaning |
|------------|---------|
| **ILLUSTRATIVE** | Planning assumption; not a quoted price |
| SHALL / MUST | Normative requirement on Restlink design |
| Operator | Ethio Telecom unless stated otherwise |
| Tenant | Government agency or bank consuming `/verify` |

---

*Previous: [Chapter 11 — Implementation Roadmap](11_implementation_roadmap.md)*
