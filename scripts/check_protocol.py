#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "packages" / "protocol"

version_file = PROTOCOL / "version" / "protocol_version.json"
messages_file = PROTOCOL / "schema" / "messages.json"
errors_file = PROTOCOL / "schema" / "error_codes.json"
fixtures_dir = PROTOCOL / "fixtures"

for path in (version_file, messages_file, errors_file):
    if not path.exists():
        raise SystemExit(f"missing required file: {path}")

version = json.loads(version_file.read_text())
messages = json.loads(messages_file.read_text())
errors = json.loads(errors_file.read_text())

if version.get("version") != "1.0":
    raise SystemExit("unexpected protocol version")

message_defs = messages.get("messages", [])
message_types = [item["type"] for item in message_defs]
if len(message_types) != len(set(message_types)):
    raise SystemExit("duplicate message type found")

for item in message_defs:
    if "type" not in item or "payload" not in item:
        raise SystemExit("every message definition must include type and payload")
    for field in item.get("required", []):
        if field not in item["payload"]:
            raise SystemExit(f"required field '{field}' missing from payload schema for {item['type']}")

codes = [item["code"] for item in errors.get("errors", [])]
if len(codes) != len(set(codes)):
    raise SystemExit("duplicate error code found")

for fixture in fixtures_dir.glob("*.json"):
    data = json.loads(fixture.read_text())
    msg_type = data.get("type")
    if msg_type not in message_types:
        raise SystemExit(f"fixture {fixture.name} references unknown message type {msg_type}")

print("protocol check passed")
