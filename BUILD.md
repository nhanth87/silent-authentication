# Building Silent Authentication (CAMARA Number Verification v2)

This guide builds every deliverable in this repository from a clean checkout:
the two server binaries, the lab simulators, the device SDKs (Android, iOS, Web)
and the customer-backend SDK. It also covers the verification runs that prove a
build works. Commands run from the repository root unless stated otherwise.

| Deliverable | Directory | Output |
|---|---|---|
| sas-camara: CAMARA API gateway / OAuth AS (:8085) | `sas-host/` | `dist/` (Quarkus fast-jar) |
| sas-ecs: entitlement server, core-network adapter (:8095) | `sas-ecs-host/` | `dist-ecs/` (Quarkus fast-jar) |
| Lab HLR simulator (MAP over SCTP :2906) | `sas-jss7-testapp/` | `target/sas-jss7-testapp.jar` |
| Lab HSS / AAA / PCRF simulator (Diameter :3868) | `sas-diameter-testapp/` | `target/sas-diameter-testapp.jar` |
| Android SDK (Java 8 bytecode, minSdk 24) | `ue-sdk-android/` | `target/ue-sdk-android-0.1.0-SNAPSHOT.jar` |
| iOS SDK (Swift Package `UESDK`, iOS 14+) | `ue-sdk-ios/` | Swift package (SPM) |
| Web SDK (ES2020, zero deps) | `ue-sdk-web/` | npm package `@restlink/ue-sdk-web` |
| JVM device SDK (session tuple) | `ue-sdk/` | `target/ue-sdk-0.1.0-SNAPSHOT.jar` |
| Customer-backend SDK (Java 25) | `bank-backend-sdk/` | `target/bank-backend-sdk-0.1.0-SNAPSHOT.jar` |

## 1. Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK | **25** (Azul Zulu 25 via [mise](https://mise.jdx.dev): `mise install`, see `mise.toml`) | every Java module. Never use an older JDK or lower `maven.compiler.release` |
| Maven | 3.9.9 | Java builds |
| Node.js | ≥ 18 | Web SDK tests, E2E scripts |
| Xcode + Swift | Swift 5.9+ (macOS) | iOS SDK build, device runs, App Clip |
| Docker | any | iOS SDK tests on Linux (`swift:5.10-jammy`) |
| Python | 3.10+ | harness gates, preflight |
| Linux tools | `unshare`, `ip`, `curl`, `jq`, `tcpdump` (optional) | E2E/conformance scripts (private user + network namespace, no root) |
| SCTP | kernel lksctp (`modprobe sctp`) | SS7 / Diameter lab transports (SCTP only, never TCP) |

```bash
mise install                         # installs zulu-25 and Maven from mise.toml
export JAVA_HOME="$(mise where java)"; export PATH="$JAVA_HOME/bin:$PATH"
java -version                        # must print 25
```

## 2. Upstream dependencies (build once, install into `~/.m2`)

The server side depends on three artifacts that are not on Maven Central in the
versions used here. Build and install them first. Every `mvn -o` (offline)
command below assumes they are in the local repository.

| Artifact | Version | Source | Build |
|---|---|---|---|
| micro-jainslee (`com.microjainslee:*`) | `1.2.0-SNAPSHOT` | `https://github.com/RestComm/jain-slee.git`, branch `micro-jainslee-2` | `mvn -DskipTests install` |
| jSS7 (`org.restcomm.protocols.ss7:*`) | `9.2.8-j25` | `https://github.com/digicom-et/jss7.git`, branch `j25` | `mvn -DskipTests install` |
| corsac-diameter (Mobius) | `10.0.0-41-SNAPSHOT` | `https://github.com/mobius-software-ltd/corsac-diameter.git` (local fork) | `mvn -DskipTests install` |

The first build of this repository must run **online** (without `-o`) so Maven can
fetch Quarkus, Nimbus, JUnit and the plugins. Every later build can be offline.

> Licensing: jSS7 and corsac-diameter are AGPL-3.0 components of third parties.
> They are linked by sas-ecs and the simulators only. sas-camara, the device SDKs
> and `bank-backend-sdk` do not depend on them (gate H25 and
> `scripts/check-dependency-boundary.sh` enforce this). See `LICENSE.md`.

## 3. Server binaries

```bash
mvn test                                  # 9 modules; first run online, later: mvn -o test
./scripts/package-dist.sh all             # → dist/ (sas-camara) and dist-ecs/ (sas-ecs)
scripts/check-dependency-boundary.sh dist dist-ecs   # both lines must print OK
```

Run the lab profile (plain HTTP, in-memory/H2, lab evidence; **never ship it**):

```bash
(cd dist-ecs && ./run.sh)                 # sas-ecs on :8095
(cd dist && ./run.sh)                     # sas-camara on :8085
curl -s http://127.0.0.1:8085/camara/health
curl -s http://127.0.0.1:8085/.well-known/openid-configuration | jq .
```

Environment variables and `-D` options (through `SAS_JAVA_OPTS`) override the
dist's `configs/application.properties`. The settings that matter for NV v2:

| Property | Lab default | Meaning |
|---|---|---|
| `sas.oauth.clients-json` | — | registered clients (`private_key_jwt` + `jwks`, grant types, redirect URIs, scopes, purposes, optional `dpop_bound_access_tokens`) |
| `sas.oauth.require-client-auth` | false | `true` = `private_key_jwt` mandatory (prod) |
| `sas.security.token-validation-enabled` | false | `true` = ES256 access tokens verified at every API (prod) |
| `sas.security.accept-hs256` | true | `false` in prod (PRO-33) |
| `sas.security.trust-amr-header` | false | never `true` in prod (PRO-37) |
| `sas.oauth.authorize.trust-src-header` | true (lab) | `false` = identify the subscriber from the connection source only |
| `sas.oauth.access-token-ttl-seconds` | 300 | clamped to ≤ 300 |
| `sas.oauth.dpop.enabled` | true | DPoP (RFC 9449) at `/token` and on every API |

Production profile: `QUARKUS_PROFILE=prod dist/run.sh` with the environment
variables listed in `sas-host/src/main/resources/application-prod.properties`
(TLS 1.2+, mTLS, PostgreSQL, real transports). Check it with
`python3 harness/preflight_prod.py --service all` before any boot.

Lab simulators (standalone builds):

```bash
(cd sas-jss7-testapp && mvn -o package -DskipTests)
(cd sas-diameter-testapp && mvn -o package)
java --add-modules jdk.sctp -jar sas-jss7-testapp/target/sas-jss7-testapp.jar --listen-port 2906 --peer-port 2907 --http-port 8087
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar
```

Step-by-step test flows with expected outputs: `docs/test/testflow.en.md` §00.

## 4. Android SDK

```bash
cd ue-sdk-android
mvn -o package                            # tests + target/ue-sdk-android-0.1.0-SNAPSHOT.jar
```

The module compiles with plain `javac --release 8` and **without `android.jar`**:

- Framework calls are made by reflection.
- The three classes `requestNetwork()` needs (`ConnectivityManager`, `Network`,
  `NetworkRequest`) are **compile-only stubs** in `src/stubs/java`.
- The jar excludes `android/**`, so on a device the real framework classes are
  used. Verify with `unzip -l target/*.jar | grep android/` (no output).

Use it in an Android app (Gradle):

```kotlin
// app/build.gradle.kts
android { defaultConfig { minSdk = 24 } }
dependencies { implementation(files("libs/ue-sdk-android-0.1.0-SNAPSHOT.jar")) }
```

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />  <!-- requestNetwork(CELLULAR) -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />      <!-- optional: LTE/NR radio type -->
```

Call `NumberVerification.builder(context).build().authorize(url)` off the main
thread. Usage: `ue-sdk-android/README.md`.

## 5. iOS SDK (and App Clip)

On macOS:

```bash
cd ue-sdk-ios
swift build && swift test                  # compiles CellularHTTPClient (Network.framework) too
```

On Linux (CI), Network.framework and CoreTelephony are compiled out
(`#if canImport`). The rest builds and tests in Docker:

```bash
cd ue-sdk-ios
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/w -w /w swift:5.10-jammy swift test
# expected: 57 tests, 3 skipped (Apple-specific JSONEncoder key order), 0 failures
```

Add it to an Xcode project:
1. **File → Add Package Dependencies…**
2. Choose the repository (or **Add Local…** for `ue-sdk-ios/`).
3. Select product **UESDK**.
4. Link it into the app target and, for an App Clip, into the App Clip target. The
   SDK uses only Foundation, Network and CoreTelephony, all allowed in App Clips,
   and is a few hundred KB.

App Clip set-up on the bank's side:
- App Clip target with the `appclips:<host>` Associated Domains entitlement.
- `apple-app-site-association` on that host.
- App Clip experience in App Store Connect.
- Read the invocation with `AppClipInvocation.parse(activity, allowedHosts:)`.

See `ue-sdk-ios/README.md` § App Clip.

## 6. Web SDK

```bash
cd ue-sdk-web
node --test                                # 43 tests
npm pack                                   # → restlink-ue-sdk-web-0.3.0.tgz
```

Entry points: `@restlink/ue-sdk-web` (session tuple), `/number-verification`
(silent `prompt=none` authorization, callback parsing, Digital Credentials API),
`/qr-session` (desktop QR hand-off), `/hand-off` (QR on desktop, App Clip / App
Link on the phone). TypeScript declarations ship next to each module.

## 7. Customer-backend SDK

```bash
cd bank-backend-sdk
mvn -o install                             # tests + installs et.restlink:bank-backend-sdk
```

Generate a client key once, store the private JWK in the bank's vault, and register
the public JWKS with the operator:

```java
Jose key = Jose.generate("bank-nv-1");
vault.store(key.privateJwk());             // never leaves the bank
operator.register(key.publicJwks());       // token_endpoint_auth_method = private_key_jwt
```

Usage: `bank-backend-sdk/README.md`.

## 8. Verify a build

Order matters: unit tests, then gates, then the packaged end-to-end runs.

```bash
mvn -o test                                                   # all server modules
(cd ue-sdk && mvn -o test) && (cd ue-sdk-android && mvn -o test) && (cd bank-backend-sdk && mvn -o test)
(cd ue-sdk-web && node --test)
python3 harness/run_hardness.py                               # 38/38 gates
python3 harness/run_hardness.py --mutations                   # mutation self-tests
python3 harness/preflight_prod.py --selftest                  # 47/47 scenarios
./scripts/package-dist.sh all
./scripts/camara-nv2-conformance.sh                           # CAMARA NV v2 Phase 2: 50 checks
./scripts/sdk-nv2-e2e.sh                                      # Android + Web SDK against the dists
./scripts/layered-e2e.sh dist dist-ecs                        # MAP/Diameter core paths, pcap
```

The two NV v2 scripts start both dists inside a private user and network namespace.
`lo` carries `10.20.30.40`, the address the lab resolver binds to `+251911111111`,
so the "cellular" request really comes from that source address. They need no root
and leave nothing running.

Expected results at the time of writing (2026-10-14):

| Run | Result |
|---|---|
| `mvn -o test` | 788 tests, 0 failures |
| gates / mutations / preflight | 38/38 · 29/29 · 47/47 |
| conformance | 50/50 |
| SDK E2E | Android ALL OK, Web ALL OK |

## 9. What not to commit

`dist/`, `dist-ecs/`, `*/target/`, `.build/`, `node_modules/`, `sas-host/data/`, CDRs,
pcaps, `slides/` and anything under a scratch directory are local artifacts (see
`.gitignore`). Commits are authored by **nhanth87 / Tran Nhan** only. The repository
hooks reject AI or bot attribution, and `--no-verify` is not allowed.

## 10. Further reading

| Topic | Document |
|---|---|
| NV v2 Phase 1–2 (server, DPoP, amr, conformance) | `docs/design/camara-nv2-phase1-2.md` |
| Device SDKs, platform matrix, App Clip | `docs/design/camara-nv2-device-sdk.md` |
| Two-binary layered architecture | `docs/design/sas-layered-architecture.md` |
| Test flows with expected outputs | `docs/test/testflow.en.md` |
| Gates and preflight | `docs/design/hardness.md` |
