#!/usr/bin/env python3
"""Analyze triad soak logs and emit a 6.5-style session report."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


SUMMARY_RE = re.compile(r"session_summary session=(?P<session>\S+)")
RENDER_SAMPLE_RE = re.compile(
    r"render_frame_sample session=(?P<session>\S+) .*?fps=(?P<fps>[-\d.]+).*?"
    r"low_fps_streak_ms=(?P<low_fps_streak>\d+).*?longest_gap_ms=(?P<longest_gap>\d+)"
)
GAP_SPIKE_RE = re.compile(r"render_frame_gap_spike session=(?P<session>\S+) gap_ms=(?P<gap>\d+)")
SOAK_PHASE_RE = re.compile(r"RemoteDeskSoak.*soak_phase session=(?P<session>\S+) phase=(?P<phase>\S+)")
NET_STATS_RE = re.compile(
    r"net_stats session=(?P<session>\S+) .*?frames_dropped=(?P<dropped>\d+) .*?"
    r"rtt_ms=(?P<rtt>[-\d.]+) .*?candidate_tier=(?P<tier>\S+)"
)
SESSION_ENTER_RE = re.compile(r"进入会话 (?P<session>\S+)")
TS_RE = re.compile(r"^(?P<md>\d{2}-\d{2}) (?P<hms>\d{2}:\d{2}:\d{2}\.\d{3})")
REQUIRED_INPUT_CATEGORIES = ("click", "drag", "keyboard", "wheel")
VISIBLE_PHASES = {"foreground", "dynamic", "recovery", "ending", "unknown"}


@dataclass
class Verdict:
    name: str
    passed: bool
    detail: str


def parse_float(raw: str) -> float | None:
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    return value


def extract_kv(raw_line: str, key: str) -> str | None:
    match = re.search(rf"{re.escape(key)}=([^ ]+)", raw_line)
    if not match:
        return None
    return match.group(1)


def as_float(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        return parse_float(value)
    return None


def as_bool(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "y"}:
            return True
        if normalized in {"false", "0", "no", "n", "-"}:
            return False
    return None


def parse_coverage(value: Any) -> set[str]:
    if isinstance(value, list):
        return {str(item).strip().lower() for item in value if str(item).strip()}
    if isinstance(value, str):
        return {part.strip().lower() for part in value.split(",") if part.strip() and part.strip() != "-"}
    return set()


def parse_ts(line: str, year: int) -> datetime | None:
    match = TS_RE.search(line)
    if not match:
        return None
    return datetime.strptime(f"{year}-{match.group('md')} {match.group('hms')}", "%Y-%m-%d %H:%M:%S.%f")


def load_text(path: Path) -> list[str]:
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def detect_session_id(lines: list[str]) -> str | None:
    for line in reversed(lines):
        match = SUMMARY_RE.search(line)
        if match:
            return match.group("session")
    for line in reversed(lines):
        match = SESSION_ENTER_RE.search(line)
        if match:
            return match.group("session")
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session-id", default="", help="Target session id; auto-detect when omitted")
    parser.add_argument("--android-log", required=True)
    parser.add_argument("--mac-log", required=True)
    parser.add_argument("--relay-log", required=True)
    parser.add_argument("--turn-log", required=True)
    parser.add_argument("--min-render-fps", type=float, default=24.0)
    parser.add_argument("--stall-fps", type=float, default=10.0)
    parser.add_argument("--stall-window-sec", type=float, default=30.0)
    parser.add_argument("--rtt-high-ms", type=float, default=220.0)
    parser.add_argument("--frames-drop-spike", type=int, default=30)
    parser.add_argument("--max-visible-frame-gap-ms", type=int, default=1000)
    parser.add_argument("--require-input-proof", choices=["true", "false"], default="true")
    parser.add_argument("--out-md", required=True)
    parser.add_argument("--out-json", required=True)
    args = parser.parse_args()
    require_input_proof = args.require_input_proof == "true"

    android_lines = load_text(Path(args.android_log))
    relay_lines = load_text(Path(args.relay_log))

    session_id = args.session_id.strip() or detect_session_id(android_lines)
    if not session_id:
        raise SystemExit("failed to detect session_id from android log")

    now = datetime.now()
    year = now.year

    summary_render_fps = None
    summary_render_fps_recent = None
    summary_recent_sample_count = None
    summary_recent_window_ms = None
    summary_recent_frame_gap_ms = None
    summary_recent_low_fps_streak_ms = None
    summary_controller_quality_hint_recent = "-"
    summary_rtt = None
    summary_candidate_tier = "-"
    summary_frames_dropped = None
    low_streak_start = None
    low_streak_last = None
    low_streak_max = 0.0
    render_samples: list[float] = []
    net_drops: list[tuple[datetime | None, int]] = []
    net_rtt_samples: list[float] = []
    net_tier_last = "-"
    rtt_high_samples = 0
    candidate_tiers: set[str] = set()
    current_phase = "unknown"
    phase_gap_max: dict[str, int] = {}
    phase_fps_samples: dict[str, list[float]] = {}
    visible_gap_max = 0
    all_gap_max = 0
    gap_spikes: list[dict[str, Any]] = []

    for line in android_lines:
        phase = SOAK_PHASE_RE.search(line)
        if phase and phase.group("session") in {session_id, "unknown"}:
            current_phase = phase.group("phase")
            continue

        s = SUMMARY_RE.search(line)
        if s and s.group("session") == session_id:
            summary_render_fps = parse_float(extract_kv(line, "render_fps_avg") or "")
            summary_render_fps_recent = parse_float(extract_kv(line, "render_fps_recent") or "")
            summary_recent_sample_count = parse_float(extract_kv(line, "render_recent_samples") or "")
            summary_recent_window_ms = parse_float(extract_kv(line, "render_recent_window_ms") or "")
            summary_recent_frame_gap_ms = parse_float(extract_kv(line, "recent_frame_gap_ms") or "")
            summary_recent_low_fps_streak_ms = parse_float(extract_kv(line, "recent_low_fps_streak_ms") or "")
            summary_controller_quality_hint_recent = extract_kv(line, "controller_quality_hint_recent") or summary_controller_quality_hint_recent
            summary_rtt = parse_float(extract_kv(line, "rtt_ms_avg") or "")
            summary_candidate_tier = extract_kv(line, "candidate_tier_last") or "-"
            dropped_raw = extract_kv(line, "frames_dropped")
            if dropped_raw is not None and dropped_raw.isdigit():
                summary_frames_dropped = int(dropped_raw)
            continue

        r = RENDER_SAMPLE_RE.search(line)
        if r and r.group("session") == session_id:
            fps = parse_float(r.group("fps"))
            ts = parse_ts(line, year)
            if fps is not None:
                render_samples.append(fps)
                phase_fps_samples.setdefault(current_phase, []).append(fps)
            # 作者: long；阶段化验收关注当下阶段的可见帧间隔，不能只等 1s spike 日志，否则 861ms 这类接近卡顿的样本会从阶段统计里消失。
            sample_gap = parse_float(extract_kv(line, "sample_gap_ms") or "")
            if sample_gap is None:
                sample_gap = parse_float(r.group("longest_gap"))
            if sample_gap is not None:
                gap_ms = int(sample_gap)
                all_gap_max = max(all_gap_max, gap_ms)
                phase_gap_max[current_phase] = max(phase_gap_max.get(current_phase, 0), gap_ms)
                if current_phase in VISIBLE_PHASES:
                    visible_gap_max = max(visible_gap_max, gap_ms)
            if fps is not None and fps < args.stall_fps and ts is not None:
                if low_streak_start is None:
                    low_streak_start = ts
                low_streak_last = ts
            else:
                if low_streak_start and low_streak_last:
                    low_streak_max = max(low_streak_max, (low_streak_last - low_streak_start).total_seconds())
                low_streak_start = None
                low_streak_last = None
            continue

        g = GAP_SPIKE_RE.search(line)
        if g and g.group("session") == session_id:
            gap_ms = int(g.group("gap"))
            ts = parse_ts(line, year)
            all_gap_max = max(all_gap_max, gap_ms)
            phase_gap_max[current_phase] = max(phase_gap_max.get(current_phase, 0), gap_ms)
            if current_phase in VISIBLE_PHASES:
                visible_gap_max = max(visible_gap_max, gap_ms)
            gap_spikes.append(
                {
                    "ts": ts.isoformat() if ts else "-",
                    "phase": current_phase,
                    "gap_ms": gap_ms,
                }
            )
            continue

        n = NET_STATS_RE.search(line)
        if n and n.group("session") == session_id:
            dropped = int(n.group("dropped"))
            ts = parse_ts(line, year)
            net_drops.append((ts, dropped))
            tier = n.group("tier")
            if tier and tier != "-":
                candidate_tiers.add(tier)
                net_tier_last = tier
            rtt = parse_float(n.group("rtt"))
            if rtt is not None:
                net_rtt_samples.append(rtt)
                if rtt >= args.rtt_high_ms:
                    rtt_high_samples += 1

    if low_streak_start and low_streak_last:
        low_streak_max = max(low_streak_max, (low_streak_last - low_streak_start).total_seconds())

    if summary_render_fps is None and render_samples:
        summary_render_fps = round(sum(render_samples) / len(render_samples), 2)
    if summary_rtt is None and net_rtt_samples:
        summary_rtt = round(sum(net_rtt_samples) / len(net_rtt_samples), 2)
    if summary_candidate_tier == "-" and net_tier_last != "-":
        summary_candidate_tier = net_tier_last
    phase_fps_avg = {
        phase: round(sum(values) / len(values), 2)
        for phase, values in sorted(phase_fps_samples.items())
        if values
    }

    max_drop_spike = 0
    for idx in range(1, len(net_drops)):
        prev = net_drops[idx - 1][1]
        curr = net_drops[idx][1]
        max_drop_spike = max(max_drop_spike, curr - prev)
    if summary_frames_dropped is None and net_drops:
        summary_frames_dropped = net_drops[-1][1]

    non_user_end_reasons: list[str] = []
    combined_quality_hint = "-"
    combined_quality_hint_recent = "-"
    combined_render_fps_recent = None
    combined_render_recent_sample_count = None
    combined_render_recent_window_ms = None
    combined_render_recent_max_frame_gap_ms = None
    combined_render_recent_low_fps_streak_ms = None
    combined_frames_dropped_delta_recent = None
    combined_frames_dropped_spike_recent = None
    combined_remote_input_count = 0.0
    combined_remote_input_applied = 0.0
    combined_remote_input_failed = 0.0
    combined_remote_input_coverage: set[str] = set()
    combined_proof_status = "-"
    combined_target_route = False
    combined_last_executor = "-"
    combined_last_status = "-"
    for line in relay_lines:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            payload: dict[str, Any] = json.loads(line)
        except json.JSONDecodeError:
            continue
        if payload.get("session_id") != session_id:
            continue
        event = str(payload.get("event") or "")
        if event == "session.ended":
            reason = str(payload.get("reason") or "-")
            if reason != "user_end":
                non_user_end_reasons.append(reason)
        elif event == "session.metrics.combined":
            combined_quality_hint = str(payload.get("session_quality_hint") or "-")
            combined_quality_hint_recent = str(payload.get("session_quality_hint_recent") or combined_quality_hint_recent or "-")
            recent_value = as_float(payload.get("render_fps_recent"))
            if recent_value is not None:
                combined_render_fps_recent = recent_value
            recent_value = as_float(payload.get("render_recent_sample_count"))
            if recent_value is not None:
                combined_render_recent_sample_count = recent_value
            recent_value = as_float(payload.get("render_recent_window_ms"))
            if recent_value is not None:
                combined_render_recent_window_ms = recent_value
            recent_value = as_float(payload.get("render_recent_max_frame_gap_ms"))
            if recent_value is not None:
                combined_render_recent_max_frame_gap_ms = recent_value
            recent_value = as_float(payload.get("render_recent_low_fps_streak_ms"))
            if recent_value is not None:
                combined_render_recent_low_fps_streak_ms = recent_value
            recent_value = as_float(payload.get("frames_dropped_delta_recent"))
            if recent_value is not None:
                combined_frames_dropped_delta_recent = recent_value
            recent_value = as_float(payload.get("frames_dropped_spike_recent"))
            if recent_value is not None:
                combined_frames_dropped_spike_recent = recent_value
            combined_proof_status = str(payload.get("session_e2e_proof_status") or combined_proof_status or "-")
            combined_target_route = as_bool(payload.get("session_e2e_target_route")) or combined_target_route
            combined_last_executor = str(payload.get("remote_input_last_executor") or combined_last_executor or "-")
            combined_last_status = str(payload.get("remote_input_last_status_code") or combined_last_status or "-")
            combined_remote_input_count = as_float(payload.get("remote_input_result_count")) or combined_remote_input_count
            combined_remote_input_applied = (
                as_float(payload.get("remote_input_result_applied_count")) or combined_remote_input_applied
            )
            combined_remote_input_failed = (
                as_float(payload.get("remote_input_result_failed_count")) or combined_remote_input_failed
            )
            combined_remote_input_coverage |= parse_coverage(payload.get("remote_input_coverage"))
            combined_remote_input_coverage |= parse_coverage(payload.get("remote_input_applied_categories"))
            for category in REQUIRED_INPUT_CATEGORIES:
                if as_bool(payload.get(f"remote_input_applied_{category}")):
                    combined_remote_input_coverage.add(category)

    missing_input_categories = [
        category for category in REQUIRED_INPUT_CATEGORIES if category not in combined_remote_input_coverage
    ]
    if combined_render_fps_recent is None:
        combined_render_fps_recent = summary_render_fps_recent
    if combined_render_recent_sample_count is None:
        combined_render_recent_sample_count = summary_recent_sample_count
    if combined_render_recent_window_ms is None:
        combined_render_recent_window_ms = summary_recent_window_ms
    if combined_render_recent_max_frame_gap_ms is None:
        combined_render_recent_max_frame_gap_ms = summary_recent_frame_gap_ms
    if combined_render_recent_low_fps_streak_ms is None:
        combined_render_recent_low_fps_streak_ms = summary_recent_low_fps_streak_ms
    if combined_quality_hint_recent == "-":
        combined_quality_hint_recent = summary_controller_quality_hint_recent
    input_detail = (
        "skipped"
        if not require_input_proof
        else (
            f"proof={combined_proof_status}, route={combined_target_route}, "
            f"applied={combined_remote_input_applied:g}/{combined_remote_input_count:g}, "
            f"failed={combined_remote_input_failed:g}, coverage={','.join(sorted(combined_remote_input_coverage)) or '-'}, "
            f"missing={','.join(missing_input_categories) or '-'}, executor={combined_last_executor}, status={combined_last_status}"
        )
    )

    verdicts = [
        Verdict(
            "1) 无异常掉线（非 user_end）",
            passed=not non_user_end_reasons,
            detail="ok" if not non_user_end_reasons else f"reasons={','.join(non_user_end_reasons)}",
        ),
        Verdict(
            f"2) Android render_fps_avg >= {args.min_render_fps}",
            passed=summary_render_fps is not None and summary_render_fps >= args.min_render_fps,
            detail=f"render_fps_avg={summary_render_fps if summary_render_fps is not None else '-'}",
        ),
        Verdict(
            f"2b) 无持续塌陷（fps<{args.stall_fps} 连续>={args.stall_window_sec}s）",
            passed=low_streak_max < args.stall_window_sec,
            detail=f"longest_low_fps_streak_sec={low_streak_max:.1f}",
        ),
        Verdict(
            f"2c) 可见阶段无 1s 级帧间隔尖峰（gap<{args.max_visible_frame_gap_ms}ms）",
            passed=visible_gap_max < args.max_visible_frame_gap_ms,
            detail=f"visible_gap_max_ms={visible_gap_max}, phase_gap_max={phase_gap_max or '-'}",
        ),
        Verdict(
            "2d) 最近质量窗口稳定",
            passed=combined_quality_hint_recent == "stable",
            detail=(
                f"recent_hint={combined_quality_hint_recent}, "
                f"recent_fps={combined_render_fps_recent if combined_render_fps_recent is not None else '-'}, "
                f"recent_gap_ms={combined_render_recent_max_frame_gap_ms if combined_render_recent_max_frame_gap_ms is not None else '-'}, "
                f"recent_samples={combined_render_recent_sample_count if combined_render_recent_sample_count is not None else '-'}"
            ),
        ),
        Verdict(
            f"3) frames_dropped 无异常陡增（delta<{args.frames_drop_spike}）",
            passed=max_drop_spike < args.frames_drop_spike,
            detail=f"max_drop_spike={max_drop_spike}",
        ),
        Verdict(
            "4) ICE/RTT 无长时异常",
            passed=rtt_high_samples < 10 and "relay_tcp" not in candidate_tiers and "p2p_tcp" not in candidate_tiers,
            detail=f"rtt_high_samples={rtt_high_samples}, tiers={','.join(sorted(candidate_tiers)) or '-'}",
        ),
        Verdict(
            "5) Android 输入控制覆盖 click/drag/keyboard/wheel",
            passed=(
                True
                if not require_input_proof
                else (
                    combined_proof_status == "video_and_input_observed"
                    and combined_target_route
                    and combined_remote_input_count > 0
                    and combined_remote_input_applied >= combined_remote_input_count
                    and combined_remote_input_failed == 0
                    and not missing_input_categories
                )
            ),
            detail=input_detail,
        ),
    ]

    overall_pass = all(v.passed for v in verdicts)
    summary = {
        "generated_at": now.isoformat(),
        "session_id": session_id,
        "overall_pass": overall_pass,
        "session_quality_hint": combined_quality_hint,
        "session_quality_hint_recent": combined_quality_hint_recent,
        "render_fps_avg": summary_render_fps,
        "render_fps_recent": combined_render_fps_recent,
        "render_recent_sample_count": combined_render_recent_sample_count,
        "render_recent_window_ms": combined_render_recent_window_ms,
        "render_recent_max_frame_gap_ms": combined_render_recent_max_frame_gap_ms,
        "render_recent_low_fps_streak_ms": combined_render_recent_low_fps_streak_ms,
        "rtt_ms_avg": summary_rtt,
        "candidate_tier_last": summary_candidate_tier,
        "frames_dropped_last": summary_frames_dropped,
        "frames_dropped_delta_recent": combined_frames_dropped_delta_recent,
        "frames_dropped_spike_recent": combined_frames_dropped_spike_recent,
        "low_fps_streak_sec_max": round(low_streak_max, 2),
        "visible_frame_gap_ms_max": visible_gap_max,
        "all_frame_gap_ms_max": all_gap_max,
        "phase_frame_gap_ms_max": phase_gap_max,
        "phase_render_fps_avg": phase_fps_avg,
        "frame_gap_spikes": gap_spikes,
        "max_frames_dropped_spike": max_drop_spike,
        "rtt_high_samples": rtt_high_samples,
        "candidate_tiers": sorted(candidate_tiers),
        "non_user_end_reasons": non_user_end_reasons,
        "remote_input_result_count": combined_remote_input_count,
        "remote_input_result_applied_count": combined_remote_input_applied,
        "remote_input_result_failed_count": combined_remote_input_failed,
        "remote_input_coverage": sorted(combined_remote_input_coverage),
        "remote_input_missing_coverage": missing_input_categories,
        "session_e2e_proof_status": combined_proof_status,
        "session_e2e_target_route": combined_target_route,
        "remote_input_last_executor": combined_last_executor,
        "remote_input_last_status_code": combined_last_status,
        "verdicts": [v.__dict__ for v in verdicts],
    }

    md_lines = [
        f"# 6.5 Soak Report ({session_id})",
        "",
        f"- generated_at: `{summary['generated_at']}`",
        f"- overall: `{'PASS' if overall_pass else 'FAIL'}`",
        f"- relay.session_quality_hint: `{combined_quality_hint}`",
        f"- relay.session_quality_hint_recent: `{combined_quality_hint_recent}`",
        f"- render_fps_avg: `{summary_render_fps}`",
        f"- render_fps_recent: `{combined_render_fps_recent}`",
        f"- render_recent_max_frame_gap_ms: `{combined_render_recent_max_frame_gap_ms}`",
        f"- visible_frame_gap_ms_max: `{visible_gap_max}`",
        f"- phase_frame_gap_ms_max: `{phase_gap_max or '-'}`",
        f"- phase_render_fps_avg: `{phase_fps_avg or '-'}`",
        f"- rtt_ms_avg: `{summary_rtt}`",
        f"- candidate_tier_last: `{summary_candidate_tier}`",
        f"- frames_dropped_last: `{summary_frames_dropped}`",
        f"- frames_dropped_delta_recent: `{combined_frames_dropped_delta_recent}`",
        f"- frames_dropped_spike_recent: `{combined_frames_dropped_spike_recent}`",
        f"- longest_low_fps_streak_sec: `{low_streak_max:.1f}`",
        f"- max_frames_dropped_spike: `{max_drop_spike}`",
        f"- remote_input_applied: `{combined_remote_input_applied:g}/{combined_remote_input_count:g}`",
        f"- remote_input_coverage: `{','.join(sorted(combined_remote_input_coverage)) or '-'}`",
        f"- session_e2e_proof_status: `{combined_proof_status}`",
        "",
        "## Verdicts",
    ]
    for verdict in verdicts:
        md_lines.append(
            f"- [{'x' if verdict.passed else ' '}] {verdict.name}: `{verdict.detail}`"
        )

    out_md = Path(args.out_md)
    out_json = Path(args.out_json)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text("\n".join(md_lines) + "\n", encoding="utf-8")
    out_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(str(out_md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
