# Dual License — Silent Authentication (SAS)

**SPDX-License-Identifier:** `AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

Copyright © 2026 Tran Nhan. All rights reserved.

Silent Authentication (SAS) is released under a **dual-license model**
(MySQL/Sidekiq pattern), both grants held by the copyright owner (Tran Nhan):
one **AGPL-3.0 Community** license and one proprietary **Operator** license.
Pick exactly one of the two options below. They do not stack: holding an
Operator license does not soften or add to the AGPL terms, and using the
software under AGPL grants **no** production or support rights.

## Scope — what this license covers

The grant below covers the **entire tree**, component by component:

| Component | Contents | Covered |
|---|---|---|
| `sas-api` | CAMARA northbound `/verify`, `/session-tuple`, security | yes |
| `sas-entitlement` | TS.43 / Wi-Fi entitlement track | yes |
| `sas-host` | Quarkus app: SLEE bootstrap, RAs, CDR, admin dashboard | yes |
| `sas-jss7-testapp` / `sas-diameter-testapp` | lab SS7 / Diameter simulators | yes |
| `ue-sdk`, `ue-sdk-android`, `ue-sdk-ios`, `ue-sdk-web` | device-side tuple SDKs | yes |
| `harness/`, `docs/`, `slides/`, `proposal/` | gates, design/research docs, decks | yes (see "Spec material" below) |

## Option 1 — AGPL-3.0 (Community)

The **whole silent-authentication tree** — `sas-api`, `sas-entitlement`,
`sas-host`, the UE SDKs, lab/testapp harnesses, `harness/` gates, docs
(`docs/design`, `docs/research`), `slides/` and `proposal/` — is provided under
the **GNU Affero General Public License, version 3 or later**
(AGPL-3.0-or-later). You may copy, modify and redistribute it under the terms of
that license, which also covers network use (the AGPL "remote network
interaction" clause, §13): **if you run this service for third parties over a
network, you must offer them your corresponding source.**

Full license text: [`LICENSES/AGPL-3.0.txt`](LICENSES/AGPL-3.0.txt) (verbatim GNU copy;
upstream: <https://www.gnu.org/licenses/agpl-3.0.html>). It ships with this tree so the
Community grant is never a dangling link — a derivative must carry that file along.
`LICENSE.md` itself is the dual-license **notice**; the AGPL body governs Option 1.

Community in plain words — and what Community is **not**:

- **No supply agreement implied.** AGPL is a copyright license, not a commercial
  deal: no SLA, no support, no warranty, no trademark rights in "Restlink", no
  license keys, and no production integration package. Running a modified SAS as
  a service additionally triggers the §13 source-offer duty above.
- **Copyleft reaches your derivatives**, including a modified SAS serving
  subscribers or banks over a network. If that is unacceptable, take Option 2.
- **UE SDKs are AGPL as well** (owner decision D1 — one tree, one grant).
  Shipping a modified SDK inside an app triggers the usual source-offer duty; a
  permissive client license (Apache-2.0/MIT) is available only via Option 2.

### Spec material (not relicensed)

`docs/research/` paraphrases and quotes **3GPP, GSMA and CAMARA** documents,
which remain the property of their respective owners and are reproduced for
interoperability and reference. AGPL covers **our** text and code, not
third-party specification content; redistribution must preserve those
attributions (FS.11, TS.29.002, TS.33.501, TS.43, CAMARA NumberVerification, …).

### Third-party dependencies

Dependencies keep their own licenses and fall outside both grants below:

- Mobius **corsac-diameter** (linked by the SAS Diameter S6a/SWx path) — AGPL-3.0
- Quarkus, Log4j2, Jackson, Mobicents jSS7 — Apache-2.0 / LGPL, AGPL-compatible

## Option 2 — Commercial Operator license

Banks, system integrators and operators (Restlink deployments) that need
**production rights**, proprietary/private builds, or support must obtain a
**commercial Operator license** from the copyright owner. Terms are agreed per
deployment (per-verify / node / year), enforced by license key, and include
L1/L2 SLA, training and integration engineering. Contact through Restlink.

Option 2 exists precisely for users for whom AGPL is a blocker, and typically
grants (subject to the signed offer):

- the right to **deploy in production** without copyleft on your own code;
- **private / proprietary builds and modifications** you may keep closed;
- a **permissive UE SDK** option for mobile apps that cannot carry AGPL;
- **support** — L1/L2 SLA, security advisories, integration engineering,
  training, and the pilot API contract for the operator's banks;
- permission to use the Restlink product name, if agreed.

An Operator license **never** allows reselling or redistributing this software
as your own product unless the signed offer explicitly says so.

## License decisions (owner)

- **D1** — open everything: single all-AGPL tree, including the SAS host app.
- **D2** — micro-jainslee dual-licensed (AGPL/commercial); in-house, owner-held.
- **D3** — Mobius corsac handled by direct relationship — no open blocker.

## Warranty

Unless required by applicable law, the software is provided "AS IS", WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, express or implied; neither the
copyright holder nor any contributor is liable for any damages arising from
use of this software.