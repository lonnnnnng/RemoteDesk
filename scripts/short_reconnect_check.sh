#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/.rd_runtime/logs"
REPORT_DIR="${ROOT_DIR}/.rd_runtime/reports"

ANDROID_LOG="${RD_ANDROID_LOG:-${LOG_DIR}/android-emulator.log}"
RELAY_LOG="${LOG_DIR}/relay.log"
TURN_LOG="${LOG_DIR}/turn.log"

RD_WS_PORT="${RD_WS_PORT:-18081}"
RD_TURN_PORT="${RD_TURN_PORT:-3478}"
RD_SCREEN_PREFIX="${RD_SCREEN_PREFIX:-rdtriad}"
RD_ANDROID_APP_ID="${RD_ANDROID_APP_ID:-com.remotedesk.app}"
ANDROID_ACTIVITY="${RD_ANDROID_ACTIVITY:-com.remotedesk.app/.ui.MainActivity}"
ANDROID_SERIAL="${RD_ANDROID_SERIAL:-}"
ANDROID_WS_URL="${RD_ANDROID_WS_URL:-ws://127.0.0.1:${RD_WS_PORT}/ws}"
TARGET_DEVICE_ID="${RD_AGENT_DEVICE_ID:-auto}"

DISCONNECT_SEC=4
RESTART_TRIAD=1
SESSION_TIMEOUT_SEC=50
RECOVERY_TIMEOUT_SEC=70
INPUT_PROOF_TIMEOUT_SEC=30
QUALITY_TIMEOUT_SEC=35
QUALITY_SAMPLE_COUNT=5
MIN_QUALITY_FPS=23.5
QUALITY_STALL_FPS=10
QUALITY_STALL_WINDOW_MS=30000
QUALITY_RTT_HIGH_MS=220
QUALITY_DROP_SPIKE=30
AUTO_PROOF_INPUT=1
LEAVE_SESSION_RUNNING=1

RELAY_SCREEN="${RD_SCREEN_PREFIX}_relay"
RELAY_STOPPED_BY_SCRIPT=0
ACTIVE_TURN_PORT="${RD_TURN_PORT}"

REQUIRED_INPUT_CATEGORIES=(click drag keyboard wheel)

log() {
  printf '[short_reconnect_check] %s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

usage() {
  cat <<'EOF'
Usage: scripts/short_reconnect_check.sh [options]

Options:
  --no-restart              Reuse current triad instead of restarting all targets first
  --serial <adb-serial>     Target Android device serial for physical-device validation
  --ws-url <url>            Relay WebSocket URL injected into Android launch extras
  --target-device-id <id>   Target Mac/Windows agent device id, or auto
  --disconnect-sec <sec>    Relay outage duration, default: 4
  --session-timeout <sec>   Timeout for initial first frame, default: 50
  --recovery-timeout <sec>  Timeout for recovered first frame, default: 70
  --input-timeout <sec>     Timeout for recovered input proof, default: 30
  --quality-timeout <sec>   Timeout for recovered quality proof, default: 35
  --quality-samples <n>     Recent render samples required for quality proof, default: 5
  --min-quality-fps <n>     Minimum recent average FPS after reconnect, default: 23.5
  --quality-stall-fps <n>   FPS treated as a stall sample, default: 10
  --quality-drop-spike <n>  Max recovered-window dropped-frame delta, default: 30
  --no-auto-proof-input     Do not ask Android to send proof input automatically
  --end-session             End the recovered session after report generation
  -h, --help                Show help
EOF
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --no-restart)
        RESTART_TRIAD=0
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
      --disconnect-sec)
        DISCONNECT_SEC="$2"
        shift
        ;;
      --session-timeout)
        SESSION_TIMEOUT_SEC="$2"
        shift
        ;;
      --recovery-timeout)
        RECOVERY_TIMEOUT_SEC="$2"
        shift
        ;;
      --input-timeout)
        INPUT_PROOF_TIMEOUT_SEC="$2"
        shift
        ;;
      --quality-timeout)
        QUALITY_TIMEOUT_SEC="$2"
        shift
        ;;
      --quality-samples)
        QUALITY_SAMPLE_COUNT="$2"
        shift
        ;;
      --min-quality-fps)
        MIN_QUALITY_FPS="$2"
        shift
        ;;
      --quality-stall-fps)
        QUALITY_STALL_FPS="$2"
        shift
        ;;
      --quality-drop-spike)
        QUALITY_DROP_SPIKE="$2"
        shift
        ;;
      --no-auto-proof-input)
        AUTO_PROOF_INPUT=0
        ;;
      --end-session)
        LEAVE_SESSION_RUNNING=0
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

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL}" ]]; then
    adb -s "${ANDROID_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

epoch_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

current_line_count() {
  local file="$1"
  if [[ ! -f "${file}" ]]; then
    echo 0
    return 0
  fi
  wc -l < "${file}" | tr -d '[:space:]'
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

http_devices_url() {
  ANDROID_WS_URL="${ANDROID_WS_URL}" python3 - <<'PY'
from urllib.parse import urlparse, urlunparse
import os

raw = os.environ["ANDROID_WS_URL"]
parsed = urlparse(raw)
scheme = "https" if parsed.scheme == "wss" else "http"
path = parsed.path
if path.endswith("/ws"):
    path = path[:-3]
path = path.rstrip("/") + "/devices"
print(urlunparse((scheme, parsed.netloc, path, "", "", "")))
PY
}

detect_relay_agent_id() {
  local response=""
  response="$(curl -fsS "$(http_devices_url)" 2>/dev/null || true)"
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
  detect_relay_agent_id
}

wait_for_agent_online() {
  local target_device_id="$1"
  local timeout_sec="$2"
  local waited=0
  while (( waited < timeout_sec )); do
    if [[ "${target_device_id}" == "auto" ]] || [[ -z "${target_device_id}" ]]; then
      if detect_relay_agent_id >/dev/null 2>&1; then
        return 0
      fi
    else
      local response=""
      response="$(curl -fsS "$(http_devices_url)" 2>/dev/null || true)"
      if [[ -n "${response}" ]] && RD_DEVICES_JSON="${response}" RD_TARGET_DEVICE_ID="${target_device_id}" python3 - <<'PY' 2>/dev/null
import json
import os

payload = json.loads(os.environ.get("RD_DEVICES_JSON", ""))
target = os.environ["RD_TARGET_DEVICE_ID"]
devices = payload.get("devices") if isinstance(payload, dict) else payload
if not isinstance(devices, list):
    raise SystemExit(1)
for device in devices:
    if not isinstance(device, dict):
        continue
    if str(device.get("device_id", "")).strip() != target:
        continue
    if str(device.get("role", "")).lower() == "agent" and str(device.get("status", "")).lower() in {"online", "busy"}:
        raise SystemExit(0)
raise SystemExit(1)
PY
      then
        return 0
      fi
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

setup_android_reverse_port() {
  local port="$1"
  local spec="tcp:${port}"
  adb_cmd reverse --remove "${spec}" >/dev/null 2>&1 || true
  adb_cmd reverse "${spec}" "${spec}" >/dev/null 2>&1 || true
}

detect_active_turn_port() {
  local port
  local detected=""
  # 作者: long；短断测试只重启 relay，不重启 TURN，所以 relay 恢复时必须沿用当前真实 TURN 端口，避免 3478 被占用时把恢复链路配错。
  detected="$(
    lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null \
      | awk '$1 ~ /turn/ {
          for (i = 1; i <= NF; i++) {
            if ($i == "TCP" && (i + 1) <= NF) {
              port = $(i + 1)
              sub(/^.*:/, "", port)
              print port
              exit
            }
          }
        }'
  )"
  if [[ -n "${detected}" ]]; then
    printf '%s\n' "${detected}"
    return 0
  fi
  for port in $(seq "$((RD_TURN_PORT + 1))" "$((RD_TURN_PORT + 48))") $(seq 3500 3560); do
    if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      printf '%s\n' "${port}"
      return 0
    fi
  done
  printf '%s\n' "${RD_TURN_PORT}"
}

stop_relay_only() {
  log "stopping relay for ${DISCONNECT_SEC}s outage simulation"
  screen_stop "${RELAY_SCREEN}"
  pkill -f "go run ./cmd/api-server" >/dev/null 2>&1 || true
  pkill -f "/api-server" >/dev/null 2>&1 || true
  RELAY_STOPPED_BY_SCRIPT=1
}

start_relay_only() {
  if lsof -nP -iTCP:"${RD_WS_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    RELAY_STOPPED_BY_SCRIPT=0
    return 0
  fi
  log "restarting relay on :${RD_WS_PORT} with TURN :${ACTIVE_TURN_PORT}"
  screen_start "${RELAY_SCREEN}" bash -lc "cd '${ROOT_DIR}' && RD_TURN_PORT='${ACTIVE_TURN_PORT}' make server-run >> '${RELAY_LOG}' 2>&1"
  if ! wait_for_tcp_listen "${RD_WS_PORT}" 35; then
    return 1
  fi
  RELAY_STOPPED_BY_SCRIPT=0
}

restore_relay_on_exit() {
  if (( RELAY_STOPPED_BY_SCRIPT == 1 )); then
    start_relay_only || true
  fi
}

launch_android_for_check() {
  local target_device_id="$1"
  local cmd=(shell am start -S -n "${ANDROID_ACTIVITY}" -e rd_ws_url "${ANDROID_WS_URL}" --ez rd_auto_connect true)
  if [[ -n "${target_device_id}" ]]; then
    cmd+=(-e rd_target_device_id "${target_device_id}" --ez rd_auto_request_session true)
  fi
  if (( AUTO_PROOF_INPUT == 1 )); then
    cmd+=(--ez rd_auto_proof_input true)
  fi
  log "launching android serial=${ANDROID_SERIAL:-default} ws=${ANDROID_WS_URL} target=${target_device_id:-manual}"
  adb_cmd "${cmd[@]}" >/dev/null
}

latest_first_frame_after() {
  local start_line="$1"
  local exclude_session="${2:-}"
  # 作者: long；短断恢复验收必须排除断线前的旧 session 首帧，否则旧画面日志会把“自动恢复到新会话”误判成成功。
  python3 - "${ANDROID_LOG}" "${start_line}" "${exclude_session}" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
start_line = int(sys.argv[2])
exclude = sys.argv[3]
pattern = re.compile(r"first_rendered_frame session=(sess-[^ ]+) size=([^ ]+) since_track_ms=([0-9]+)")
latest = None
if path.exists():
    with path.open("r", errors="replace") as handle:
        for line_no, line in enumerate(handle, start=1):
            if line_no <= start_line:
                continue
            match = pattern.search(line)
            if not match:
                continue
            session_id = match.group(1)
            if exclude and session_id == exclude:
                continue
            latest = (line_no, session_id, match.group(2), match.group(3))
if latest:
    print("\t".join(str(part) for part in latest))
PY
}

wait_for_first_frame() {
  local start_line="$1"
  local timeout_sec="$2"
  local exclude_session="${3:-}"
  local waited=0
  local result=""
  FOUND_FIRST_FRAME=""
  while (( waited < timeout_sec )); do
    result="$(latest_first_frame_after "${start_line}" "${exclude_session}")"
    if [[ -n "${result}" ]]; then
      FOUND_FIRST_FRAME="${result}"
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

wait_for_android_log_pattern() {
  local start_line="$1"
  local pattern="$2"
  local timeout_sec="$3"
  local waited=0
  while (( waited < timeout_sec )); do
    if tail -n +"$((start_line + 1))" "${ANDROID_LOG}" 2>/dev/null | rg -n "${pattern}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

latest_input_proof_after() {
  local relay_start_line="$1"
  local session_id="$2"
  # 作者: long；输入控制 proof 必须限定到恢复后的新 session，旧 session 的鼠标/键盘结果不能证明短断恢复后的控制仍可用。
  python3 - "${RELAY_LOG}" "${relay_start_line}" "${session_id}" <<'PY'
import json
import sys
from pathlib import Path

required = {"click", "drag", "keyboard", "wheel"}
path = Path(sys.argv[1])
start_line = int(sys.argv[2])
session_id = sys.argv[3]
latest = None

def as_float(value, default=0.0):
    try:
        if isinstance(value, bool):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default

def as_bool(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() in {"true", "1", "yes", "y"}
    return False

def coverage_from(value):
    if isinstance(value, list):
        return {str(item).strip().lower() for item in value if str(item).strip()}
    if isinstance(value, str):
        return {part.strip().lower() for part in value.split(",") if part.strip() and part.strip() != "-"}
    return set()

if path.exists():
    with path.open("r", errors="replace") as handle:
        for line_no, line in enumerate(handle, start=1):
            if line_no <= start_line or '"event":"session.metrics.combined"' not in line:
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if payload.get("event") != "session.metrics.combined" or payload.get("session_id") != session_id:
                continue
            coverage = coverage_from(payload.get("remote_input_coverage"))
            coverage |= coverage_from(payload.get("remote_input_applied_categories"))
            for category in required:
                if as_bool(payload.get(f"remote_input_applied_{category}")):
                    coverage.add(category)
            count = as_float(payload.get("remote_input_result_count"))
            applied = as_float(payload.get("remote_input_result_applied_count"))
            failed = as_float(payload.get("remote_input_result_failed_count"))
            missing = sorted(required - coverage)
            passed = (
                payload.get("session_e2e_proof_status") == "video_and_input_observed"
                and as_bool(payload.get("session_e2e_target_route"))
                and count > 0
                and applied >= count
                and failed == 0
                and not missing
            )
            latest = {
                "line": line_no,
                "passed": passed,
                "session_id": session_id,
                "session_e2e_proof_status": payload.get("session_e2e_proof_status", "-"),
                "session_e2e_target_route": as_bool(payload.get("session_e2e_target_route")),
                "remote_input_result_count": count,
                "remote_input_result_applied_count": applied,
                "remote_input_result_failed_count": failed,
                "remote_input_coverage": sorted(coverage),
                "remote_input_missing_coverage": missing,
                "remote_input_last_executor": payload.get("remote_input_last_executor", "-"),
                "remote_input_last_status_code": payload.get("remote_input_last_status_code", "-"),
                "render_fps_avg": as_float(payload.get("render_fps_avg"), -1),
                "rtt_ms_avg": as_float(payload.get("rtt_ms_avg"), -1),
                "candidate_tier_last": payload.get("candidate_tier_last", "-"),
                "session_quality_hint": payload.get("session_quality_hint", "-"),
            }

if latest:
    print(json.dumps(latest, ensure_ascii=False))
PY
}

wait_for_input_proof() {
  local relay_start_line="$1"
  local session_id="$2"
  local timeout_sec="$3"
  local waited=0
  local result=""
  FOUND_INPUT_PROOF=""
  while (( waited < timeout_sec )); do
    result="$(latest_input_proof_after "${relay_start_line}" "${session_id}")"
    if [[ -n "${result}" ]]; then
      FOUND_INPUT_PROOF="${result}"
      if PROOF_JSON="${result}" python3 - <<'PY'
import json
import os
raise SystemExit(0 if json.loads(os.environ["PROOF_JSON"]).get("passed") else 1)
PY
      then
        return 0
      fi
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

latest_quality_proof_after() {
  local android_start_line="$1"
  local session_id="$2"
  # 作者: long；短断恢复后的质量验收只看新首帧之后的 Android 渲染/网络采样，避免 relay 断开期间的长 gap 被累计指标带进恢复质量判断。
  python3 - "${ANDROID_LOG}" "${android_start_line}" "${session_id}" "${QUALITY_SAMPLE_COUNT}" "${MIN_QUALITY_FPS}" "${QUALITY_STALL_FPS}" "${QUALITY_STALL_WINDOW_MS}" "${QUALITY_RTT_HIGH_MS}" "${QUALITY_DROP_SPIKE}" <<'PY'
import json
import re
import statistics
import sys
from pathlib import Path

path = Path(sys.argv[1])
start_line = int(sys.argv[2])
session_id = sys.argv[3]
sample_count = int(sys.argv[4])
min_quality_fps = float(sys.argv[5])
stall_fps = float(sys.argv[6])
stall_window_ms = int(float(sys.argv[7]))
rtt_high_ms = float(sys.argv[8])
drop_spike_limit = int(float(sys.argv[9]))

# 作者: long；Android 渲染采样日志会继续追加 recent_* 字段，解析时只锚定质量验收需要的字段，避免指标扩展后把真实样本误判为 0。
render_re = re.compile(
    r"render_frame_sample session=(?P<session>\S+) "
    r".*?\bfps=(?P<fps>[-\d.]+)\b "
    r".*?\blow_fps_streak_ms=(?P<low>\d+)\b "
    r".*?\blongest_gap_ms=(?P<gap>\d+)\b "
    r".*?\bsize=(?P<size>\S+)"
)
net_re = re.compile(
    r"net_stats session=(?P<session>\S+) .*?frames_dropped=(?P<dropped>\d+) "
    r".*?frames_dropped_spike_max=(?P<spike>\d+) .*?rtt_ms=(?P<rtt>[-\d.]+) "
    r".*?candidate_tier=(?P<tier>\S+)"
)

def parse_optional_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None

render_samples = []
net_samples = []
if path.exists():
    with path.open("r", errors="replace") as handle:
        for line_no, line in enumerate(handle, start=1):
            if line_no <= start_line or session_id not in line:
                continue
            render_match = render_re.search(line)
            if render_match and render_match.group("session") == session_id:
                render_samples.append(
                    {
                        "line": line_no,
                        "fps": float(render_match.group("fps")),
                        "low_fps_streak_ms": int(render_match.group("low")),
                        "longest_gap_ms": int(render_match.group("gap")),
                        "size": render_match.group("size"),
                    }
                )
                continue
            net_match = net_re.search(line)
            if net_match and net_match.group("session") == session_id:
                net_samples.append(
                    {
                        "line": line_no,
                        "frames_dropped": int(net_match.group("dropped")),
                        "frames_dropped_spike_max": int(net_match.group("spike")),
                        "rtt_ms": parse_optional_float(net_match.group("rtt")),
                        "candidate_tier": net_match.group("tier"),
                    }
                )

if len(render_samples) < sample_count:
    latest = {
        "passed": False,
        "reason": "not_enough_render_samples",
        "session_id": session_id,
        "render_sample_count": len(render_samples),
        "required_render_sample_count": sample_count,
    }
    print(json.dumps(latest, ensure_ascii=False))
    raise SystemExit(0)

recent_render = render_samples[-sample_count:]
recent_fps = [sample["fps"] for sample in recent_render]
recent_net = [sample for sample in net_samples if sample["line"] >= recent_render[0]["line"]]
if not recent_net:
    recent_net = net_samples[-1:]

fps_avg = statistics.fmean(recent_fps)
fps_min = min(recent_fps)
max_low_fps_streak_ms = max(sample["low_fps_streak_ms"] for sample in recent_render)
drop_values = [sample["frames_dropped"] for sample in recent_net]
frames_dropped_delta = (max(drop_values) - min(drop_values)) if len(drop_values) >= 2 else 0
max_drop_spike = max((sample["frames_dropped_spike_max"] for sample in recent_net), default=0)
rtt_high_samples = sum(1 for sample in recent_net if sample["rtt_ms"] is not None and sample["rtt_ms"] >= rtt_high_ms)
candidate_tiers = sorted({sample["candidate_tier"] for sample in recent_net if sample["candidate_tier"] != "-"})
tcp_tiers = [tier for tier in candidate_tiers if tier.endswith("_tcp")]
passed = (
    fps_avg >= min_quality_fps
    and max_low_fps_streak_ms < stall_window_ms
    and frames_dropped_delta < drop_spike_limit
    and rtt_high_samples == 0
    and not tcp_tiers
)

print(
    json.dumps(
        {
            "passed": passed,
            "session_id": session_id,
            "render_sample_count": len(render_samples),
            "recent_sample_count": len(recent_render),
            "fps_avg_recent": round(fps_avg, 2),
            "fps_min_recent": round(fps_min, 2),
            "min_quality_fps": min_quality_fps,
            "max_low_fps_streak_ms": max_low_fps_streak_ms,
            "stall_window_ms": stall_window_ms,
            "frames_dropped_delta_recent": frames_dropped_delta,
            "max_frames_dropped_spike_observed": max_drop_spike,
            "frames_drop_spike_limit": drop_spike_limit,
            "rtt_high_samples": rtt_high_samples,
            "rtt_high_ms": rtt_high_ms,
            "candidate_tiers": candidate_tiers,
            "first_recent_line": recent_render[0]["line"],
            "last_recent_line": recent_render[-1]["line"],
            "frame_size_last": recent_render[-1]["size"],
        },
        ensure_ascii=False,
    )
)
PY
}

wait_for_quality_proof() {
  local android_start_line="$1"
  local session_id="$2"
  local timeout_sec="$3"
  local waited=0
  local result=""
  FOUND_QUALITY_PROOF=""
  while (( waited < timeout_sec )); do
    result="$(latest_quality_proof_after "${android_start_line}" "${session_id}")"
    FOUND_QUALITY_PROOF="${result}"
    if [[ -z "${result}" ]]; then
      sleep 1
      (( waited += 1 ))
      continue
    fi
    if QUALITY_JSON="${result}" python3 - <<'PY'
import json
import os
raise SystemExit(0 if json.loads(os.environ["QUALITY_JSON"]).get("passed") else 1)
PY
    then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

write_report() {
  local out_md="$1"
  local out_json="$2"
  local overall="$3"
  local target_device_id="$4"
  local old_session="$5"
  local new_session="$6"
  local old_frame_size="$7"
  local new_frame_size="$8"
  local new_frame_since_ms="$9"
  local recovery_ms="${10}"
  local recovery_intent="${11}"
  local recovery_request="${12}"
  local proof_json="${13:-}"
  local quality_json="${14:-}"

  REPORT_OVERALL="${overall}" \
  REPORT_TARGET="${target_device_id}" \
  REPORT_OLD_SESSION="${old_session}" \
  REPORT_NEW_SESSION="${new_session}" \
  REPORT_OLD_FRAME_SIZE="${old_frame_size}" \
  REPORT_NEW_FRAME_SIZE="${new_frame_size}" \
  REPORT_NEW_FRAME_SINCE_MS="${new_frame_since_ms}" \
  REPORT_RECOVERY_MS="${recovery_ms}" \
  REPORT_DISCONNECT_SEC="${DISCONNECT_SEC}" \
  REPORT_RECOVERY_INTENT="${recovery_intent}" \
  REPORT_RECOVERY_REQUEST="${recovery_request}" \
  REPORT_PROOF_JSON="${proof_json}" \
  REPORT_QUALITY_JSON="${quality_json}" \
  REPORT_OUT_MD="${out_md}" \
  REPORT_OUT_JSON="${out_json}" \
  python3 - <<'PY'
import json
import os
from pathlib import Path

proof_raw = os.environ.get("REPORT_PROOF_JSON", "")
proof = json.loads(proof_raw) if proof_raw else {}
quality_raw = os.environ.get("REPORT_QUALITY_JSON", "")
quality = json.loads(quality_raw) if quality_raw else {}
data = {
    "overall": os.environ["REPORT_OVERALL"],
    "target_device_id": os.environ["REPORT_TARGET"],
    "old_session_id": os.environ["REPORT_OLD_SESSION"],
    "new_session_id": os.environ["REPORT_NEW_SESSION"],
    "old_frame_size": os.environ["REPORT_OLD_FRAME_SIZE"],
    "new_frame_size": os.environ["REPORT_NEW_FRAME_SIZE"],
    "new_frame_since_track_ms": int(os.environ["REPORT_NEW_FRAME_SINCE_MS"] or "0"),
    "relay_disconnect_sec": int(os.environ["REPORT_DISCONNECT_SEC"]),
    "recovery_ms": int(os.environ["REPORT_RECOVERY_MS"] or "0"),
    "recovery_intent_observed": os.environ["REPORT_RECOVERY_INTENT"] == "true",
    "recovery_request_observed": os.environ["REPORT_RECOVERY_REQUEST"] == "true",
    "input_proof": proof,
    "quality_proof": quality,
}

Path(os.environ["REPORT_OUT_JSON"]).write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

proof_status = proof.get("session_e2e_proof_status", "-")
coverage = ",".join(proof.get("remote_input_coverage", [])) or "-"
applied = proof.get("remote_input_result_applied_count", 0)
total = proof.get("remote_input_result_count", 0)
failed = proof.get("remote_input_result_failed_count", 0)
quality_passed = quality.get("passed", False)
quality_fps = quality.get("fps_avg_recent", "-")
quality_min_fps = quality.get("fps_min_recent", "-")
quality_samples = quality.get("recent_sample_count", 0)
quality_drop_spike = quality.get("frames_dropped_delta_recent", "-")
quality_tiers = ",".join(quality.get("candidate_tiers", [])) or "-"
md = [
    "# Short Reconnect Check",
    "",
    f"- overall: `{data['overall']}`",
    f"- target_device_id: `{data['target_device_id']}`",
    f"- old_session_id: `{data['old_session_id']}`",
    f"- new_session_id: `{data['new_session_id']}`",
    f"- relay_disconnect_sec: `{data['relay_disconnect_sec']}`",
    f"- recovery_ms: `{data['recovery_ms']}`",
    f"- new_frame: `{data['new_frame_size']}`, since_track_ms=`{data['new_frame_since_track_ms']}`",
    f"- recovery_intent_observed: `{data['recovery_intent_observed']}`",
    f"- recovery_request_observed: `{data['recovery_request_observed']}`",
    f"- session_e2e_proof_status: `{proof_status}`",
    f"- remote_input_applied: `{applied:g}/{total:g}`",
    f"- remote_input_failed: `{failed:g}`",
    f"- remote_input_coverage: `{coverage}`",
    f"- quality_proof_passed: `{quality_passed}`",
    f"- quality_fps_avg_recent: `{quality_fps}`",
    f"- quality_fps_min_recent: `{quality_min_fps}`",
    f"- quality_recent_samples: `{quality_samples}`",
    f"- quality_drop_delta_recent: `{quality_drop_spike}`",
    f"- quality_candidate_tiers: `{quality_tiers}`",
    f"- render_fps_avg: `{proof.get('render_fps_avg', '-')}`",
    f"- rtt_ms_avg: `{proof.get('rtt_ms_avg', '-')}`",
    f"- candidate_tier_last: `{proof.get('candidate_tier_last', '-')}`",
    "",
]
Path(os.environ["REPORT_OUT_MD"]).write_text("\n".join(md), encoding="utf-8")
PY
}

restart_triad() {
  local android_mode="${RD_ANDROID_MODE:-}"
  if [[ -z "${android_mode}" && -n "${ANDROID_SERIAL}" ]]; then
    if [[ "${ANDROID_SERIAL}" == emulator-* ]]; then
      android_mode="emulator"
    else
      android_mode="physical"
    fi
  fi
  log "restarting triad before short reconnect check"
  if [[ -n "${ANDROID_SERIAL}" ]]; then
    (cd "${ROOT_DIR}" && RD_ANDROID_SERIAL="${ANDROID_SERIAL}" RD_ANDROID_MODE="${android_mode:-physical}" ./scripts/triad_ctl.sh restart)
  else
    (cd "${ROOT_DIR}" && ./scripts/triad_ctl.sh restart)
  fi
}

maybe_end_session() {
  if (( LEAVE_SESSION_RUNNING == 1 )); then
    return 0
  fi
  adb_cmd shell input tap 896 646 >/dev/null 2>&1 || true
}

main() {
  parse_args "$@"
  mkdir -p "${LOG_DIR}" "${REPORT_DIR}"
  trap restore_relay_on_exit EXIT

  if ! command -v screen >/dev/null 2>&1; then
    echo "screen is required" >&2
    exit 1
  fi
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb is required" >&2
    exit 1
  fi

  if (( RESTART_TRIAD == 1 )); then
    restart_triad
  fi

  ACTIVE_TURN_PORT="$(detect_active_turn_port)"
  setup_android_reverse_port "${RD_WS_PORT}" || true
  setup_android_reverse_port "${ACTIVE_TURN_PORT}" || true

  local target_device_id=""
  target_device_id="$(resolve_target_device_id || true)"
  if [[ -z "${target_device_id}" ]]; then
    echo "failed to resolve online target device id from relay /devices" >&2
    exit 1
  fi
  log "target device: ${target_device_id}"

  local android_before_start
  local relay_before_start
  android_before_start="$(current_line_count "${ANDROID_LOG}")"
  relay_before_start="$(current_line_count "${RELAY_LOG}")"

  launch_android_for_check "${target_device_id}"
  if ! wait_for_first_frame "${android_before_start}" "${SESSION_TIMEOUT_SEC}"; then
    echo "initial session did not render first frame within ${SESSION_TIMEOUT_SEC}s" >&2
    exit 1
  fi
  local old_frame_line old_session old_frame_size old_frame_since
  IFS=$'\t' read -r old_frame_line old_session old_frame_size old_frame_since <<< "${FOUND_FIRST_FRAME}"
  log "initial session rendered: ${old_session} ${old_frame_size} since_track_ms=${old_frame_since}"

  local android_before_disconnect
  local relay_before_disconnect
  local disconnect_start_ms
  android_before_disconnect="$(current_line_count "${ANDROID_LOG}")"
  relay_before_disconnect="$(current_line_count "${RELAY_LOG}")"
  disconnect_start_ms="$(epoch_ms)"

  stop_relay_only
  sleep "${DISCONNECT_SEC}"
  if ! start_relay_only; then
    echo "relay did not restart on port ${RD_WS_PORT}" >&2
    exit 1
  fi
  if ! wait_for_agent_online "${target_device_id}" 45; then
    echo "target agent did not reappear after relay restart" >&2
    exit 1
  fi

  local recovery_intent_observed="false"
  local recovery_request_observed="false"
  if wait_for_android_log_pattern "${android_before_disconnect}" "session_recovery_intent target=${target_device_id}" 8; then
    recovery_intent_observed="true"
  fi

  if wait_for_android_log_pattern "${android_before_disconnect}" "短断线恢复：自动重新请求会话 -> ${target_device_id}" "${RECOVERY_TIMEOUT_SEC}"; then
    recovery_request_observed="true"
  fi

  if [[ "${recovery_intent_observed}" != "true" || "${recovery_request_observed}" != "true" ]]; then
    echo "short reconnect did not log both recovery intent and automatic session request" >&2
    exit 1
  fi

  if ! wait_for_first_frame "${android_before_disconnect}" "${RECOVERY_TIMEOUT_SEC}" "${old_session}"; then
    echo "recovered session did not render a new first frame within ${RECOVERY_TIMEOUT_SEC}s" >&2
    exit 1
  fi

  local new_frame_line new_session new_frame_size new_frame_since
  IFS=$'\t' read -r new_frame_line new_session new_frame_size new_frame_since <<< "${FOUND_FIRST_FRAME}"
  local recovery_ms
  recovery_ms="$(( $(epoch_ms) - disconnect_start_ms ))"
  log "recovered session rendered: ${new_session} ${new_frame_size} since_track_ms=${new_frame_since} recovery_ms=${recovery_ms}"

  local proof_json=""
  if (( AUTO_PROOF_INPUT == 1 )); then
    if ! wait_for_input_proof "${relay_before_disconnect}" "${new_session}" "${INPUT_PROOF_TIMEOUT_SEC}"; then
      proof_json="${FOUND_INPUT_PROOF}"
      echo "recovered session did not satisfy input proof within ${INPUT_PROOF_TIMEOUT_SEC}s" >&2
      local ts_fail
      ts_fail="$(date '+%Y%m%d_%H%M%S')"
      write_report \
        "${REPORT_DIR}/short_reconnect_${ts_fail}.md" \
        "${REPORT_DIR}/short_reconnect_${ts_fail}.json" \
        "FAIL" \
        "${target_device_id}" \
        "${old_session}" \
        "${new_session}" \
        "${old_frame_size}" \
        "${new_frame_size}" \
        "${new_frame_since}" \
        "${recovery_ms}" \
        "${recovery_intent_observed}" \
        "${recovery_request_observed}" \
        "${proof_json}" \
        "${FOUND_QUALITY_PROOF:-}"
      exit 1
    fi
    proof_json="${FOUND_INPUT_PROOF}"
  fi

  local quality_json=""
  if ! wait_for_quality_proof "${new_frame_line}" "${new_session}" "${QUALITY_TIMEOUT_SEC}"; then
    quality_json="${FOUND_QUALITY_PROOF}"
    echo "recovered session did not satisfy quality proof within ${QUALITY_TIMEOUT_SEC}s" >&2
    local ts_quality_fail
    ts_quality_fail="$(date '+%Y%m%d_%H%M%S')"
    write_report \
      "${REPORT_DIR}/short_reconnect_${ts_quality_fail}.md" \
      "${REPORT_DIR}/short_reconnect_${ts_quality_fail}.json" \
      "FAIL" \
      "${target_device_id}" \
      "${old_session}" \
      "${new_session}" \
      "${old_frame_size}" \
      "${new_frame_size}" \
      "${new_frame_since}" \
      "${recovery_ms}" \
      "${recovery_intent_observed}" \
      "${recovery_request_observed}" \
      "${proof_json}" \
      "${quality_json}"
    exit 1
  fi
  quality_json="${FOUND_QUALITY_PROOF}"

  maybe_end_session

  local ts
  local out_md
  local out_json
  ts="$(date '+%Y%m%d_%H%M%S')"
  out_md="${REPORT_DIR}/short_reconnect_${ts}.md"
  out_json="${REPORT_DIR}/short_reconnect_${ts}.json"
  write_report \
    "${out_md}" \
    "${out_json}" \
    "PASS" \
    "${target_device_id}" \
    "${old_session}" \
    "${new_session}" \
    "${old_frame_size}" \
    "${new_frame_size}" \
    "${new_frame_since}" \
    "${recovery_ms}" \
    "${recovery_intent_observed}" \
    "${recovery_request_observed}" \
    "${proof_json}" \
    "${quality_json}"

  log "done -> ${out_md}"
  echo "${out_md}"
}

main "$@"
