#!/usr/bin/env bash
# Assemble the Silent Auth SAS dist (Quarkus fast-jar, non-uber).
# Mirrors gmlc-microjainslee build/package-jvm-harness.sh for a plain JVM app
# (no F-Stack / native sidecar). Usage: ./scripts/package-dist.sh
# Env: SAS_DIST_DIR (default <repo>/dist), JAVA_HOME (JDK 25), MVN_OPTS.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$REPO_DIR"
HOST_DIR="$REPO_DIR/sas-host"
DIST_ROOT="${SAS_DIST_DIR:-${DIST_DIR:-$REPO_DIR/dist}}"
MVN_OPTS="${MVN_OPTS:--B -ntp}"
APP_JAR_NAME="sas-host-app.jar"

resolve_java25() {
  local cand ver
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    ver="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
    if echo "${ver}" | grep -qE 'version "25'; then return 0; fi
    echo "warn: ignoring non-JDK-25 JAVA_HOME=${JAVA_HOME} (${ver})" >&2
  fi
  if command -v mise >/dev/null 2>&1; then
    cand="$(mise where java@zulu-25 2>/dev/null || true)"
    if [[ -n "$cand" && -x "$cand/bin/java" ]]; then export JAVA_HOME="$cand"; return 0; fi
  fi
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17" \
    "${HOME}/.local/share/mise/installs/java/25.0.2" \
    "${HOME}/.local/share/mise/installs/java/25"; do
    if [[ -x "${cand}/bin/java" ]]; then export JAVA_HOME="$cand"; return 0; fi
  done
  return 1
}

install_config() {
  local src="$1" dest="$2"
  mkdir -p "$(dirname "$dest")"
  if [[ ! -e "$dest" ]]; then
    cp -f "$src" "$dest"; echo "  installed $(basename "$dest") (first package)"
  elif cmp -s "$src" "$dest"; then
    echo "  kept $(basename "$dest") (identical to packaged default)"
  else
    cp -f "$src" "$dest.new"
    echo "  KEPT existing $(basename "$dest") — packaged default at $(basename "$dest").new"
  fi
}

if ! resolve_java25; then echo "error: JDK 25 required (mise java@zulu-25)." >&2; exit 1; fi
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}"

echo "Packaging Quarkus fast-jar (non-uber) → ${DIST_ROOT}"
# Build with the LAB profile so build-time-fixed properties (quarkus.datasource
# .db-kind) bake H2, matching run.sh's lab default. A PostgreSQL production
# artifact must be repackaged with -Dquarkus.profile=prod (db-kind is build-time).
# shellcheck disable=SC2086
mvn ${MVN_OPTS} package -Dquarkus.package.jar.type=fast-jar -Dquarkus.profile=lab \
  -Dquarkus.build.skip=false -Dmaven.test.skip=true

QA="$HOST_DIR/target/quarkus-app"
if [[ ! -f "$QA/quarkus-run.jar" ]]; then
  echo "error: fast-jar layout missing: $QA/quarkus-run.jar" >&2; exit 1
fi

DIST_ROOT="$(mkdir -p "$DIST_ROOT" && readlink -f "$DIST_ROOT")"
mkdir -p "${DIST_ROOT}/configs" "${DIST_ROOT}/data" "${DIST_ROOT}/logs"
rm -rf "${DIST_ROOT}/lib" "${DIST_ROOT}/quarkus" "${DIST_ROOT}/app"
rm -f "${DIST_ROOT}/quarkus-run.jar" "${DIST_ROOT}/${APP_JAR_NAME}"

cp -f "$QA/quarkus-run.jar" "${DIST_ROOT}/"
cp -a "$QA/lib" "${DIST_ROOT}/"
cp -a "$QA/quarkus" "${DIST_ROOT}/"

src_app_jar="$(find "$QA/app" -maxdepth 1 -type f -name '*.jar' | head -1 || true)"
if [[ -z "$src_app_jar" ]]; then echo "error: app jar missing under $QA/app" >&2; exit 1; fi
cp -f "$src_app_jar" "${DIST_ROOT}/${APP_JAR_NAME}"

# app/ is UI-only: rewrite "app/<jar>" → root name in quarkus-application.dat (writeUTF).
python3 - "${DIST_ROOT}/quarkus/quarkus-application.dat" \
  "app/$(basename "$src_app_jar")" "$APP_JAR_NAME" <<'PY'
import struct, sys
from pathlib import Path
dat_path, old, new = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
data = dat_path.read_bytes()
old_b, new_b = old.encode("utf-8"), new.encode("utf-8")
old_enc = struct.pack(">H", len(old_b)) + old_b
new_enc = struct.pack(">H", len(new_b)) + new_b
n = data.count(old_enc)
if n != 1:
    sys.exit(f"error: expected 1 writeUTF path {old!r} in {dat_path}, found {n}")
dat_path.write_bytes(data.replace(old_enc, new_enc, 1))
print(f"  rewritten quarkus-application.dat: {old} → {new}")
PY

mkdir -p "${DIST_ROOT}/app/html"
cp -a "${HOST_DIR}/app/html/." "${DIST_ROOT}/app/html/"

install_config "${HOST_DIR}/src/main/resources/application.properties" \
  "${DIST_ROOT}/configs/application.properties"
install_config "${HOST_DIR}/src/main/resources/application-prod.properties" \
  "${DIST_ROOT}/configs/application-prod.properties"
install_config "${HOST_DIR}/src/main/resources/ss7-sas.json" \
  "${DIST_ROOT}/configs/ss7-sas.json"

cp -f "${SCRIPT_DIR}/run.sh" "${DIST_ROOT}/run.sh"
chmod +x "${DIST_ROOT}/run.sh"
cp -f "${SCRIPT_DIR}/dist-README.md" "${DIST_ROOT}/README.md"

printf 'jvm-fastjar\n' > "${DIST_ROOT}/.dist-kind"
BAKED_KIND="$(grep -E '^quarkus\.datasource\.db-kind=' \
  "${DIST_ROOT}/configs/application.properties" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '[:space:]' || true)"
printf '%s\n' "${BAKED_KIND:-unknown}" > "${DIST_ROOT}/.baked-db-kind"

missing=0
for req in run.sh README.md quarkus-run.jar "${APP_JAR_NAME}" \
  configs/application.properties configs/application-prod.properties app/html/admin/index.html; do
  if [[ ! -e "${DIST_ROOT}/${req}" ]]; then echo "error: package incomplete — missing ${req}" >&2; missing=1; fi
done
if find "${DIST_ROOT}/app" -type f -name '*.jar' 2>/dev/null | grep -q .; then
  echo "error: jars must not live under app/ (UI-only)" >&2; missing=1
fi
if [[ ! -d "${DIST_ROOT}/lib/main" || ! -d "${DIST_ROOT}/quarkus" ]]; then
  echo "error: fast-jar layout incomplete (lib/main and quarkus/ required)" >&2; missing=1
fi
[[ "$missing" -eq 0 ]] || exit 1

echo "Dist ready: ${DIST_ROOT} (fast-jar + app/html + configs)"
echo "  start:  cd ${DIST_ROOT} && ./run.sh"