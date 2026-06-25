#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${ROOT_DIR}/.rd_runtime"
LOG_DIR="${STATE_DIR}/logs"

RELAY_LOG="${LOG_DIR}/relay.log"
TURN_LOG="${LOG_DIR}/turn.log"
MAC_LOG="${LOG_DIR}/mac.log"
ANDROID_LOG="${LOG_DIR}/android-emulator.log"

RD_WS_PORT="${RD_WS_PORT:-18081}"
RD_TURN_PORT="${RD_TURN_PORT:-3478}"
RD_MAC_DEV_PORT="${RD_MAC_DEV_PORT:-5173}"
RD_AVD_NAME="${RD_AVD_NAME:-Pixel_6_API_29}"
RD_ANDROID_APP_ID="${RD_ANDROID_APP_ID:-com.remotedesk.app}"
RD_ANDROID_ACTIVITY="${RD_ANDROID_ACTIVITY:-com.remotedesk.app/.ui.MainActivity}"
RD_EMULATOR_ARGS="${RD_EMULATOR_ARGS:--no-snapshot-load -no-snapshot-save}"
RD_SCREEN_PREFIX="${RD_SCREEN_PREFIX:-rdtriad}"
RD_AGENT_DEVICE_ID="${RD_AGENT_DEVICE_ID:-auto}"
RD_DETECTED_AGENT_DEVICE_ID=""

ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ANDROID_EMULATOR_BIN="${ANDROID_EMULATOR_BIN:-${ANDROID_SDK}/emulator/emulator}"
RD_ACTIVE_TURN_PORT="${RD_TURN_PORT}"

RELAY_SCREEN="${RD_SCREEN_PREFIX}_relay"
TURN_SCREEN="${RD_SCREEN_PREFIX}_turn"
MAC_SCREEN="${RD_SCREEN_PREFIX}_mac"
ANDROID_SCREEN="${RD_SCREEN_PREFIX}_android"
ANDROID_LOGCAT_SCREEN="${RD_SCREEN_PREFIX}_android_logcat"

mkdir -p "${LOG_DIR}"

log() {
  printf '[triad_ctl] %s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

warn() {
  printf '[triad_ctl] %s WARN: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

clear_history_logs() {
  mkdir -p "${LOG_DIR}"
  find "${LOG_DIR}" -mindepth 1 -maxdepth 1 -type f -delete 2>/dev/null || true
  log "cleared historical logs in ${LOG_DIR}"
}

wait_for_tcp_listen() {
  local port="$1"
  local timeout_sec="${2:-30}"
  local waited=0
  while (( waited < timeout_sec )); do
    if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

relay_has_device() {
  local device_id="$1"
  local response=""
  response="$(curl -fsS "http://127.0.0.1:${RD_WS_PORT}/devices" 2>/dev/null || true)"
  [[ -n "${response}" ]] && [[ "${response}" == *"\"device_id\":\"${device_id}\""* ]]
}

relay_detect_online_agent_id() {
  local response=""
  response="$(curl -fsS "http://127.0.0.1:${RD_WS_PORT}/devices" 2>/dev/null || true)"
  [[ -n "${response}" ]] || return 1

  if command -v python3 >/dev/null 2>&1; then
    local detected=""
    detected="$(
      RD_DEVICES_JSON="${response}" python3 - <<'PY' 2>/dev/null
import json
import os
import sys

raw = os.environ.get("RD_DEVICES_JSON", "")
payload = json.loads(raw)
devices = payload.get("devices") if isinstance(payload, dict) else payload
if not isinstance(devices, list):
    raise SystemExit(1)

for device in devices:
    if not isinstance(device, dict):
        continue
    role = str(device.get("role", "")).strip().lower()
    status = str(device.get("status", "")).strip().lower()
    if role != "agent" or status not in {"online", "busy"}:
        continue
    device_id = str(device.get("device_id", "")).strip()
    if device_id:
        print(device_id)
        raise SystemExit(0)

raise SystemExit(1)
PY
    )"
    if [[ -n "${detected}" ]]; then
      RD_DETECTED_AGENT_DEVICE_ID="${detected}"
      return 0
    fi
  fi

  return 1
}

wait_for_relay_device() {
  local device_id="${1:-auto}"
  local timeout_sec="${2:-40}"
  local waited=0
  RD_DETECTED_AGENT_DEVICE_ID=""
  while (( waited < timeout_sec )); do
    if [[ "${device_id}" == "auto" ]] || [[ -z "${device_id}" ]]; then
      if relay_detect_online_agent_id; then
        return 0
      fi
    else
      if relay_has_device "${device_id}"; then
        RD_DETECTED_AGENT_DEVICE_ID="${device_id}"
        return 0
      fi
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

relay_has_android_controller() {
  local response=""
  response="$(curl -fsS "http://127.0.0.1:${RD_WS_PORT}/devices" 2>/dev/null || true)"
  [[ -n "${response}" ]] &&
    [[ "${response}" == *"\"role\":\"controller\""* ]] &&
    [[ "${response}" == *"\"device_id\":\"android-"* ]]
}

wait_for_android_controller() {
  local timeout_sec="${1:-45}"
  local waited=0
  while (( waited < timeout_sec )); do
    if relay_has_android_controller; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

has_udp_port_bind() {
  local port="$1"
  netstat -anv -p udp 2>/dev/null | awk -v port="${port}" '
    $4 ~ ("\\*\\." port "$") { found=1 }
    END { exit(found ? 0 : 1) }
  '
}

can_bind_udp_port() {
  local port="$1"
  if command -v python3 >/dev/null 2>&1; then
    python3 - "${port}" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    sock.bind(("0.0.0.0", port))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
    return $?
  fi

  if has_udp_port_bind "${port}"; then
    return 1
  fi
  return 0
}

is_turn_port_available() {
  local port="$1"
  if [[ -z "${port}" ]] || (( port < 1 )) || (( port > 65535 )); then
    return 1
  fi

  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    return 1
  fi

  if ! can_bind_udp_port "${port}"; then
    return 1
  fi

  return 0
}

select_turn_port() {
  local preferred="${RD_TURN_PORT}"
  local candidates=()
  local offset=0
  local port=""
  local selected=""
  local seen=" "

  for offset in $(seq 0 48); do
    candidates+=("$((preferred + offset))")
  done
  for port in $(seq 3500 3560); do
    candidates+=("${port}")
  done

  for port in "${candidates[@]}"; do
    if [[ "${seen}" == *" ${port} "* ]]; then
      continue
    fi
    seen="${seen}${port} "
    if ! is_turn_port_available "${port}"; then
      continue
    fi
    selected="${port}"
    break
  done

  if [[ -z "${selected}" ]]; then
    warn "failed to find available TURN port from ${preferred} (scanned ${preferred}..$((preferred + 48)) and 3500..3560)"
    return 1
  fi

  RD_ACTIVE_TURN_PORT="${selected}"
  if [[ "${RD_ACTIVE_TURN_PORT}" != "${preferred}" ]]; then
    warn "turn port ${preferred} is busy, fallback to ${RD_ACTIVE_TURN_PORT}"
  fi
}

has_running_emulator() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {found=1} END{exit found?0:1}'
}

wait_for_emulator_device() {
  local timeout_sec="${1:-120}"
  local waited=0
  while (( waited < timeout_sec )); do
    if has_running_emulator; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

emulator_has_default_network() {
  if ! command -v adb >/dev/null 2>&1; then
    return 1
  fi
  local active_line
  active_line="$(adb shell dumpsys connectivity 2>/dev/null | awk -F': ' '/Active default network:/ {print $2; exit}' | tr -d '\r' | xargs || true)"
  [[ -n "${active_line}" ]] && [[ "${active_line}" != "none" ]]
}

wait_for_emulator_default_network() {
  local timeout_sec="${1:-45}"
  local waited=0
  while (( waited < timeout_sec )); do
    if emulator_has_default_network; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

kill_android_emulator() {
  if command -v adb >/dev/null 2>&1; then
    local serials
    serials="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1}')"
    if [[ -n "${serials}" ]]; then
      while IFS= read -r serial; do
        [[ -n "${serial}" ]] || continue
        adb -s "${serial}" emu kill >/dev/null 2>&1 || true
      done <<< "${serials}"
    fi
  fi
  screen_stop "${ANDROID_SCREEN}"
  pkill -f "qemu-system-aarch64 -avd ${RD_AVD_NAME}" >/dev/null 2>&1 || true
  pkill -f "/Android/sdk/emulator/netsimd" >/dev/null 2>&1 || true
}

ensure_android_network_routes() {
  if ! command -v adb >/dev/null 2>&1; then
    return 1
  fi

  adb root >/dev/null 2>&1 || true
  adb wait-for-device >/dev/null 2>&1 || true

  adb shell 'ip link set radio0 up >/dev/null 2>&1 || true
ip route add 10.0.2.0/24 dev radio0 src 10.0.2.15 >/dev/null 2>&1 || true
ip route add default via 10.0.2.2 dev radio0 >/dev/null 2>&1 || true
for tbl in legacy_system legacy_network local_network; do
  ip route add 10.0.2.0/24 dev radio0 src 10.0.2.15 table ${tbl} >/dev/null 2>&1 || true
  ip route add default via 10.0.2.2 dev radio0 table ${tbl} >/dev/null 2>&1 || true
done' >/dev/null 2>&1 || true

  if adb shell "echo > /dev/null | nc -w 2 10.0.2.2 ${RD_WS_PORT}" >/dev/null 2>&1; then
    log "android emulator route to 10.0.2.2:${RD_WS_PORT} is ready"
  else
    warn "android emulator route to 10.0.2.2:${RD_WS_PORT} is not ready"
    return 1
  fi
}

setup_android_reverse_port() {
  local port="$1"
  local spec="tcp:${port}"
  adb reverse --remove "${spec}" >/dev/null 2>&1 || true
  if adb reverse "${spec}" "${spec}" >/dev/null 2>&1; then
    log "configured adb reverse ${spec} -> ${spec}"
    return 0
  fi
  warn "failed to configure adb reverse ${spec}"
  return 1
}

setup_android_reverse() {
  local ok=0
  setup_android_reverse_port "${RD_WS_PORT}" || ok=1
  setup_android_reverse_port "${RD_ACTIVE_TURN_PORT}" || ok=1
  if (( ok != 0 )); then
    warn "android reverse setup incomplete; emulator media path may fail"
    return 1
  fi
}

screen_has_session() {
  local session="$1"
  screen -ls 2>/dev/null | grep -E "[0-9]+\.${session}[[:space:]]" >/dev/null 2>&1
}

screen_start() {
  local session="$1"
  shift
  if screen_has_session "${session}"; then
    return 0
  fi
  screen -dmS "${session}" "$@"
}

screen_stop() {
  local session="$1"
  if screen_has_session "${session}"; then
    screen -S "${session}" -X quit || true
  fi
}

start_relay() {
  if lsof -nP -iTCP:"${RD_WS_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    warn "relay port ${RD_WS_PORT} already in use, skip starting relay"
    return 0
  fi
  log "starting relay service on :${RD_WS_PORT}"
  screen_start "${RELAY_SCREEN}" bash -lc "cd '${ROOT_DIR}' && RD_TURN_PORT='${RD_ACTIVE_TURN_PORT}' make server-run >> '${RELAY_LOG}' 2>&1"
  if ! wait_for_tcp_listen "${RD_WS_PORT}" 30; then
    warn "relay did not listen on ${RD_WS_PORT} in time, check ${RELAY_LOG}"
    return 1
  fi
}

start_turn() {
  if lsof -nP -iTCP:"${RD_ACTIVE_TURN_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    warn "turn port ${RD_ACTIVE_TURN_PORT} already in use, skip starting turn"
    return 0
  fi
  log "starting turn service on :${RD_ACTIVE_TURN_PORT}"
  screen_start "${TURN_SCREEN}" bash -lc "cd '${ROOT_DIR}' && RD_TURN_BIND_ADDR='0.0.0.0:${RD_ACTIVE_TURN_PORT}' RD_TURN_PORT='${RD_ACTIVE_TURN_PORT}' make turn-run >> '${TURN_LOG}' 2>&1"
  if ! wait_for_tcp_listen "${RD_ACTIVE_TURN_PORT}" 30; then
    warn "turn did not listen on ${RD_ACTIVE_TURN_PORT} in time, check ${TURN_LOG}"
    return 1
  fi
}

start_mac() {
  if lsof -nP -iTCP:"${RD_MAC_DEV_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    warn "mac dev port ${RD_MAC_DEV_PORT} already in use, skip starting mac"
    return 0
  fi
  log "starting mac desktop dev app"
  screen_start "${MAC_SCREEN}" bash -lc "cd '${ROOT_DIR}/apps/desktop' && RD_DESKTOP_AUTO_CONNECT=1 RD_DESKTOP_AUTO_REGISTER=1 RD_DESKTOP_AUTO_HEARTBEAT=1 npm exec tauri dev --no-watch >> '${MAC_LOG}' 2>&1"
  if ! wait_for_tcp_listen "${RD_MAC_DEV_PORT}" 60; then
    warn "mac app did not expose port ${RD_MAC_DEV_PORT} in time, check ${MAC_LOG}"
    return 1
  fi
}

start_android() {
  if ! command -v adb >/dev/null 2>&1; then
    warn "adb not found, skip android startup"
    return 1
  fi
  local boot_attempt=0
  while :; do
    if ! has_running_emulator; then
      if [[ ! -x "${ANDROID_EMULATOR_BIN}" ]]; then
        warn "emulator binary not found at ${ANDROID_EMULATOR_BIN}"
        return 1
      fi
      log "starting android emulator: ${RD_AVD_NAME} (${RD_EMULATOR_ARGS})"
      screen_start "${ANDROID_SCREEN}" bash -lc "'${ANDROID_EMULATOR_BIN}' -avd '${RD_AVD_NAME}' ${RD_EMULATOR_ARGS} >> '${ANDROID_LOG}' 2>&1"
    else
      log "android emulator already running, skip boot"
    fi

    log "waiting for android emulator to be ready"
    if ! wait_for_emulator_device 180; then
      warn "android emulator not ready in time, check ${ANDROID_LOG}"
      return 1
    fi

    if wait_for_emulator_default_network 45; then
      break
    fi

    if (( boot_attempt >= 1 )); then
      warn "android emulator default network is still missing after retry"
      break
    fi

    warn "android emulator default network missing; restarting emulator once with cold boot args"
    (( boot_attempt += 1 ))
    kill_android_emulator
    sleep 2
  done

  ensure_android_network_routes || true
  setup_android_reverse || true

  log "installing android debug app"
  (cd "${ROOT_DIR}/apps/android" && ./gradlew :app:installDebug >> "${ANDROID_LOG}" 2>&1)

  log "launching android app ${RD_ANDROID_ACTIVITY}"
  adb shell am start -n "${RD_ANDROID_ACTIVITY}" >/dev/null

  log "starting android logcat capture (RemoteDesk* tags)"
  adb logcat -c >/dev/null 2>&1 || true
  screen_stop "${ANDROID_LOGCAT_SCREEN}"
  screen_start "${ANDROID_LOGCAT_SCREEN}" bash -lc "adb logcat -v time RemoteDeskRtc:I RemoteDeskWs:I RemoteDeskUi:I *:S >> '${ANDROID_LOG}' 2>&1"
}

stop_relay() {
  log "stopping relay"
  screen_stop "${RELAY_SCREEN}"
  pkill -f "go run ./cmd/api-server" >/dev/null 2>&1 || true
  pkill -f "/api-server" >/dev/null 2>&1 || true
}

stop_turn() {
  log "stopping turn"
  screen_stop "${TURN_SCREEN}"
  pkill -f "go run ./cmd/turn-server" >/dev/null 2>&1 || true
  pkill -f "/turn-server" >/dev/null 2>&1 || true
}

stop_mac() {
  log "stopping mac desktop"
  screen_stop "${MAC_SCREEN}"
  pkill -f "@tauri-apps/cli/tauri.js dev" >/dev/null 2>&1 || true
  pkill -f "vite/bin/vite.js" >/dev/null 2>&1 || true
  pkill -f "remote_desk_desktop" >/dev/null 2>&1 || true
}

stop_android() {
  log "stopping android emulator/app/logcat"
  if command -v adb >/dev/null 2>&1; then
    adb shell am force-stop "${RD_ANDROID_APP_ID}" >/dev/null 2>&1 || true
    adb reverse --remove "tcp:${RD_WS_PORT}" >/dev/null 2>&1 || true
    adb reverse --remove "tcp:${RD_ACTIVE_TURN_PORT}" >/dev/null 2>&1 || true
  fi

  screen_stop "${ANDROID_LOGCAT_SCREEN}"
  kill_android_emulator
}

do_stop() {
  log "stopping android, mac, turn, relay"
  stop_android
  stop_mac
  stop_turn
  stop_relay
  log "all targets stopped"
}

do_start() {
  clear_history_logs
  if ! select_turn_port; then
    return 1
  fi
  log "starting relay -> turn -> mac -> android"
  start_relay
  start_turn
  start_mac
  start_android
  if wait_for_android_controller 45; then
    log "android controller is online"
  else
    warn "android controller did not appear in /devices, retrying android app launch once"
    if command -v adb >/dev/null 2>&1; then
      adb shell am force-stop "${RD_ANDROID_APP_ID}" >/dev/null 2>&1 || true
      adb shell am start -n "${RD_ANDROID_ACTIVITY}" >/dev/null 2>&1 || true
    fi
    if wait_for_android_controller 45; then
      log "android controller is online after retry"
    else
      warn "android controller is still offline after retry, check ${ANDROID_LOG}"
    fi
  fi
  if wait_for_relay_device "${RD_AGENT_DEVICE_ID}" 35; then
    log "agent ${RD_DETECTED_AGENT_DEVICE_ID:-unknown} is online"
  else
    warn "no online agent appeared in /devices, retrying mac startup once"
    stop_mac
    start_mac
    if wait_for_relay_device "${RD_AGENT_DEVICE_ID}" 35; then
      log "agent ${RD_DETECTED_AGENT_DEVICE_ID:-unknown} is online after retry"
    else
      warn "online agent is still missing after retry, check ${MAC_LOG}"
    fi
  fi
  log "all targets started"
}

do_restart() {
  do_stop
  do_start
}

usage() {
  cat <<'EOF'
Usage: scripts/triad_ctl.sh <command>

Commands:
  stop      Stop android + mac + turn + relay
  start     Start relay -> turn -> mac -> android
  restart   Stop all first, then start relay -> turn -> mac -> android

Environment overrides:
  RD_WS_PORT=18081
  RD_TURN_PORT=3478
  RD_MAC_DEV_PORT=5173
  RD_AVD_NAME=Pixel_6_API_29
  RD_ANDROID_APP_ID=com.remotedesk.app
  RD_ANDROID_ACTIVITY=com.remotedesk.app/.ui.MainActivity
  RD_EMULATOR_ARGS='-no-snapshot-load -no-snapshot-save'
  RD_AGENT_DEVICE_ID=auto
  RD_SCREEN_PREFIX=rdtriad
  RD_ICE_MODE=default|relay_only|relay_udp|relay_tcp
  RD_ICE_DISABLE_STUN=0|1
  RD_ICE_TURN_TRANSPORT=all|udp|tcp
  RD_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS=220
  ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_EMULATOR_BIN
EOF
}

main() {
  if ! command -v screen >/dev/null 2>&1; then
    warn "screen is required but not installed"
    exit 1
  fi

  local cmd="${1:-}"
  case "${cmd}" in
    stop) do_stop ;;
    start) do_start ;;
    restart) do_restart ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "${1:-}"
