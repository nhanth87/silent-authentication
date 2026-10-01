#!/usr/bin/env bash
# Silent Auth — runtime half of layered ADR §9 criterion 1: a process owns no SCTP socket.
#
# Usage: scripts/check-no-sctp.sh <pid> [label]
# Matches the socket inodes the process holds (/proc/<pid>/fd → socket:[inode]) against
# every SCTP endpoint and association the kernel lists (/proc/net/sctp/{eps,assocs}).
# Exit 0 and "OK" when none match; exit 1 and the offending inodes otherwise.
# Run it in the same network namespace as the process.
set -euo pipefail

PID="${1:?usage: $0 <pid> [label]}"
LABEL="${2:-pid $PID}"
[[ -d "/proc/$PID" ]] || { echo "FAIL  $LABEL: no such process"; exit 1; }

mapfile -t mine < <(ls -l "/proc/$PID/fd" 2>/dev/null | sed -n 's/.*socket:\[\([0-9]*\)\].*/\1/p' | sort -u)
sctp_inodes() {
  # The INODE column is located by its header name, never assumed by position.
  local f
  for f in /proc/net/sctp/eps /proc/net/sctp/assocs; do
    [[ -r "$f" ]] || continue
    awk 'NR==1 { for (i = 1; i <= NF; i++) if ($i == "INODE") col = i; next }
         col { print $col }' "$f"
  done
  return 0
}
mapfile -t sctp < <(sctp_inodes | sort -u)

hits=()
for inode in "${mine[@]}"; do
  for s in "${sctp[@]}"; do
    [[ "$inode" == "$s" ]] && hits+=("$inode")
  done
done
if [[ ${#hits[@]} -eq 0 ]]; then
  echo "OK    $LABEL owns no SCTP endpoint (${#mine[@]} sockets checked; ${#sctp[@]} SCTP inodes on this host)"
  exit 0
fi
echo "FAIL  $LABEL owns SCTP socket inode(s): ${hits[*]}"
exit 1
