#!/usr/bin/env bash
# Silent Auth — dependency boundary of the two binaries, checked on the packaged artifact.
#
# docs/design/sas-layered-architecture.md §9.1 test C:
#   * sas-ecs MUST ship the operator adapter (it is the only binary that signals the core);
#   * sas-camara MUST NOT ship any MAP / Diameter / SCTP / SS7 artifact.
#
# Both halves are enforced since migration step M3 (the gateway signals nothing).
# SAS_BOUNDARY_GATEWAY=report downgrades the gateway half to a report (debugging only).
#
# Usage: scripts/check-dependency-boundary.sh [camara-app-dir] [ecs-app-dir]
#   defaults: sas-host/target/quarkus-app  sas-ecs-host/target/quarkus-app
#   (a packaged dist works too: dist/  dist-ecs/ — anything with lib/main/)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
CAMARA="${1:-$REPO/sas-host/target/quarkus-app}"
ECS="${2:-$REPO/sas-ecs-host/target/quarkus-app}"
GATEWAY_MODE="${SAS_BOUNDARY_GATEWAY:-enforce}"

# Signalling artifacts by file name: jSS7 (org.restcomm.protocols.ss7.*), SCTP stacks
# (mobicents / mobius / netty-transport-sctp), Diameter (mobius corsac), our adapter.
FORBIDDEN='(\.ss7\.|sctp|diameter|corsac|sas-operator-adapter)'

lib_of() {
  local dir="$1"
  if [[ -d "$dir/lib/main" ]]; then echo "$dir/lib/main"; return; fi
  echo "missing $dir/lib/main — build or package the binary first" >&2
  exit 2
}

rc=0
ecs_lib=$(lib_of "$ECS")
if ls "$ecs_lib" | grep -q '^et\.restlink\.sas-operator-adapter-.*\.jar$'; then
  echo "OK    sas-ecs ships sas-operator-adapter"
else
  echo "FAIL  sas-ecs does not ship sas-operator-adapter ($ecs_lib)"
  rc=1
fi

camara_lib=$(lib_of "$CAMARA")
mapfile -t leaked < <(ls "$camara_lib" | grep -E "$FORBIDDEN" || true)
if [[ ${#leaked[@]} -eq 0 ]]; then
  echo "OK    sas-camara ships no MAP/Diameter/SCTP/SS7 artifact"
elif [[ "$GATEWAY_MODE" == enforce ]]; then
  echo "FAIL  sas-camara ships ${#leaked[@]} signalling artifact(s):"
  printf '        %s\n' "${leaked[@]}"
  rc=1
else
  echo "REPORT sas-camara ships ${#leaked[@]} signalling artifact(s) (SAS_BOUNDARY_GATEWAY=report)"
  printf '        %s\n' "${leaked[@]}"
fi
exit $rc
