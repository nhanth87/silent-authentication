# SAS jSS7 TestApp — simulated home HLR (P2 item #9)

Proves **live SS7 signalling** for the Silent Auth SAS MAP verifier: a
simulated home HLR answers the exact dialogs `Jss7MapVerifierBackend` opens —
PSI (`provideSubscriberInfo` v3) and SAI (`sendAuthenticationInfo` v3), never
ATI — over a real jSS7 (coral-valley 9.2.8-j25) M3UA/SCCP/TCAP/MAP stack on
loopback SCTP, plus an automated live-loop test that runs both stacks in one
JVM.

FS.11-clean by construction:

| Inbound op | Behaviour |
|------------|-----------|
| `provideSubscriberInfo` | ReturnResultLast: `subscriberState=assumedIdle` + `locationInformation` (LAI 636-01-100) while attached; detached ⇒ `returnError(systemFailure)` (TS 29.002 has no `absentSubscriber` in the PSI error list — systemFailure maps cleanly) |
| `sendAuthenticationInfo` | N fabricated triplets (RAND/SRES/Kc = random 16/4/8 bytes); `vectors=0` ⇒ `returnError(systemFailure)` |
| `anyTimeInterrogation` | logged then dropped silently — FS.11 Cat 1 demo, **never answered** |

## Layout

```
src/main/java/et/restlink/hlrsim/
├── Main.java               entry point (--listen-port/--peer-port/--http-port)
├── HlrSimulator.java       jSS7 SERVER-side stack + MAPServiceMobilityListener
├── SimState.java           {attached:bool, vectors:int} control state
└── web/                    JDK HttpServer control API (:8087) + minimal JSON
src/test/java/et/restlink/hlrsim/
├── LiveLoopTest.java       client↔server over loopback SCTP, in-process
└── LiveMapClient.java      replica of the SAS verifier PSI/SAI/ATI dialogs
```

Server wiring mirrors the coral-valley `map/load` harness `ss7-server.json`
(`map/load/src/main/java/org/restcomm/protocols/ss7/map/load/ussd/Server.java`
pattern): SCTP link `type=server`, M3UA AS with `functionality=ipsp,
ipsp=server, routingContext=0` (matching the SAS client's
`functionality=as, ipsp=client, routingContext=0` from `ss7-sas.json`), SCCP
point code 2 serving SSN 6, bounded TCAP timers.

## Run

```bash
cd sas-jss7-testapp
JAVA_HOME=$HOME/.local/share/mise/installs/java/zulu-25 /usr/bin/mvn -q clean package

# defaults: SCTP listen 127.0.0.1:2906 (SAS dials from :2905), HTTP :8087
java -jar target/sas-jss7-testapp.jar \
    [--host 127.0.0.1] [--listen-port 2906] [--peer-port 2905] [--http-port 8087]
```

Control API:

```bash
curl http://127.0.0.1:8087/health
curl http://127.0.0.1:8087/state                      # {"attached":true,"vectors":1}
curl -X POST -d '{"attached":false}' http://127.0.0.1:8087/state   # detach
curl -X POST -d '{"vectors":0}' http://127.0.0.1:8087/state        # starve SAI
curl http://127.0.0.1:8087/messages                   # ring buffer of MAP traffic
```

## Automated live-loop test

Builds BOTH stacks in-process on ephemeral loopback ports and asserts:

1. PSI returns subscriberState + locationInformation within 2000 ms;
2. SAI returns exactly 1 triplet (RAND/SRES/Kc sizes 16/4/8) within 2000 ms;
3. ATI gets **no answer within 1500 ms**, dialog aborts cleanly;
4. detached ⇒ error component ⇒ client fails closed;
5. vectors=0 ⇒ error component ⇒ client fails closed.

```bash
cd sas-jss7-testapp
JAVA_HOME=$HOME/.local/share/mise/installs/java/zulu-25 /usr/bin/mvn clean test
```

## Run the REAL SAS against it

Terminal 1 — start the simulated HLR (defaults already match `ss7-sas.json`):

```bash
java -jar sas-jss7-testapp/target/sas-jss7-testapp.jar     # SCTP :2906, HTTP :8087
```

Terminal 2 — point the SAS at it (2G/3G path uses the MAP RA):

```bash
cd sas
JAVA_HOME=$HOME/.local/share/mise/installs/java/zulu-25 /usr/bin/mvn quarkus:dev \
    -Dsas.transport.map=jss7 \
    -Dsas.transport.jss7.config=sas/src/main/resources/ss7-sas.json
# ss7-sas.json already wires the ASP 127.0.0.1:2905 -> 127.0.0.1:2906 and
# defaults hlr-gt/local-gt to 251911000000 / 251911999999.
```

Drive a verify through the SAS (note: **no `X-Sas-Access-Tech` header** — the
2G/3G path selects the MAP transport; with `sas.transport.map=memory` the
in-memory backend would be used instead, so leave it unset or set it to a
2G/3G value):

```bash
curl -s -X POST http://localhost:8084/v1/verify \
    -H 'Content-Type: application/json' \
    -d '{"hashedPhoneNumber":"<sha256 of +251...>","accessTech":{"rat":"GERAN"}}'
```

Watch the signalling land on the simulator: `curl
http://127.0.0.1:8087/messages` shows each inbound PSI/SAI answered
(ReturnResultLast) and any stray ATI DROPPED. Detach or starve vectors via
`POST /state` and the SAS must fall back (fail-closed).

Selection recap: `sas.transport.map=jss7` picks `Jss7MapVerifierBackend`;
anything else (`memory`) keeps the in-memory backend. If the SAS log shows the
fstack backend failing to load its native library, add `"backend":
"netty_kernel"` to the `sctp` section of your `ss7-sas.json` copy (the
simulator always pins netty_kernel).

### Known SAS one-liners surfaced by this testapp (not patched here)

Proving the loop exposed two latent issues in `Jss7MapVerifierBackend` that a
real SAS run will trip over; both are outside this module's scope:

1. **Service never activated** — `MAPServiceMobilityImpl.createNewDialog`
   refuses to run while `isActivated()` is false, so `start()` must add
   `mapProvider.getMAPServiceMobility().activate();` right after registering
   the listeners. Without it every `/verify` fails with
   `Cannot create MAPDialogMobility because MAPServiceMobility is not
   activated`.
2. **`dialog.abort(null)` cannot encode** — `MAPUserAbortInfoImpl` throws
   `UserSpecificReason must not be null`; use a real
   `MAPUserAbortChoiceImpl` with `setUserSpecificReason()`.

Also note the bring-up race: if the SAS ASP dials in before the simulator's
M3UA AS is active, the first PSI/SAI gets `No AS found for routing message`
and fails closed (FALLBACK) — subsequent requests succeed once both sides are
up. The live-loop test and the control API `/messages` view make this visible.

## Notes

- Both stacks persist XML state files into CWD; re-runs are idempotent (the
  builder drops reloaded resources before re-adding). The test cleans up after
  itself; `.gitignore` covers manual-run leftovers.
- The simulator pins `"backend": "netty_kernel"` so no DPDK/fstack native
  library is needed on lab hosts.

## License

Dual-licensed — **pick exactly one** (full terms: [`LICENSE.md`](../LICENSE.md)).

`SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Silent-Auth-Operator-1.0`

| Edition | Terms |
|---|---|
| **Community** | **AGPL-3.0-or-later** — free to use, modify and redistribute; copyleft, and AGPL §13 also bites when you host it as a service. No SLA, no support, no warranty, no trademark rights in Restlink. |
| **Operator** | **Proprietary, owner-held.** Production rights without copyleft, signed builds + license key, security advisories, L1/L2 SLA, training and integration engineering. Terms per deployment via Restlink. |

The lab / dev profile of this component accepts plain HTTP and mock transports on purpose. Neither license changes that: **do not ship it** — see `harness/preflight_prod.py`.

Copyright © 2026 Tran Nhan.
