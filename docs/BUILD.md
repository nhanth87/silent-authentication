# Building Silent Authentication (CAMARA Number Verification v2)

This guide builds every deliverable in this repository: the two server binaries,
the lab simulators, the device SDKs (Android, iOS, Web) and the customer-backend
SDK. It also covers the verification runs that prove a build works. Commands run
from the repository root unless stated otherwise.

> **Read §2 first.** The device SDKs and the backend SDK need only Maven Central.
> The server side needs four upstream forks, all pushed (§2).

| Deliverable | Directory | Output | Upstream forks needed |
|---|---|---|---|
| Android SDK (Java 8 bytecode, minSdk 24) | `sdk/ue-android/` | `target/ue-sdk-android-0.1.0-SNAPSHOT.jar` | none |
| iOS SDK (Swift package `UESDK`, iOS 14+) | `sdk/ue-ios/` | Swift package | none |
| Web SDK (ES2020, zero deps) | `sdk/ue-web/` | `restlink-ue-sdk-web-0.3.0.tgz` | none |
| Customer-backend SDK (Java 25) | `sdk/bank-backend/` | `target/bank-backend-sdk-0.1.0-SNAPSHOT.jar` | none |
| **sas-camara**: CAMARA gateway + OAuth AS (:8085) | `sas-host/` | `dist/` | micro-jainslee |
| **sas-ecs**: entitlement server + core-network adapter (:8095) | `sas-ecs-host/` | `dist-ecs/` | micro-jainslee, jSS7, sctp, corsac-diameter |
| Lab HLR simulator (MAP over SCTP :2906) | `sas-jss7-testapp/` | `target/sas-jss7-testapp.jar` | jSS7, sctp |
| Lab HSS/AAA/PCRF simulator (Diameter :3868) | `sas-diameter-testapp/` | `target/sas-diameter-testapp.jar` | corsac-diameter |

## 1. Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK | **25**: Azul Zulu 25 via [mise](https://mise.jdx.dev) (`mise.toml`) | every Java module. Never build with an older JDK or lower `maven.compiler.release` |
| Maven | 3.9.9 (`mise.toml`) | Java builds |
| Node.js | ≥ 18 | Web SDK, E2E scripts |
| Xcode + Swift | Swift 5.9+ on macOS | iOS SDK build, device runs, App Clip |
| Docker | any | iOS SDK tests on Linux (`swift:5.10-jammy`) |
| Python | 3.10+ | harness gates, preflight |
| Linux tools | `unshare`, `ip`, `curl`, `jq` | E2E/conformance scripts (unprivileged user + network namespace) |
| SCTP | kernel lksctp (`sudo modprobe sctp`) | SS7/Diameter lab transports (SCTP only, never TCP) |

```bash
mise install
export JAVA_HOME="$(mise where java)"; export PATH="$JAVA_HOME/bin:$PATH"
java -version          # must print 25
```

## 2. Upstream forks (install into `~/.m2` before the server build)

The server side links four upstream projects, built locally and installed with
`mvn -DskipTests install`. They are not on Maven Central in these versions.

| Artifacts | Version | Repository, branch (commit 2026-10-15) | Visibility |
|---|---|---|---|
| `org.mobicents.protocols.sctp:*` (`sctp-api`, `sctp-core`, `sctp-impl`, `sctp-backend-fstack`) | `2.27.32` | `github.com/nhanth87/sctp` or `digicom-et/sctp`, branch **`master`** (`d086c0f`) | nhanth87 public, digicom-et private |
| `org.restcomm.protocols.ss7:*` + `org.mobicents.protocols.asn:asn` (jSS7, includes `asn/`) | `9.2.8-j25` | `github.com/digicom-et/jss7` or `nhanth87/jss7`, branch **`j25`** | digicom-et private, nhanth87 public |
| `com.microjainslee:*` (micro-jainslee) | `1.2.0-SNAPSHOT` | `github.com/nhanth87/jain-slee` or `digicom-et/jain-slee`, branch **`major-lotus`** | digicom-et private |
| `com.mobius-software.protocols.diameter:*` (corsac-diameter fork) | `10.0.0-41-SNAPSHOT` | `github.com/nhanth87/corsac-diameter`, branch **`main`** (`2ddcbe4`, Java 25 baseline + Session-Id fix) | **private** |

All four are committed and pushed: a clean machine with access to these repositories
reproduces the server build.

Install order: sctp → jSS7 → micro-jainslee → corsac-diameter.

```bash
git clone https://github.com/nhanth87/sctp.git && (cd sctp && mvn -DskipTests install)
git clone -b j25 https://github.com/digicom-et/jss7.git && (cd jss7 && mvn -DskipTests install)
git clone -b major-lotus https://github.com/nhanth87/jain-slee.git && (cd jain-slee && mvn -DskipTests install)
git clone https://github.com/nhanth87/corsac-diameter.git && (cd corsac-diameter && mvn -DskipTests install)
```

`-DskipTests` is the documented build of each fork. Their own suites are not all green:
20 of 39 `sctp-impl` tests fail (mostly TCP variants and `ManagementTest` persistence);
`sctp-api` and `sctp-core` pass. The SAS suite (§8) is what verifies the integration.
The F-Stack native dataplane work in progress lives on sctp branch
`fstack-native-dataplane`, not on `master`.

The micro-jainslee checkout at `jain-slee/jain-slee` (branch `micro-jainslee-2`) is
now at **1.2.2-SNAPSHOT**. Building it does **not** produce the `1.2.0-SNAPSHOT`
this repository pins. Use branch `major-lotus`.

Parent POMs and some transitive artifacts (`org.mobicents:restcomm-parent:2.27.32`,
`org.mobicents.commons:commons:1.0.0.CR1`, …) do not come from Maven Central. They
resolve through the Restcomm/JBoss repositories declared in the upstream POMs
(`oss.sonatype.org/content/groups/public`, `repository.jboss.org/nexus/...`). If those
are unreachable from your network, copy them from a machine that has them in
`~/.m2`.

> Licensing: jSS7, sctp and corsac-diameter are third-party AGPL-3.0 code. They are
> linked by sas-ecs and the simulators only. sas-camara, the device SDKs and
> `bank-backend-sdk` do not depend on them (gate H25,
> `scripts/check-dependency-boundary.sh`). See `LICENSE.md`.

## 3. Server binaries

Whole tree (needs all four forks from §2):

```bash
mvn test                                   # 9 modules; first run online, then mvn -o test
./scripts/package-dist.sh all              # → dist/ (sas-camara) and dist-ecs/ (sas-ecs)
scripts/check-dependency-boundary.sh dist dist-ecs   # both lines must print OK
```

sas-camara alone (needs **only micro-jainslee**). `package-dist.sh` builds the whole
reactor unless told otherwise, so restrict it with `MVN_OPTS`:

```bash
MVN_OPTS="-pl sas-host -am" ./scripts/package-dist.sh camara     # → dist/ (verified 2026-10-14)
```

`package-dist.sh` keeps an existing `dist/configs/application-prod.properties`. A newer
packaged default lands next to it as `application-prod.properties.new`; diff and merge
it by hand.

Run the lab profile (plain HTTP, H2/in-memory, lab evidence; **never ship it**):

```bash
(cd dist-ecs && ./run.sh)                  # sas-ecs on :8095
(cd dist && ./run.sh)                      # sas-camara on :8085
curl -s http://127.0.0.1:8085/camara/health
curl -s http://127.0.0.1:8085/.well-known/openid-configuration | jq .
```

Environment variables and `-D` options (through `SAS_JAVA_OPTS`) override the
dist's `configs/application.properties`. The settings that matter for NV v2:

| Property | Lab default | Meaning |
|---|---|---|
| `sas.oauth.clients-json` | — | registered clients: `private_key_jwt` + `jwks`, grant types, redirect URIs, scopes, purposes, optional `dpop_bound_access_tokens` |
| `sas.oauth.require-client-auth` | false | `true` = `private_key_jwt` mandatory (prod) |
| `sas.security.token-validation-enabled` | false | `true` = ES256 access tokens verified at every API (prod) |
| `sas.security.accept-hs256` | true | `false` in prod (PRO-33) |
| `sas.security.trust-amr-header` | false | never `true` in prod (PRO-37) |
| `sas.oauth.authorize.trust-src-header` | true (lab) | `false` = subscriber identified from the connection source only |
| `sas.oauth.access-token-ttl-seconds` | 300 | clamped to ≤ 300 |
| `sas.oauth.dpop.enabled` | true | DPoP (RFC 9449) at `/token` and on every API |

For the production profile, run `QUARKUS_PROFILE=prod dist/run.sh` with the
environment variables named in `sas-host/src/main/resources/application-prod.properties`
(TLS 1.2+, mTLS, PostgreSQL, real transports). Check the environment first with
`python3 harness/preflight_prod.py --service all`.

Lab simulators:

```bash
(cd sas-jss7-testapp && mvn -o package -DskipTests)
(cd sas-diameter-testapp && mvn -o package)
java --add-modules jdk.sctp -jar sas-jss7-testapp/target/sas-jss7-testapp.jar --listen-port 2906 --peer-port 2907 --http-port 8087
java -jar sas-diameter-testapp/target/sas-diameter-testapp.jar
```

Step-by-step flows with expected outputs: `docs/test/testflow.en.md` §00.

## 4. Android SDK (Maven Central only)

```bash
cd ue-sdk-android
mvn package                                # tests + target/ue-sdk-android-0.1.0-SNAPSHOT.jar
unzip -l target/ue-sdk-android-*.jar | grep ' android/' || echo "OK: no framework stubs in the jar"
```

The module compiles with `javac --release 8` and **without `android.jar`**.
Framework calls go through reflection, and the three classes `requestNetwork()`
needs (`ConnectivityManager`, `Network`, `NetworkRequest`) are compile-only stubs
in `src/stubs/java`. The jar excludes `android/**`, so on a device the framework
classes are used.

In the app (Gradle):

```kotlin
android { defaultConfig { minSdk = 24 } }
dependencies { implementation(files("libs/ue-sdk-android-0.1.0-SNAPSHOT.jar")) }
```

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />  <!-- requestNetwork(CELLULAR) -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />      <!-- optional: LTE/NR radio type -->
```

`NumberVerification.builder(context).build().authorize(url)` blocks; call it off
the main thread. Usage: `sdk/ue-android/README.md`.

## 5. iOS SDK and App Clip (no dependencies)

On macOS:

```bash
cd ue-sdk-ios
swift build && swift test                  # also compiles CellularHTTPClient (Network.framework)
```

On Linux (CI), Network.framework and CoreTelephony are compiled out
(`#if canImport`). The rest builds and tests in Docker:

```bash
cd ue-sdk-ios
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/w -w /w swift:5.10-jammy swift test
# expected: 57 tests, 3 skipped (Apple-specific JSONEncoder key order), 0 failures
```

`Package.swift` lives in `sdk/ue-ios/`, **not at the repository root**, so Xcode
cannot add this repository by its Git URL. Either:

- **Local package:** File → Add Package Dependencies… → **Add Local…** → choose
  `sdk/ue-ios/`. Then link product **UESDK** into the app target and the App Clip
  target.
- **Remote package:** publish `sdk/ue-ios/` as its own repository (or a split
  branch, e.g. `git subtree split -P ue-sdk-ios`) with `Package.swift` at the root,
  then add that URL.

The SDK uses only Foundation, Network and CoreTelephony, all allowed in App Clips.
It is a few hundred KB, far below the App Clip limit.

App Clip set-up on the bank's side:
- App Clip target with the `appclips:<host>` Associated Domains entitlement.
- `apple-app-site-association` on that host.
- App Clip experience in App Store Connect.
- Read the invocation with `AppClipInvocation.parse(activity, allowedHosts:)`.

See `sdk/ue-ios/README.md` § App Clip. Not yet done: an App Clip build on a real
device.

## 6. Web SDK (no dependencies)

```bash
cd ue-sdk-web
node --test                                # 43 tests
npm pack                                   # → restlink-ue-sdk-web-0.3.0.tgz
```

Entry points:

| Entry point | What it provides |
|---|---|
| `@restlink/ue-sdk-web` | session tuple |
| `/number-verification` | silent `prompt=none` authorization, callback parsing, Digital Credentials API |
| `/qr-session` | desktop QR hand-off |
| `/hand-off` | QR on a desktop, App Clip / App Link on the phone |

TypeScript declarations ship next to each module.

## 7. Customer-backend SDK (Maven Central only)

```bash
cd bank-backend-sdk
mvn install                                # tests + installs et.restlink:bank-backend-sdk
```

Generate the client key once, keep the private JWK in the bank's vault, and
register the public JWKS with the operator (`token_endpoint_auth_method =
private_key_jwt`):

```java
Jose key = Jose.generate("bank-nv-1");
vault.store(key.privateJwk());
operator.register(key.publicJwks());
```

Usage: `sdk/bank-backend/README.md`.

## 8. Verify a build

Order matters: unit tests, then gates, then the packaged end-to-end runs.

```bash
mvn -o test                                                   # all server modules
mvn -o -f sdk/pom.xml test     # ue-android + bank-backend in one run
(cd sdk/ue-web && node --test)
python3 harness/run_hardness.py                               # 38/38 gates
python3 harness/run_hardness.py --mutations                   # mutation self-tests
python3 harness/preflight_prod.py --selftest                  # 47/47 scenarios
./scripts/package-dist.sh all
./scripts/camara-nv2-conformance.sh                           # CAMARA NV v2 Phase 2 + SIM Swap v2: 55 checks
./scripts/sdk-nv2-e2e.sh                                      # Android + Web SDK against the dists
./scripts/android-demo-e2e.sh                                 # Android app on the emulator + simulators, pcaps
./scripts/android-demo-screencast.sh                          # the same, filmed: Hyprland, terminals, Wireshark, emulator
./scripts/layered-e2e.sh dist dist-ecs                        # MAP/Diameter core paths (needs the simulators)
```

Two of the scripts read compiled classes and do not build them:

- `camara-nv2-conformance.sh` reads `sdk/bank-backend/target/classes` and
  `sdk/ue-android/target/classes`.
- `sdk-nv2-e2e.sh` reads `sdk/ue-android/target/classes`.

Run the module tests above first. Both scripts start the dists inside a private user
and network namespace, where `lo` also carries `10.20.30.40` (the address the lab
resolver binds to `+251911111111`). They need no root and leave nothing running.

Results on 2026-10-14:

| Run | Result |
|---|---|
| `mvn -o test` | 788 tests, 0 failures |
| gates / mutations / preflight | 38/38 · 29/29 · 47/47 |
| conformance | 50/50 |
| SDK E2E | Android ALL OK, Web ALL OK |

## 9. What not to commit

- **Local artifacts** (see `.gitignore`): `dist/`, `dist-ecs/`, `*/target/`,
  `.build/`, `node_modules/`, `sas-host/data/`, CDRs, pcaps, `slides/`, scratch
  directories.
- **Credentials:** never put a token in a Git remote URL or any file. Use a
  credential helper.
- **Authorship:** commits are authored by **nhanth87 / Tran Nhan** only. The
  repository hooks reject AI or bot attribution, and `--no-verify` is not allowed.

## 10. Further reading

| Topic | Document |
|---|---|
| NV v2 Phase 1–2 (server, DPoP, amr, conformance) | `docs/design/camara-nv2-phase1-2.md` |
| Device SDKs, platform matrix, App Clip | `docs/design/camara-nv2-device-sdk.md` |
| Two-binary layered architecture | `docs/design/sas-layered-architecture.md` |
| Test flows with expected outputs | `docs/test/testflow.en.md` |
| Android app → CAMARA gateway → entitlement server | `docs/test/android-camara-ecs.en.md` |
| Gates and preflight | `docs/design/hardness.md` |
