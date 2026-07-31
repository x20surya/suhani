#!/usr/bin/env bash
# Real end-to-end liveness probe + nightly maintenance for the deployed Suhani.
#
#   bash deploy/heartbeat.sh probe     # every 3h via cron
#   bash deploy/heartbeat.sh nightly   # once a day via cron
#
# WHY THIS EXISTS: the agent's :8081 healthcheck only reports on its inference
# executor. A worker wedged inside the native WebRTC layer keeps returning
# 200 OK while accepting no jobs, so Docker healthcheck + autoheal cannot see
# it. Actually joining a room and counting audio frames back is the only check
# that proves the whole path - LiveKit dispatch, Saaras STT, the brain, Bulbul
# TTS - is alive. That is what silai/probe_participant.py already does.
#
# It also generates periodic CPU and network load, which is what keeps an
# Oracle Always Free instance from being deemed idle and reclaimed.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
COMPOSE="docker compose -f deploy/docker-compose.yml"
MODE="${1:-probe}"
LOG="data/heartbeat.log"
mkdir -p data/backups
log() { printf '%s [%s] %s\n' "$(date -Is)" "$MODE" "$1" >> "$LOG"; }

if [ "$MODE" = "nightly" ]; then
  # WAL-safe backup. .backup is the only correct way to copy a live SQLite DB;
  # cp of a WAL database can capture a torn state.
  STAMP="$(date +%F)"
  if $COMPOSE exec -T web python -c "
import sqlite3, pathlib
src = sqlite3.connect('/app/data/silai.db')
dst = sqlite3.connect('/app/data/backups/silai-$STAMP.db')
src.backup(dst); dst.close(); src.close()
"; then
    log "backup ok -> data/backups/silai-$STAMP.db"
  else
    log "BACKUP FAILED"
  fi
  # Keep 14 days.
  ls -1t data/backups/silai-*.db 2>/dev/null | tail -n +15 | xargs -r rm -f

  # Unconditional restart. The wedge is invisible to every health signal we
  # have, so the only guaranteed mitigation is to not let any worker process
  # live longer than a day. 04:10 IST is the safest hour for a home tailor.
  $COMPOSE restart agent >/dev/null 2>&1 && log "nightly agent restart ok" || log "nightly agent restart FAILED"
  exit 0
fi

# --- probe mode ---
ROOM="heartbeat-$(date +%s)"
OUT="$($COMPOSE exec -T agent python -m silai.probe_participant "$ROOM" 2>&1 | tail -20)"

if printf '%s' "$OUT" | grep -q "PROBE PASS"; then
  log "probe ok ($ROOM)"
  exit 0
fi

log "PROBE FAILED ($ROOM) - restarting agent"
log "$(printf '%s' "$OUT" | tail -5)"
$COMPOSE restart agent >/dev/null 2>&1 || log "restart command failed"

# Give it time to load models, then confirm the restart actually helped.
sleep 120
OUT2="$($COMPOSE exec -T agent python -m silai.probe_participant "${ROOM}-retry" 2>&1 | tail -20)"
if printf '%s' "$OUT2" | grep -q "PROBE PASS"; then
  log "recovered after restart"
else
  log "STILL FAILING AFTER RESTART - needs a human"
fi
