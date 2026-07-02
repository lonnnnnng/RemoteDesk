INSERT INTO devices (device_id, user_id, platform, client_version, device_name, role)
VALUES ('seed-agent-001', 'seed-user', 'macos', '0.1.1', 'Seed Agent', 'agent')
ON CONFLICT (device_id) DO NOTHING;
