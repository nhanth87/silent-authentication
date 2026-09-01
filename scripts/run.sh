#!/usr/bin/env bash
# Silent Auth SAS — lab launcher for the Quarkus fast-jar dist.
#
# Lab defaults: in-memory transports, plain HTTP on :8085, H2 file DB under
# data/. Production is the same launcher with QUARKUS_PROFILE=prod plus the
# env vars application-prod.properties demands — see
# harness/preflight_prod.py (fail-closed: missing env vars refuse to boot).
#
# Overrides:
#   SAS_XMS / SAS_XMX         heap (default 512m / 2g)
#   SAS_LOG_DIR               log dir (default <dist>/logs; /tmp is refused)
#   SAS_PROFILE               profile (default lab; QUARKUS_PROFILE wins)
#   SAS_JAVA_OPTS             extra JVM opts
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME"

APP_JAR="sas-host-app.jar"
for required in quarkus-run.jar "$APP_JAR" configs/application.properties; do
  if [[ ! -f "$APP_HOME/$required" ]]; then
    echo "error: incomplete SAS dist; missing $required" >&2
    exit 1
  fi
done
if [[ ! -d "$APP_HOME/lib/main" || ! -d "$APP_HOME/quarkus" ]]; then
  echo "error: incomplete SAS fast-jar dist; run ./scripts/package-dist.sh" >&2
  exit 1
fi
if find "$APP_HOME/app" -type f -name '*.jar' 2>/dev/null | grep -q .; then
  echo "error: app/ is UI-only; jars are forbidden" >&2
  exit 1
fi

# Resolve JDK 25 without mise shims (an IDE AppImage breaks `mise where`/shims).
JAVA_HOME_25=""
for cand in \
  "${JAVA_HOME:-}" \
  "${HOME}/.local/share/mise/installs/java/zulu-25" \
  "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
  "${HOME}/.local/share/mise/installs/java/25"
do
  [[ -z "$cand" || ! -x "$cand/bin/java" ]] && continue
  if "$cand/bin/java" -version 2>&1 | grep -qE 'version "25'; then
    JAVA_HOME_25="$(readlink -f "$cand" 2>/dev/null || echo "$cand")"
    break
  fi
done
if [[ -z "$JAVA_HOME_25" ]]; then
  echo "error: JDK 25 required (mise java@zulu-25). Do not use mise shims under an IDE." >&2
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_25"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p data logs configs
export SAS_LOG_DIR="${SAS_LOG_DIR:-$APP_HOME/logs}"
case "$SAS_LOG_DIR" in /tmp|/*/tmp/*) echo "error: logs under /tmp are forbidden" >&2; exit 1;; esac

: "${SAS_XMS:=512m}"
: "${SAS_XMX:=2g}"

if [[ -n "${QUARKUS_PROFILE:-}" ]]; then
  export QUARKUS_PROFILE
else
  export QUARKUS_PROFILE="${SAS_PROFILE:-lab}"
fi

# shellcheck disable=SC2206
EXTRA_JAVA_OPTS=( ${JAVA_OPTS:-${SAS_JAVA_OPTS:-}} )

exec "$JAVA_HOME/bin/java" \
  "-Xms${SAS_XMS}" "-Xmx${SAS_XMX}" \
  -XX:+UseZGC -XX:+ExitOnOutOfMemoryError \
  --enable-native-access=ALL-UNNAMED \
  --add-modules jdk.sctp \
  "-Dquarkus.profile=${QUARKUS_PROFILE}" \
  "-Dquarkus.config.locations=file:$APP_HOME/configs/application.properties" \
  "-Duser.dir=$APP_HOME" \
  "-Dsas.log.dir=$SAS_LOG_DIR" \
  "${EXTRA_JAVA_OPTS[@]}" \
  -jar "$APP_HOME/quarkus-run.jar"