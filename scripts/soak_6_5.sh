#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/.rd_runtime/logs"
REPORT_DIR="${ROOT_DIR}/.rd_runtime/reports"
ANDROID_LOG="${LOG_DIR}/android-emulator.log"
MAC_LOG="${LOG_DIR}/mac.log"
RELAY_LOG="${LOG_DIR}/relay.log"
TURN_LOG="${LOG_DIR}/turn.log"

PHASE_FG_SEC=300
PHASE_BG_SEC=1800
PHASE_DYNAMIC_SEC=600
PHASE_RECOVER_SEC=300
RESTART_TRIAD=1
TAP_X=896
TAP_Y=646
SESSION_ID=""

log() {
  printf '[soak_6_5] %s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

usage() {
  cat <<'EOF'
Usage: scripts/soak_6_5.sh [options]

Options:
  --quick                 Use quick profile: 30/60/60/30 seconds
  --no-restart            Skip triad restart
  --fg-sec <sec>          Foreground phase duration (default: 300)
  --bg-sec <sec>          Background phase duration (default: 1800)
  --dynamic-sec <sec>     Dynamic pressure phase duration (default: 600)
  --recover-sec <sec>     Recovery foreground phase duration (default: 300)
  --tap-x <n>             Android connect/disconnect button tap x (default: 896)
  --tap-y <n>             Android connect/disconnect button tap y (default: 646)
  --session-id <id>       Force target session id for report parsing
  -h, --help              Show help
EOF
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --quick)
        PHASE_FG_SEC=30
        PHASE_BG_SEC=60
        PHASE_DYNAMIC_SEC=60
        PHASE_RECOVER_SEC=30
        ;;
      --no-restart)
        RESTART_TRIAD=0
        ;;
      --fg-sec)
        PHASE_FG_SEC="$2"
        shift
        ;;
      --bg-sec)
        PHASE_BG_SEC="$2"
        shift
        ;;
      --dynamic-sec)
        PHASE_DYNAMIC_SEC="$2"
        shift
        ;;
      --recover-sec)
        PHASE_RECOVER_SEC="$2"
        shift
        ;;
      --tap-x)
        TAP_X="$2"
        shift
        ;;
      --tap-y)
        TAP_Y="$2"
        shift
        ;;
      --session-id)
        SESSION_ID="$2"
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "unknown option: $1" >&2
        usage
        exit 1
        ;;
    esac
    shift
  done
}

wait_for_pattern() {
  local file="$1"
  local pattern="$2"
  local timeout_sec="$3"
  local waited=0
  while (( waited < timeout_sec )); do
    if rg -n "${pattern}" "${file}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

wait_for_new_pattern() {
  local file="$1"
  local pattern="$2"
  local timeout_sec="$3"
  local start_line="$4"
  local waited=0
  while (( waited < timeout_sec )); do
    if tail -n +"$((start_line + 1))" "${file}" 2>/dev/null | rg -n "${pattern}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

current_line_count() {
  local file="$1"
  if [[ ! -f "${file}" ]]; then
    echo 0
    return 0
  fi
  wc -l < "${file}" | tr -d '[:space:]'
}

latest_session_id() {
  local start_line="${1:-0}"
  tail -n +"$((start_line + 1))" "${ANDROID_LOG}" 2>/dev/null \
    | rg -n "进入会话 sess-" \
    | tail -n 1 \
    | sed -E 's/.*进入会话 (sess-[^ ]+).*/\1/' || true
}

countdown() {
  local phase="$1"
  local total="$2"
  local tick="${3:-30}"
  local elapsed=0
  while (( elapsed < total )); do
    local remain=$(( total - elapsed ))
    log "${phase}: remaining ${remain}s"
    sleep "${tick}"
    elapsed=$(( elapsed + tick ))
  done
}

dynamic_pressure_phase() {
  local duration="$1"
  local elapsed=0
  local step=5
  adb shell am start -n com.remotedesk.app/.ui.MainActivity >/dev/null || true
  sleep 1
  while (( elapsed < duration )); do
    # Swipe inside remote frame area to generate scrolling/motion pressure.
    adb shell input swipe 540 1560 540 980 220 >/dev/null 2>&1 || true
    adb shell input swipe 540 980 540 1560 220 >/dev/null 2>&1 || true
    sleep "${step}"
    elapsed=$(( elapsed + step ))
    if (( elapsed % 30 == 0 )); then
      log "dynamic pressure running: ${elapsed}/${duration}s"
    fi
  done
}

main() {
  parse_args "$@"
  mkdir -p "${REPORT_DIR}"

  if (( RESTART_TRIAD == 1 )); then
    log "restarting triad"
    (cd "${ROOT_DIR}" && ./scripts/triad_ctl.sh restart)
  fi

  log "launching android app and starting session"
  local before_enter_lines
  before_enter_lines="$(current_line_count "${ANDROID_LOG}")"
  adb shell am start -n com.remotedesk.app/.ui.MainActivity >/dev/null || true
  sleep 2
  adb shell input tap "${TAP_X}" "${TAP_Y}" >/dev/null 2>&1 || true

  if ! wait_for_new_pattern "${ANDROID_LOG}" "进入会话 sess-" 45 "${before_enter_lines}"; then
    log "ERROR: session did not start within timeout"
    exit 1
  fi

  local sid="${SESSION_ID}"
  if [[ -z "${sid}" ]]; then
    sid="$(latest_session_id "${before_enter_lines}")"
  fi
  log "session started: ${sid:-unknown}"

  log "phase 1 foreground: ${PHASE_FG_SEC}s"
  adb shell am start -n com.remotedesk.app/.ui.MainActivity >/dev/null || true
  countdown "foreground" "${PHASE_FG_SEC}"

  log "phase 2 background: ${PHASE_BG_SEC}s"
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  countdown "background" "${PHASE_BG_SEC}"

  log "phase 3 dynamic pressure: ${PHASE_DYNAMIC_SEC}s"
  dynamic_pressure_phase "${PHASE_DYNAMIC_SEC}"

  log "phase 4 recovery foreground: ${PHASE_RECOVER_SEC}s"
  adb shell am start -n com.remotedesk.app/.ui.MainActivity >/dev/null || true
  countdown "recovery" "${PHASE_RECOVER_SEC}"

  log "ending session"
  local before_end_lines
  before_end_lines="$(current_line_count "${ANDROID_LOG}")"
  adb shell input tap "${TAP_X}" "${TAP_Y}" >/dev/null 2>&1 || true
  wait_for_new_pattern "${ANDROID_LOG}" "会话结束" 30 "${before_end_lines}" || true

  local ts
  ts="$(date '+%Y%m%d_%H%M%S')"
  local out_md="${REPORT_DIR}/soak_6_5_${ts}.md"
  local out_json="${REPORT_DIR}/soak_6_5_${ts}.json"
  log "building report -> ${out_md}"
  python3 "${ROOT_DIR}/scripts/soak_report.py" \
    --session-id "${sid}" \
    --android-log "${ANDROID_LOG}" \
    --mac-log "${MAC_LOG}" \
    --relay-log "${RELAY_LOG}" \
    --turn-log "${TURN_LOG}" \
    --out-md "${out_md}" \
    --out-json "${out_json}" >/dev/null

  log "done"
  echo "${out_md}"
}

main "$@"
