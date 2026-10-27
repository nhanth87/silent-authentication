# Client SDKs

Every SDK a bank or app embeds to use the Silent Auth SAS. Each one builds and
ships on its own; artifact and package names are independent of this folder.

| Folder | Artifact | Runs in | Build / test |
|---|---|---|---|
| `bank-backend/` | `et.restlink:bank-backend-sdk` | the bank's server (Java 25) | `mvn -o -f sdk/pom.xml test` |
| `ue-android/` | `et.restlink:ue-sdk-android` | Android app (Java 8 bytecode, minSdk 24) | `mvn -o -f sdk/pom.xml test` |
| `ue-ios/` | Swift package `UESDK` | iOS 14+ app, App Clip | `(cd sdk/ue-ios && swift test)` |
| `ue-web/` | `@restlink/ue-sdk-web` | browser (ES2020) | `(cd sdk/ue-web && node --test)` |
| `ue-jvm/` | `et.restlink:ue-sdk` | JVM device (Java 25) | `mvn -o -f sdk/pom.xml test` |

`sdk/pom.xml` builds the three Java SDKs in one run. It is an aggregator, not a
parent, and it is not part of the root `sas-core` build.

## CAMARA v2

A CAMARA login has two halves. The device sends the silent authorization request
(`/authorize`, `prompt=none`) over the mobile network, so the operator can tell
which line it came from. The bank's server owns PKCE and the state, redeems the
code and makes one CAMARA call with the token. The device SDKs do not limit the
scope, so the same device step serves Number Verification and SIM Swap.

| | NV v2 `verify` / `device-phone-number` | SIM Swap v2 `check` / `retrieve-date` |
|---|---|---|
| `bank-backend` | `verify`, `verifyHashed`, `devicePhoneNumber` | `simSwapCheck`, `simSwapRetrieveDate` |
| `ue-android` | device step over `requestNetwork(CELLULAR)` | same device step |
| `ue-ios` | device step over `NWConnection` pinned to `.cellular` | same device step |
| `ue-web` | device step from the browser, SIM credential via the Digital Credentials API | same device step |
| `ue-jvm` | none: session tuple and QR claim only | none |

**Wi-Fi only.** Without a cellular path the device needs a GSMA TS.43 operator
token (`OperatorTokenSource`). An ordinary app cannot run EAP-AKA against the
SIM, so the token has to come from the operator's app, an OS credential service,
or, in the lab, sas-ecs. With no token the SDK fails closed
(`CellularUnavailableException`) and the bank falls back to OTP.

**Tested so far.** Unit tests for all five; `scripts/sdk-nv2-e2e.sh` and
`scripts/camara-nv2-conformance.sh` run ue-android, ue-web and bank-backend
against the packaged servers; `scripts/android-demo-e2e.sh` runs the demo APK on
an emulator. Not yet run: ue-android on a real phone with a real SIM, and the
iOS cellular path on an iPhone (on Linux the `NWConnection` path is compiled out).
