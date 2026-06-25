#!/usr/bin/env python3
"""Build runtime capability matrix from relay session.metrics.combined logs."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


DEFAULT_LOG = Path('.rd_runtime/logs/relay.log')


def fmt_num(value: Any, ndigits: int = 2) -> str:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return '-'
    if number < 0:
        return '-'
    return f"{number:.{ndigits}f}"


def normalize_runtime_kernel(entry: dict[str, Any]) -> str:
    value = str(entry.get('runtime_kernel') or '').strip()
    return value or 'unknown'


def normalize_runtime_cap_sig(entry: dict[str, Any]) -> str:
    value = str(entry.get('runtime_capability_signature') or '').strip()
    return value or 'unknown'


def normalize_native_sender_support_level(entry: dict[str, Any]) -> str:
    value = str(entry.get('runtime_native_sender_support_level') or '').strip()
    return value or 'unknown'


def normalize_native_sender_blocker(entry: dict[str, Any]) -> str:
    value = str(entry.get('runtime_native_sender_blocker') or '').strip()
    return value or '-'


def fmt_bool(value: Any) -> str:
    if isinstance(value, bool):
        return '1' if value else '0'
    return '-'


def runtime_bucket(entry: dict[str, Any]) -> str:
    tier = str(entry.get('bridge_capability_tier') or '').strip() or 'unknown'
    skips = str(entry.get('bridge_fetch_skips') or '').strip()
    modes = str(entry.get('bridge_modes') or '').strip()
    native_sender_blocker = normalize_native_sender_blocker(entry)

    if native_sender_blocker and native_sender_blocker != '-':
        if native_sender_blocker in {
            'native_sender.encoder_pipeline_missing',
            'native_sender.webrtc_transport_missing',
            'native_sender.peer_connection_runtime_missing',
            'native_sender.track_publish_missing',
            'native_sender.video_encoder_missing',
            'native_sender.shadow_signaling_ownership_missing',
        }:
            return 'native_sender_pipeline_missing'
        return 'native_sender_blocked_other'

    if tier == 'no_canvas_ready':
        return 'native_no_canvas_ready'
    if 'unsupported_track_generator' in skips and tier == 'unknown':
        return 'untagged_track_generator_blocked'
    if 'unsupported_track_generator' in skips:
        return 'capability_blocked_track_generator'
    if tier == 'capability_blocked':
        return 'capability_blocked_other'
    if tier == 'unknown':
        return 'untagged_legacy_metrics'
    if 'image_canvas' in modes or 'video_canvas' in modes:
        return 'canvas_workaround_only'
    return 'unclassified'


def parse_metrics(path: Path) -> list[dict[str, Any]]:
    sessions: list[dict[str, Any]] = []
    if not path.exists():
        raise FileNotFoundError(f'log not found: {path}')

    with path.open('r', encoding='utf-8', errors='replace') as handle:
        for raw in handle:
            line = raw.strip()
            if not line.startswith('{'):
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if payload.get('event') != 'session.metrics.combined':
                continue
            sessions.append(payload)
    return sessions


def print_table(headers: list[str], rows: list[list[str]]) -> None:
    widths = [len(h) for h in headers]
    for row in rows:
        for i, col in enumerate(row):
            widths[i] = max(widths[i], len(col))

    def render(cols: list[str]) -> str:
        return ' | '.join(col.ljust(widths[i]) for i, col in enumerate(cols))

    print(render(headers))
    print('-+-'.join('-' * width for width in widths))
    for row in rows:
        print(render(row))


def build_report(sessions: list[dict[str, Any]], recent_limit: int) -> None:
    print('=== Runtime Capability Matrix (from session.metrics.combined) ===')
    print(f'total_sessions: {len(sessions)}')
    tagged_sessions = sum(1 for item in sessions if str(item.get('bridge_capability_tier') or '').strip())
    print(f'tier_tagged_sessions: {tagged_sessions}')
    print(f'tier_tagged_share_pct: {fmt_num(tagged_sessions * 100.0 / max(len(sessions), 1))}')
    print('')

    tier_counts = Counter(str(item.get('bridge_capability_tier') or 'unknown') for item in sessions)
    bucket_counts = Counter(runtime_bucket(item) for item in sessions)
    quality_counts = Counter(str(item.get('session_quality_hint') or 'unknown') for item in sessions)
    path_counts = Counter(str(item.get('candidate_tier') or item.get('candidate_tier_last') or 'unknown') for item in sessions)
    runtime_kernel_counts = Counter(normalize_runtime_kernel(item) for item in sessions)
    runtime_signature_counts = Counter(
        f"{normalize_runtime_kernel(item)} || {normalize_runtime_cap_sig(item)}"
        for item in sessions
    )
    native_sender_support_counts = Counter(normalize_native_sender_support_level(item) for item in sessions)
    native_sender_blocker_counts = Counter(normalize_native_sender_blocker(item) for item in sessions)

    print('Tier Distribution:')
    print_table(
        ['tier', 'sessions', 'share_pct'],
        [[tier, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for tier, count in tier_counts.most_common()],
    )
    print('')

    print('Runtime Bucket Distribution:')
    print_table(
        ['bucket', 'sessions', 'share_pct'],
        [[bucket, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for bucket, count in bucket_counts.most_common()],
    )
    print('')

    print('Quality Hint Distribution:')
    print_table(
        ['quality_hint', 'sessions', 'share_pct'],
        [[hint, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for hint, count in quality_counts.most_common()],
    )
    print('')

    print('Candidate Tier Distribution:')
    print_table(
        ['candidate_tier', 'sessions', 'share_pct'],
        [[tier, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for tier, count in path_counts.most_common()],
    )
    print('')

    print('Runtime Kernel Distribution:')
    print_table(
        ['runtime_kernel', 'sessions', 'share_pct'],
        [[kernel, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for kernel, count in runtime_kernel_counts.most_common()],
    )
    print('')

    print('Runtime Signature Distribution:')
    print_table(
        ['runtime_signature', 'sessions', 'share_pct'],
        [[signature, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for signature, count in runtime_signature_counts.most_common()],
    )
    print('')

    print('Native Sender Support Level Distribution:')
    print_table(
        ['native_sender_support_level', 'sessions', 'share_pct'],
        [[level, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for level, count in native_sender_support_counts.most_common()],
    )
    print('')

    print('Native Sender Blocker Distribution:')
    print_table(
        ['native_sender_blocker', 'sessions', 'share_pct'],
        [[blocker, str(count), fmt_num(count * 100.0 / max(len(sessions), 1))] for blocker, count in native_sender_blocker_counts.most_common()],
    )
    print('')

    recent = sorted(sessions, key=lambda item: str(item.get('ts') or ''))[-recent_limit:]
    print(f'Recent Sessions (last {len(recent)}):')
    recent_rows: list[list[str]] = []
    for item in recent:
        recent_rows.append([
            str(item.get('ts') or '-'),
            str(item.get('session_id') or '-'),
            str(item.get('bridge_capability_tier') or '-'),
            str(item.get('session_quality_hint') or '-'),
            fmt_num(item.get('first_frame_ms'), 0),
            fmt_num(item.get('render_fps_avg'), 2),
            fmt_num(item.get('send_fps'), 2),
            fmt_num(item.get('recv_kbps_avg'), 1),
            fmt_num(item.get('send_kbps'), 1),
            str(item.get('candidate_tier') or item.get('candidate_tier_last') or '-'),
            normalize_runtime_kernel(item),
            normalize_runtime_cap_sig(item),
            fmt_bool(item.get('runtime_cap_native_sender')),
            normalize_native_sender_support_level(item),
            normalize_native_sender_blocker(item),
            str(item.get('native_sender_lifecycle') or '-'),
            str(item.get('native_sender_signaling_state') or '-'),
            fmt_num(item.get('native_sender_probe_fps'), 2),
            fmt_num(item.get('native_sender_probe_kbps'), 1),
            str(item.get('bridge_fetch_skips') or '-'),
            str(item.get('bridge_modes') or '-'),
        ])
    print_table(
        [
            'ts', 'session_id', 'capability_tier', 'quality_hint',
            'first_frame_ms', 'render_fps', 'send_fps', 'recv_kbps', 'send_kbps',
            'candidate_tier', 'runtime_kernel', 'runtime_cap_sig',
            'native_sender', 'native_sender_level', 'native_sender_blocker',
            'ns_lifecycle', 'ns_signaling', 'ns_probe_fps', 'ns_probe_kbps',
            'fetch_skips', 'bridge_modes',
        ],
        recent_rows,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description='Build runtime capability matrix from relay logs.')
    parser.add_argument('--log', type=Path, default=DEFAULT_LOG, help='Path to relay.log')
    parser.add_argument('--recent', type=int, default=6, help='How many recent sessions to print')
    args = parser.parse_args()

    sessions = parse_metrics(args.log)
    if not sessions:
        print(f'No session.metrics.combined entries found in {args.log}')
        return 0
    build_report(sessions, max(args.recent, 1))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
