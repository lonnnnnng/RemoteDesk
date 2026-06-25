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
RENDER_SAMPLE_RE = re.compile(r"render_frame_sample session=(?P<session>\S+) .*?fps=(?P<fps>[-\d.]+)")
NET_STATS_RE = re.compile(
    r"net_stats session=(?P<session>\S+) .*?frames_dropped=(?P<dropped>\d+) .*?"
    r"rtt_ms=(?P<rtt>[-\d.]+) .*?candidate_tier=(?P<tier>\S+)"
)
SESSION_ENTER_RE = re.compile(r"进入会话 (?P<session>\S+)")
TS_RE = re.compile(r"^(?P<md>\d{2}-\d{2}) (?P<hms>\d{2}:\d{2}:\d{2}\.\d{3})")


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
    parser.add_argument("--out-md", required=True)
    parser.add_argument("--out-json", required=True)
    args = parser.parse_args()

    android_lines = load_text(Path(args.android_log))
    relay_lines = load_text(Path(args.relay_log))

    session_id = args.session_id.strip() or detect_session_id(android_lines)
    if not session_id:
        raise SystemExit("failed to detect session_id from android log")

    now = datetime.now()
    year = now.year

    summary_render_fps = None
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

    for line in android_lines:
        s = SUMMARY_RE.search(line)
        if s and s.group("session") == session_id:
            summary_render_fps = parse_float(extract_kv(line, "render_fps_avg") or "")
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

    max_drop_spike = 0
    for idx in range(1, len(net_drops)):
        prev = net_drops[idx - 1][1]
        curr = net_drops[idx][1]
        max_drop_spike = max(max_drop_spike, curr - prev)
    if summary_frames_dropped is None and net_drops:
        summary_frames_dropped = net_drops[-1][1]

    non_user_end_reasons: list[str] = []
    combined_quality_hint = "-"
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
            f"3) frames_dropped 无异常陡增（delta<{args.frames_drop_spike}）",
            passed=max_drop_spike < args.frames_drop_spike,
            detail=f"max_drop_spike={max_drop_spike}",
        ),
        Verdict(
            "4) ICE/RTT 无长时异常",
            passed=rtt_high_samples < 10 and "relay_tcp" not in candidate_tiers and "p2p_tcp" not in candidate_tiers,
            detail=f"rtt_high_samples={rtt_high_samples}, tiers={','.join(sorted(candidate_tiers)) or '-'}",
        ),
    ]

    overall_pass = all(v.passed for v in verdicts)
    summary = {
        "generated_at": now.isoformat(),
        "session_id": session_id,
        "overall_pass": overall_pass,
        "session_quality_hint": combined_quality_hint,
        "render_fps_avg": summary_render_fps,
        "rtt_ms_avg": summary_rtt,
        "candidate_tier_last": summary_candidate_tier,
        "frames_dropped_last": summary_frames_dropped,
        "low_fps_streak_sec_max": round(low_streak_max, 2),
        "max_frames_dropped_spike": max_drop_spike,
        "rtt_high_samples": rtt_high_samples,
        "candidate_tiers": sorted(candidate_tiers),
        "non_user_end_reasons": non_user_end_reasons,
        "verdicts": [v.__dict__ for v in verdicts],
    }

    md_lines = [
        f"# 6.5 Soak Report ({session_id})",
        "",
        f"- generated_at: `{summary['generated_at']}`",
        f"- overall: `{'PASS' if overall_pass else 'FAIL'}`",
        f"- relay.session_quality_hint: `{combined_quality_hint}`",
        f"- render_fps_avg: `{summary_render_fps}`",
        f"- rtt_ms_avg: `{summary_rtt}`",
        f"- candidate_tier_last: `{summary_candidate_tier}`",
        f"- frames_dropped_last: `{summary_frames_dropped}`",
        f"- longest_low_fps_streak_sec: `{low_streak_max:.1f}`",
        f"- max_frames_dropped_spike: `{max_drop_spike}`",
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
