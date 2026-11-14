#!/usr/bin/env bash
# Kaia Mesh VPN — two-node end-to-end test.
#
# Boots two real meshd nodes on the loopback interface, makes node A dial
# node B explicitly, then asserts that a live libp2p connection exists.
#
# Reproducible and non-destructive:
#   * only the PIDs started by this script are ever killed (never pkill -f)
#   * all output is captured into a log file and echoed at the end
#
# Usage:  ./scripts/e2e_test.sh [logfile]
# Exit:   0 = connection proven, 1 = failure

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${1:-$ROOT/e2e-proof.log}"
BIN="${MESHD_BIN:-$ROOT/meshd}"
TMP="$(mktemp -d)"
PID_A=""
PID_B=""
FAILED=0

cleanup() {
  # Give every node its window to exit on its own, then force the survivors.
  [ -n "$PID_A" ] && kill "$PID_A" 2>/dev/null
  [ -n "$PID_B" ] && kill "$PID_B" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

# All output goes to a temp file; it is echoed to the terminal and copied to
# $LOG at the end. No process substitution: `exec > >(tee ...)` keeps a pipe
# open and makes `wait` hang forever on the last background node.
OUT="$TMP/output.log"
: > "$OUT"
log() { printf '%s\n' "$@" | tee -a "$OUT" ; }
section() { printf '\n===== %s =====\n' "$*" | tee -a "$OUT" ; }

check() { # check <description> <regex> <file>
  if grep -qE "$2" "$3" 2>/dev/null; then
    log "PASS  $1"
  else
    log "FAIL  $1  (pattern: $2)"
    FAILED=$((FAILED + 1))
  fi
}

finish() {
  local rc=$?
  cp "$OUT" "$LOG" 2>/dev/null
  echo "log saved to: $LOG"
  exit "$rc"
}

log "Kaia Mesh VPN - end-to-end test"
log "date:    $(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "host:    $(uname -sm)"
log "go:      $(go version 2>/dev/null || echo n/a)"
log "binary:  $BIN"

section "1. build"
if [ ! -x "$BIN" ]; then
  ( cd "$ROOT" && go build -o "$BIN" ./cmd/meshd ) || { log "BUILD FAILED"; finish; }
fi
log "binary present: $(ls -l "$BIN" | awk '{print $5" bytes"}')"

section "2. start node B (listener)"
"$BIN" -vip 10.42.0.2 -identity "$TMP/b.seed" -for 25s > "$TMP/b.log" 2>&1 &
PID_B=$!
log "node B pid=$PID_B"

ADDR_B=""
for _ in $(seq 1 80); do
  ADDR_B="$(grep -m1 '^listen: ' "$TMP/b.log" 2>/dev/null | sed 's/^listen: //')"
  [ -n "$ADDR_B" ] && break
  sleep 0.25
done
if [ -z "$ADDR_B" ]; then
  log "FAIL: node B never printed a listen address"
  log "--- node B output ---"; cat "$TMP/b.log"
  finish
fi
PEER_B="$(sed -n 's/^meshd ready: PeerID=\([^ ]*\).*/\1/p' "$TMP/b.log" | head -1)"
log "node B peer:   $PEER_B"
log "node B listen: $ADDR_B"

section "3. start node A and dial node B"
"$BIN" -vip 10.42.0.1 -identity "$TMP/a.seed" -connect "$ADDR_B" -for 12s > "$TMP/a.log" 2>&1 &
PID_A=$!
log "node A pid=$PID_A"
wait "$PID_A"; RC_A=$?
PID_A=""
log "node A exit code: $RC_A"
log "--- node A output ---"; cat "$TMP/a.log" | tee -a "$OUT"

section "4. assertions"
check "node A announced its PeerID"        '^meshd ready: PeerID='   "$TMP/a.log"
check "node A printed a listen address"    '^listen: '               "$TMP/a.log"
check "node A dialed node B successfully"  "^CONNECTED peer=$PEER_B" "$TMP/a.log"
check "node A reports at least one peer"   '^PEERS count=[1-9]'      "$TMP/a.log"
check "node A has a live link to node B"   "^LINK peer=$PEER_B"      "$TMP/a.log"
# Negative assertion: node A must NOT have logged any dial failure.
if grep -qE '^DIAL-FAILED' "$TMP/a.log" 2>/dev/null; then
  log "FAIL  node A had no dial failure  (found DIAL-FAILED lines)"
  FAILED=$((FAILED + 1))
else
  log "PASS  node A had no dial failure"
fi
[ -s "$TMP/a.seed" ] && log "PASS  identity seed persisted for node A" || { log "FAIL  identity seed persisted for node A"; FAILED=$((FAILED+1)); }

section "5. verdict"
if [ "$FAILED" -eq 0 ]; then
  log "RESULT: PASS - two nodes established a real encrypted libp2p connection"
  finish
fi
log "RESULT: FAIL - $FAILED assertion(s) failed"
finish
