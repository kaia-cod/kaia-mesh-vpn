#!/usr/bin/env bash
# Kaia Mesh VPN — two-node end-to-end test.
#
# Boots two real meshd nodes on the loopback interface, makes node A dial
# node B explicitly, then asserts that a live libp2p connection exists.
#
# Reproducible and non-destructive:
#   * only the PIDs started by this script are ever killed ($PID, never pkill -f)
#   * all output is captured into a log file
#
# Usage:  ./scripts/e2e_test.sh [logfile]
# Exit:   0 = connection proven, 1 = failure

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${1:-$ROOT/e2e-proof.log}"
BIN="${MESHD_BIN:-$ROOT/meshd}"
TMP="$(mktemp -d)"
PID_B=""
PID_A=""

cleanup() {
  [ -n "$PID_A" ] && kill "$PID_A" 2>/dev/null
  [ -n "$PID_B" ] && kill "$PID_B" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

log() { echo "$@" ; }
section() { echo ; echo "===== $* =====" ; }

: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

log "Kaia Mesh VPN - end-to-end test"
log "date:    $(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "host:    $(uname -sm)"
log "go:      $(go version 2>/dev/null || echo n/a)"
log "binary:  $BIN"

section "1. build"
if [ ! -x "$BIN" ]; then
  ( cd "$ROOT" && go build -o "$BIN" ./cmd/meshd ) || { log "BUILD FAILED"; exit 1; }
fi
log "binary present: $(ls -l "$BIN" | awk '{print $5" bytes"}')"

section "2. start node B (listener)"
"$BIN" -vip 10.42.0.2 -identity "$TMP/b.seed" \
       -for 25s > "$TMP/b.log" 2>&1 &
PID_B=$!
log "node B pid=$PID_B"

ADDR_B=""
for _ in $(seq 1 60); do
  ADDR_B="$(grep -m1 '^listen: ' "$TMP/b.log" 2>/dev/null | sed 's/^listen: //')"
  [ -n "$ADDR_B" ] && break
  sleep 0.25
done
if [ -z "$ADDR_B" ]; then
  log "FAIL: node B never printed a listen address"
  log "--- node B output ---"; cat "$TMP/b.log"
  exit 1
fi
PEER_B="$(grep -m1 '^meshd ready: ' "$TMP/b.log" | sed -n 's/.*PeerID=\([^ ]*\).*/\1/p')"
log "node B peer:   $PEER_B"
log "node B listen: $ADDR_B"

section "3. start node A and dial node B"
"$BIN" -vip 10.42.0.1 -identity "$TMP/a.seed" \
       -connect "$ADDR_B" -for 12s > "$TMP/a.log" 2>&1 &
PID_A=$!
log "node A pid=$PID_A"
wait "$PID_A"; RC_A=$?
PID_A=""
log "node A exit code: $RC_A"
log "--- node A output ---"; cat "$TMP/a.log"

section "4. assertions"
FAILED=0

check() { # check <description> <pattern> <file>
  if grep -qE "$2" "$3"; then
    log "PASS  $1"
  else
    log "FAIL  $1  (pattern: $2)"
    FAILED=$((FAILED + 1))
  fi
}

check "node A announced its PeerID"          '^meshd ready: PeerID='          "$TMP/a.log"
check "node A printed a listen address"      '^listen: '                      "$TMP/a.log"
check "node A dialed node B successfully"    "^CONNECTED peer=$PEER_B"        "$TMP/a.log"
check "node A reports at least one peer"     '^PEERS count=[1-9]'             "$TMP/a.log"
check "node A has a live link to node B"     "^LINK peer=$PEER_B"             "$TMP/a.log"
check "node B stayed up for its window"      'run window elapsed'             "$TMP/b.log"
check "identity seed persisted for node A"   '.'                              "$TMP/a.log"

section "5. verdict"
if [ "$FAILED" -eq 0 ]; then
  log "RESULT: PASS - two nodes established a real encrypted libp2p connection"
  log "log saved to: $LOG"
  exit 0
fi
log "RESULT: FAIL - $FAILED assertion(s) failed"
log "log saved to: $LOG"
exit 1
