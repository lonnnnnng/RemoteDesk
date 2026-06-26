#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/.rd_runtime/logs"
REPORT_DIR="${ROOT_DIR}/.rd_runtime/reports"
ANDROID_LOG="${RD_ANDROID_LOG:-${LOG_DIR}/android-emulator.log}"
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
ANDROID_SERIAL="${RD_ANDROID_SERIAL:-}"
ANDROID_ACTIVITY="${RD_ANDROID_ACTIVITY:-com.remotedesk.app/.ui.MainActivity}"
ANDROID_WS_URL="${RD_ANDROID_WS_URL:-ws://127.0.0.1:${RD_WS_PORT:-18081}/ws}"
TARGET_DEVICE_ID="${RD_AGENT_DEVICE_ID:-auto}"
AUTO_LAUNCH=1
AUTO_PROOF_INPUT=1

log() {
  printf '[soak_6_5] %s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL}" ]]; then
    adb -s "${ANDROID_SERIAL}" "$@"
  else
    adb "$@"
  fi
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
  --serial <adb-serial>   Target Android device serial for physical-device soak
  --ws-url <url>          Relay WebSocket URL injected into Android launch extras
  --target-device-id <id> Target Mac/Windows agent device id, or auto (default: env RD_AGENT_DEVICE_ID/auto)
  --manual-tap            Start/end sessions with fixed tap coordinates instead of launch extras
  --no-auto-proof-input   Do not request Android to send proof input sequence automatically
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
      --serial)
        ANDROID_SERIAL="$2"
        shift
        ;;
      --ws-url)
        ANDROID_WS_URL="$2"
        shift
        ;;
      --target-device-id)
        TARGET_DEVICE_ID="$2"
        shift
        ;;
      --manual-tap)
        AUTO_LAUNCH=0
        ;;
      --no-auto-proof-input)
        AUTO_PROOF_INPUT=0
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

detect_relay_agent_id() {
  local ws_url="$1"
  local http_url=""
  case "${ws_url}" in
    ws://*) http_url="http://${ws_url#ws://}" ;;
    wss://*) http_url="https://${ws_url#wss://}" ;;
    *) http_url="${ws_url}" ;;
  esac
  http_url="${http_url%/ws}/devices"
  local response=""
  response="$(curl -fsS "${http_url}" 2>/dev/null || true)"
  [[ -n "${response}" ]] || return 1
  RD_DEVICES_JSON="${response}" python3 - <<'PY' 2>/dev/null
import json
import os

payload = json.loads(os.environ.get("RD_DEVICES_JSON", ""))
devices = payload.get("devices") if isinstance(payload, dict) else payload
if not isinstance(devices, list):
    raise SystemExit(1)
for device in devices:
    if not isinstance(device, dict):
        continue
    if str(device.get("role", "")).lower() != "agent":
        continue
    if str(device.get("status", "")).lower() not in {"online", "busy"}:
        continue
    device_id = str(device.get("device_id", "")).strip()
    if device_id:
        print(device_id)
        raise SystemExit(0)
raise SystemExit(1)
PY
}

resolve_target_device_id() {
  if [[ "${TARGET_DEVICE_ID}" != "auto" ]] && [[ -n "${TARGET_DEVICE_ID}" ]]; then
    printf '%s\n' "${TARGET_DEVICE_ID}"
    return 0
  fi
  detect_relay_agent_id "${ANDROID_WS_URL}"
}

launch_android_for_soak() {
  local target_device_id="$1"
  local cmd=(shell am start -S -n "${ANDROID_ACTIVITY}" -e rd_ws_url "${ANDROID_WS_URL}" --ez rd_auto_connect true)
  if [[ -n "${target_device_id}" ]]; then
    cmd+=(-e rd_target_device_id "${target_device_id}" --ez rd_auto_request_session true)
  fi
  if (( AUTO_PROOF_INPUT == 1 )); then
    cmd+=(--ez rd_auto_proof_input true)
  fi
  log "launching android serial=${ANDROID_SERIAL:-default} ws=${ANDROID_WS_URL} target=${target_device_id:-manual}"
  adb_cmd "${cmd[@]}" >/dev/null || true
}

restart_triad_for_soak() {
  local android_mode="${RD_ANDROID_MODE:-}"
  if [[ -z "${android_mode}" && -n "${ANDROID_SERIAL}" ]]; then
    if [[ "${ANDROID_SERIAL}" == emulator-* ]]; then
      android_mode="emulator"
    else
      android_mode="physical"
    fi
  fi

  # 作者: long；短测脚本拿到明确设备序列号时，同步交给三端启动脚本，避免真机回归误启动模拟器。
  if [[ -n "${ANDROID_SERIAL}" ]]; then
    (cd "${ROOT_DIR}" && RD_ANDROID_SERIAL="${ANDROID_SERIAL}" RD_ANDROID_MODE="${android_mode:-emulator}" ./scripts/triad_ctl.sh restart)
  else
    (cd "${ROOT_DIR}" && ./scripts/triad_ctl.sh restart)
  fi
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
  local android_start_line="${1:-0}"
  local relay_start_line="${2:-0}"
  local session=""
  session="$(
    tail -n +"$((android_start_line + 1))" "${ANDROID_LOG}" 2>/dev/null \
      | rg -n "进入会话 sess-|session_summary session=sess-|first_rendered_frame session=sess-" \
      | tail -n 1 \
      | sed -E 's/.*(进入会话 |session_summary session=|first_rendered_frame session=)(sess-[^ ]+).*/\2/' || true
  )"
  if [[ -n "${session}" ]]; then
    printf '%s\n' "${session}"
    return 0
  fi
  tail -n +"$((relay_start_line + 1))" "${RELAY_LOG}" 2>/dev/null \
    | rg -n '"event":"session.created".*"session_id":"sess-' \
    | tail -n 1 \
    | sed -E 's/.*"session_id":"(sess-[^"]+)".*/\1/' || true
}

wait_for_session_start() {
  local timeout_sec="$1"
  local android_start_line="$2"
  local relay_start_line="$3"
  local waited=0
  while (( waited < timeout_sec )); do
    # 作者: long；真机重建 Activity 时 UI 文案可能错过 logcat，验证会话启动要以渲染/relay 会话事件为准。
    if tail -n +"$((android_start_line + 1))" "${ANDROID_LOG}" 2>/dev/null \
      | rg -n "进入会话 sess-|session_summary session=sess-|first_rendered_frame session=sess-" >/dev/null 2>&1; then
      return 0
    fi
    if tail -n +"$((relay_start_line + 1))" "${RELAY_LOG}" 2>/dev/null \
      | rg -n '"event":"session.created".*"session_id":"sess-' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

countdown() {
  local phase="$1"
  local total="$2"
  local tick="${3:-30}"
  local elapsed=0
  while (( elapsed < total )); do
    local remain=$(( total - elapsed ))
    local sleep_for="${tick}"
    if (( remain < sleep_for )); then
      sleep_for="${remain}"
    fi
    log "${phase}: remaining ${remain}s"
    sleep "${sleep_for}"
    elapsed=$(( elapsed + sleep_for ))
  done
}

mark_android_phase() {
  local session="$1"
  local phase="$2"
  local duration="$3"
  # 作者: long；阶段标记写入 Android logcat，报告才能把后台切换污染和可见播放卡顿分开判定。
  adb_cmd shell log -t RemoteDeskSoak "soak_phase session=${session:-unknown} phase=${phase} duration_sec=${duration}" >/dev/null 2>&1 || true
}

remote_viewport_swipe_points() {
  local bounds_xml=""
  local bounds=""
  adb_cmd shell uiautomator dump /sdcard/rd_soak_ui.xml >/dev/null 2>&1 || true
  bounds_xml="$(adb_cmd exec-out cat /sdcard/rd_soak_ui.xml 2>/dev/null || true)"
  bounds="$(printf '%s\n' "${bounds_xml}" \
    | tr '>' '>\n' \
    | sed -nE 's/.*resource-id="com\.remotedesk\.app:id\/remoteViewportContainer"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p' \
    | head -1)"
  if [[ -n "${bounds}" ]]; then
    local left top right bottom
    read -r left top right bottom <<<"${bounds}"
    local width=$(( right - left ))
    local height=$(( bottom - top ))
    if (( width > 120 && height > 120 )); then
      local x=$(( left + width / 2 ))
      local y1=$(( top + height * 72 / 100 ))
      local y2=$(( top + height * 34 / 100 ))
      # 作者: long；动态压测必须落在远端画面控件内部，否则固定坐标可能滚动 Android 外层页面，制造与远控画面无关的 Surface 重排卡顿。
      printf '%s %s %s %s\n' "${x}" "${y1}" "${x}" "${y2}"
      adb_cmd shell log -t RemoteDeskSoak "soak_dynamic_bounds source=remoteViewportContainer left=${left} top=${top} right=${right} bottom=${bottom} x=${x} y1=${y1} y2=${y2}" >/dev/null 2>&1 || true
      return 0
    fi
  fi

  local size_line=""
  size_line="$(adb_cmd shell wm size 2>/dev/null | tr -d '\r' || true)"
  if [[ "${size_line}" =~ ([0-9]+)x([0-9]+) ]]; then
    local screen_w="${BASH_REMATCH[1]}"
    local screen_h="${BASH_REMATCH[2]}"
    local x=$(( screen_w / 2 ))
    local y1=$(( screen_h * 62 / 100 ))
    local y2=$(( screen_h * 42 / 100 ))
    printf '%s %s %s %s\n' "${x}" "${y1}" "${x}" "${y2}"
    adb_cmd shell log -t RemoteDeskSoak "soak_dynamic_bounds source=screen_fallback width=${screen_w} height=${screen_h} x=${x} y1=${y1} y2=${y2}" >/dev/null 2>&1 || true
    return 0
  fi

  printf '540 1560 540 980\n'
  adb_cmd shell log -t RemoteDeskSoak "soak_dynamic_bounds source=legacy_fallback x=540 y1=1560 y2=980" >/dev/null 2>&1 || true
}

dynamic_pressure_phase() {
  local duration="$1"
  local elapsed=0
  local step=5
  adb_cmd shell am start -n "${ANDROID_ACTIVITY}" >/dev/null || true
  sleep 1
  local swipe_points
  swipe_points="$(remote_viewport_swipe_points)"
  local swipe_x1 swipe_y1 swipe_x2 swipe_y2
  read -r swipe_x1 swipe_y1 swipe_x2 swipe_y2 <<<"${swipe_points}"
  while (( elapsed < duration )); do
    # 作者: long；这里验证手机端连续控制 Mac 的拖拽链路，坐标来自远端画面 bounds，避免压到 Android 页面滚动本身。
    adb_cmd shell input swipe "${swipe_x1}" "${swipe_y1}" "${swipe_x2}" "${swipe_y2}" 220 >/dev/null 2>&1 || true
    adb_cmd shell input swipe "${swipe_x2}" "${swipe_y2}" "${swipe_x1}" "${swipe_y1}" 220 >/dev/null 2>&1 || true
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
    restart_triad_for_soak
  fi

  log "launching android app and starting session"
  local before_enter_lines
  before_enter_lines="$(current_line_count "${ANDROID_LOG}")"
  local before_relay_lines
  before_relay_lines="$(current_line_count "${RELAY_LOG}")"
  local target_device_id=""
  if (( AUTO_LAUNCH == 1 )); then
    target_device_id="$(resolve_target_device_id || true)"
    launch_android_for_soak "${target_device_id}"
  else
    adb_cmd shell am start -n "${ANDROID_ACTIVITY}" >/dev/null || true
  fi
  sleep 2
  if (( AUTO_LAUNCH == 0 )); then
    adb_cmd shell input tap "${TAP_X}" "${TAP_Y}" >/dev/null 2>&1 || true
  fi

  if ! wait_for_session_start 45 "${before_enter_lines}" "${before_relay_lines}"; then
    log "ERROR: session did not start within timeout"
    exit 1
  fi

  local sid="${SESSION_ID}"
  if [[ -z "${sid}" ]]; then
    sid="$(latest_session_id "${before_enter_lines}" "${before_relay_lines}")"
  fi
  log "session started: ${sid:-unknown}"

  log "phase 1 foreground: ${PHASE_FG_SEC}s"
  mark_android_phase "${sid}" "foreground" "${PHASE_FG_SEC}"
  adb_cmd shell am start -n "${ANDROID_ACTIVITY}" >/dev/null || true
  countdown "foreground" "${PHASE_FG_SEC}"

  log "phase 2 background: ${PHASE_BG_SEC}s"
  mark_android_phase "${sid}" "background" "${PHASE_BG_SEC}"
  adb_cmd shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  countdown "background" "${PHASE_BG_SEC}"

  log "phase 3 dynamic pressure: ${PHASE_DYNAMIC_SEC}s"
  mark_android_phase "${sid}" "dynamic" "${PHASE_DYNAMIC_SEC}"
  dynamic_pressure_phase "${PHASE_DYNAMIC_SEC}"

  log "phase 4 recovery foreground: ${PHASE_RECOVER_SEC}s"
  mark_android_phase "${sid}" "recovery" "${PHASE_RECOVER_SEC}"
  adb_cmd shell am start -n "${ANDROID_ACTIVITY}" >/dev/null || true
  countdown "recovery" "${PHASE_RECOVER_SEC}"

  log "ending session"
  mark_android_phase "${sid}" "ending" "0"
  local before_end_lines
  before_end_lines="$(current_line_count "${ANDROID_LOG}")"
  adb_cmd shell input tap "${TAP_X}" "${TAP_Y}" >/dev/null 2>&1 || true
  wait_for_new_pattern "${ANDROID_LOG}" "会话结束" 30 "${before_end_lines}" || true

  local ts
  ts="$(date '+%Y%m%d_%H%M%S')"
  local out_md="${REPORT_DIR}/soak_6_5_${ts}.md"
  local out_json="${REPORT_DIR}/soak_6_5_${ts}.json"
  local require_input_proof="false"
  if (( AUTO_PROOF_INPUT == 1 )); then
    require_input_proof="true"
  fi
  log "building report -> ${out_md}"
  python3 "${ROOT_DIR}/scripts/soak_report.py" \
    --session-id "${sid}" \
    --android-log "${ANDROID_LOG}" \
    --mac-log "${MAC_LOG}" \
    --relay-log "${RELAY_LOG}" \
    --turn-log "${TURN_LOG}" \
    --require-input-proof "${require_input_proof}" \
    --out-md "${out_md}" \
    --out-json "${out_json}" >/dev/null

  log "done"
  echo "${out_md}"
}

main "$@"
