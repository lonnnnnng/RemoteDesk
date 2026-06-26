use serde::Serialize;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub mod capture;
pub mod codec;
pub mod config;
pub mod controller;
pub mod core;
pub mod host;
pub mod native_sender;
pub mod platform;
pub mod transport;

#[derive(Serialize)]
struct BootstrapContext {
    runtime: &'static str,
    platform: &'static str,
    protocol_version: &'static str,
    default_ws_url: &'static str,
    default_codec: &'static str,
    auto_connect: bool,
    auto_register: bool,
    auto_heartbeat: bool,
}

#[derive(Serialize)]
pub struct DiagnosticCheck {
    pub name: &'static str,
    pub ok: bool,
    pub status: &'static str,
    pub detail: String,
}

#[derive(Serialize)]
pub struct DesktopSelfTestReport {
    pub platform: &'static str,
    pub ok: bool,
    pub duration_ms: u64,
    pub capture_backend: String,
    pub host_input_backend: String,
    pub source_id: String,
    pub source_title: String,
    pub frame_width: u32,
    pub frame_height: u32,
    pub frame_mime_type: String,
    pub frame_bytes: usize,
    pub stream_endpoint: String,
    pub native_sender_support_level: String,
    pub native_sender_lifecycle: String,
    pub native_sender_signal_count: u64,
    pub native_sender_offer_count: u64,
    pub native_sender_probe_frame_count: u64,
    pub native_sender_probe_total_bytes: u64,
    pub native_sender_last_error_code: String,
    pub native_sender_last_error_detail: String,
    pub checks: Vec<DiagnosticCheck>,
}

pub type WindowsSelfTestReport = DesktopSelfTestReport;

fn env_flag(name: &str, fallback: bool) -> bool {
    match std::env::var(name).ok().as_deref() {
        Some("1") | Some("true") | Some("TRUE") | Some("yes") | Some("YES") => true,
        Some("0") | Some("false") | Some("FALSE") | Some("no") | Some("NO") => false,
        Some(_) => fallback,
        None => fallback,
    }
}

#[tauri::command]
fn bootstrap_status() -> &'static str {
    "remote_desk desktop bootstrap ready"
}

#[tauri::command]
fn bootstrap_context() -> BootstrapContext {
    BootstrapContext {
        runtime: "tauri",
        platform: platform::current_platform_stub(),
        protocol_version: config::protocol_version(),
        default_ws_url: transport::websocket_endpoint(),
        default_codec: codec::codec_name(),
        auto_connect: env_flag("RD_DESKTOP_AUTO_CONNECT", true),
        auto_register: env_flag("RD_DESKTOP_AUTO_REGISTER", true),
        auto_heartbeat: env_flag("RD_DESKTOP_AUTO_HEARTBEAT", true),
    }
}

#[tauri::command]
fn debug_log(line: String) -> Result<(), String> {
    let text = line.trim();
    if !text.is_empty() {
        eprintln!("[rd.js] ts_ms={} {text}", now_ms());
    }
    Ok(())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as u64)
        .unwrap_or(0)
}

struct HostInputDispatchStep {
    label: &'static str,
    message_type: &'static str,
    payload: serde_json::Value,
    pause_after_ms: u64,
}

struct HostInputDispatchSequenceReport {
    ok: bool,
    detail: String,
}

fn host_input_dispatch_sequence_steps() -> Vec<HostInputDispatchStep> {
    vec![
        HostInputDispatchStep {
            label: "pointer_move",
            message_type: "input.mouse.move",
            payload: serde_json::json!({ "x": 0.50, "y": 0.50 }),
            pause_after_ms: 20,
        },
        HostInputDispatchStep {
            label: "click_down",
            message_type: "input.mouse.button",
            payload: serde_json::json!({ "button": "left", "action": "down", "x": 0.50, "y": 0.50 }),
            pause_after_ms: 25,
        },
        HostInputDispatchStep {
            label: "click_up",
            message_type: "input.mouse.button",
            payload: serde_json::json!({ "button": "left", "action": "up", "x": 0.50, "y": 0.50 }),
            pause_after_ms: 25,
        },
        HostInputDispatchStep {
            label: "drag_start_move",
            message_type: "input.mouse.move",
            payload: serde_json::json!({ "x": 0.48, "y": 0.48 }),
            pause_after_ms: 20,
        },
        HostInputDispatchStep {
            label: "drag_down",
            message_type: "input.mouse.button",
            payload: serde_json::json!({ "button": "left", "action": "down", "x": 0.48, "y": 0.48 }),
            pause_after_ms: 35,
        },
        HostInputDispatchStep {
            label: "drag_move",
            message_type: "input.mouse.move",
            payload: serde_json::json!({ "x": 0.52, "y": 0.52 }),
            pause_after_ms: 35,
        },
        HostInputDispatchStep {
            label: "drag_up",
            message_type: "input.mouse.button",
            payload: serde_json::json!({ "button": "left", "action": "up", "x": 0.52, "y": 0.52 }),
            pause_after_ms: 25,
        },
        HostInputDispatchStep {
            label: "keyboard_shift_down",
            message_type: "input.keyboard.key",
            payload: serde_json::json!({ "key_code": "ShiftLeft", "action": "down", "modifiers": [] }),
            pause_after_ms: 25,
        },
        HostInputDispatchStep {
            label: "keyboard_shift_up",
            message_type: "input.keyboard.key",
            payload: serde_json::json!({ "key_code": "ShiftLeft", "action": "up", "modifiers": [] }),
            pause_after_ms: 25,
        },
        HostInputDispatchStep {
            label: "wheel_scroll",
            message_type: "input.wheel.scroll",
            payload: serde_json::json!({ "delta_x": 0, "delta_y": -120 }),
            pause_after_ms: 0,
        },
    ]
}

fn apply_host_input_dispatch_sequence(
    session_id: &str,
    controller_id: &str,
) -> HostInputDispatchSequenceReport {
    let steps = host_input_dispatch_sequence_steps();
    let total_steps = steps.len();
    let mut applied_steps = 0usize;
    let mut shift_down = false;

    for step in steps {
        let result = host::apply_input(host::HostInputEnvelope {
            message_type: step.message_type.to_string(),
            session_id: session_id.to_string(),
            trace_id: format!("desktop-self-test-host-{}", step.label),
            sender_device_id: controller_id.to_string(),
            sender_role: "controller".to_string(),
            payload: step.payload,
        });
        if result.applied {
            applied_steps += 1;
            if step.label == "keyboard_shift_down" {
                shift_down = true;
            } else if step.label == "keyboard_shift_up" {
                shift_down = false;
            }
            if step.pause_after_ms > 0 {
                std::thread::sleep(Duration::from_millis(step.pause_after_ms));
            }
            continue;
        }

        if shift_down {
            let _ = host::apply_input(host::HostInputEnvelope {
                message_type: "input.keyboard.key".to_string(),
                session_id: session_id.to_string(),
                trace_id: "desktop-self-test-host-keyboard-shift-cleanup".to_string(),
                sender_device_id: controller_id.to_string(),
                sender_role: "controller".to_string(),
                payload: serde_json::json!({ "key_code": "ShiftLeft", "action": "up", "modifiers": [] }),
            });
        }

        return HostInputDispatchSequenceReport {
            ok: false,
            detail: format!(
                "failed_step={} applied={}/{} type={} executor={} status={} error={} detail={}",
                step.label,
                applied_steps,
                total_steps,
                result.message_type,
                result.executor,
                result.status_code,
                if result.error_code.is_empty() {
                    "-"
                } else {
                    result.error_code.as_str()
                },
                if result.error_detail.is_empty() {
                    result.status_detail.as_str()
                } else {
                    result.error_detail.as_str()
                }
            ),
        };
    }

    HostInputDispatchSequenceReport {
        ok: true,
        detail: format!(
            "applied={}/{} sequence=pointer_move,left_click,left_drag,keyboard_shift,wheel_scroll",
            applied_steps, total_steps
        ),
    }
}

#[tauri::command]
fn host_bridge_status() -> host::HostBridgeStatus {
    host::host_bridge_status()
}

#[tauri::command]
fn host_apply_input(envelope: host::HostInputEnvelope) -> host::HostInputResult {
    host::apply_input(envelope)
}

#[tauri::command]
fn host_sync_session(
    context: Option<host::HostSessionContext>,
) -> Result<host::HostBridgeStatus, String> {
    host::sync_session(context)
}

#[tauri::command]
fn platform_get_capabilities() -> platform::DesktopPlatformCapabilities {
    platform::platform_get_capabilities()
}

#[tauri::command]
fn windows_self_test() -> WindowsSelfTestReport {
    run_windows_self_test()
}

#[tauri::command]
fn desktop_self_test() -> DesktopSelfTestReport {
    run_desktop_self_test()
}

pub fn run_windows_self_test() -> WindowsSelfTestReport {
    run_desktop_self_test()
}

pub fn run_desktop_self_test() -> DesktopSelfTestReport {
    let started_at = Instant::now();
    let mut checks = Vec::new();
    let mut source_id = String::new();
    let mut source_title = String::new();
    let mut frame_width = 0;
    let mut frame_height = 0;
    let mut frame_mime_type = String::new();
    let mut frame_bytes = 0;
    let mut stream_endpoint = String::new();
    let mut native_sender_support_level = String::new();
    let mut native_sender_lifecycle = String::new();
    let mut native_sender_signal_count = 0;
    let mut native_sender_offer_count = 0;
    let mut native_sender_probe_frame_count = 0;
    let mut native_sender_probe_total_bytes = 0;
    let mut native_sender_last_error_code = String::new();
    let mut native_sender_last_error_detail = String::new();
    let mut capture_started = false;
    let original_capture_status = capture::capture_status().ok();
    let original_capture_config = original_capture_status
        .as_ref()
        .map(|status| status.config.clone());
    let original_capture_source_id = original_capture_status
        .as_ref()
        .and_then(|status| status.active_source.as_ref())
        .map(|source| source.source_id.clone());
    let original_capture_was_active = original_capture_status
        .as_ref()
        .map(|status| matches!(status.lifecycle.as_str(), "ready" | "running" | "paused"))
        .unwrap_or(false);

    let platform_name = platform::current_platform_stub();
    let desktop_platform = platform::current_platform();
    let platform_ok = matches!(
        desktop_platform,
        platform::DesktopPlatform::Macos | platform::DesktopPlatform::Windows
    );
    checks.push(DiagnosticCheck {
        name: "platform",
        ok: platform_ok,
        status: if platform_ok { "passed" } else { "failed" },
        detail: match desktop_platform {
            platform::DesktopPlatform::Macos => "running on macOS desktop shell".to_string(),
            platform::DesktopPlatform::Windows => "running on Windows desktop shell".to_string(),
            platform::DesktopPlatform::Unsupported => {
                format!("desktop self-test is only available on Windows or macOS, current={platform_name}")
            }
        },
    });

    let capabilities = platform::platform_get_capabilities();
    checks.push(DiagnosticCheck {
        name: "capabilities.capture",
        ok: capabilities.capture.support_level == "implemented"
            && capabilities.capture.supports_source_listing
            && capabilities.capture.supports_frame_streaming,
        status: "checked",
        detail: format!(
            "backend={} support={} listing={} streaming={}",
            capabilities.capture.backend,
            capabilities.capture.support_level,
            capabilities.capture.supports_source_listing,
            capabilities.capture.supports_frame_streaming
        ),
    });
    checks.push(DiagnosticCheck {
        name: "capabilities.host_input",
        ok: capabilities.host_input.support_level == "implemented"
            && capabilities.host_input.supports_pointer_input
            && capabilities.host_input.supports_keyboard_input
            && capabilities.host_input.supports_wheel_input,
        status: "checked",
        detail: format!(
            "backend={} support={} pointer={} keyboard={} wheel={}",
            capabilities.host_input.backend,
            capabilities.host_input.support_level,
            capabilities.host_input.supports_pointer_input,
            capabilities.host_input.supports_keyboard_input,
            capabilities.host_input.supports_wheel_input
        ),
    });
    let host_input_permission = capabilities.host_input.permission.clone();
    let host_input_permission_ok =
        !capabilities.host_input.requires_permission || host_input_permission.status == "granted";
    checks.push(DiagnosticCheck {
        name: "host_input.permission",
        ok: host_input_permission_ok,
        status: host_input_permission.status,
        detail: host_input_permission.detail,
    });

    let _ = host::sync_session(None);
    let guard_result = host::apply_input(host::HostInputEnvelope {
        message_type: "input.mouse.move".to_string(),
        session_id: "desktop-self-test-session".to_string(),
        trace_id: "desktop-self-test-host-guard".to_string(),
        sender_device_id: "desktop-self-test-controller".to_string(),
        sender_role: "controller".to_string(),
        payload: serde_json::json!({ "x": 0.5, "y": 0.5 }),
    });
    let guard_ok = !guard_result.applied && guard_result.error_code == "input.session.not_ready";
    checks.push(DiagnosticCheck {
        name: "host_input.session_guard",
        ok: guard_ok,
        status: if guard_ok { "passed" } else { "failed" },
        detail: format!(
            "applied={} executor={} status={} error={}",
            guard_result.applied,
            guard_result.executor,
            guard_result.status_code,
            if guard_result.error_code.is_empty() {
                "-"
            } else {
                guard_result.error_code.as_str()
            }
        ),
    });
    let _ = host::sync_session(None);

    let dispatch_probe_enabled = env_flag(
        "RD_DESKTOP_SELF_TEST_APPLY_INPUT",
        env_flag("RD_WINDOWS_SELF_TEST_APPLY_INPUT", false),
    );

    if platform_ok {
        let permission = capture::capture_get_permission_state();
        checks.push(DiagnosticCheck {
            name: "capture.permission",
            ok: permission.status == "granted",
            status: permission.status,
            detail: permission.detail,
        });

        let sources = capture::capture_list_sources();
        match sources {
            Ok(sources) => {
                let selected = sources
                    .iter()
                    .find(|source| source.is_primary)
                    .or_else(|| sources.first())
                    .cloned();
                checks.push(DiagnosticCheck {
                    name: "capture.sources",
                    ok: selected.is_some(),
                    status: if selected.is_some() {
                        "passed"
                    } else {
                        "failed"
                    },
                    detail: format!("available_sources={}", sources.len()),
                });

                if let Some(source) = selected {
                    source_id = source.source_id.clone();
                    source_title = source.title.clone();
                    let start_status = capture::capture_start(Some(capture::CaptureStartRequest {
                        source_id: source.source_id.clone(),
                        config: Some(capture::CaptureConfigPatch {
                            max_width: Some(960),
                            max_height: Some(540),
                            max_fps: Some(8),
                            codec: Some("jpeg-frame-stream".to_string()),
                        }),
                    }));
                    let start_ok = start_status
                        .as_ref()
                        .map(|status| status.active_source.is_some())
                        .unwrap_or(false);
                    capture_started = start_ok;
                    checks.push(DiagnosticCheck {
                        name: "capture.start",
                        ok: start_ok,
                        status: if start_ok { "passed" } else { "failed" },
                        detail: match start_status {
                            Ok(status) => format!(
                                "lifecycle={} active_source={} error={}",
                                status.lifecycle,
                                status
                                    .active_source
                                    .as_ref()
                                    .map(|value| value.source_id.as_str())
                                    .unwrap_or("-"),
                                if status.last_error_detail.is_empty() {
                                    "-"
                                } else {
                                    status.last_error_detail.as_str()
                                }
                            ),
                            Err(error) => error,
                        },
                    });

                    if start_ok {
                        match capture::capture_take_frame_bytes() {
                            Ok(frame) => {
                                frame_width = frame.frame_width;
                                frame_height = frame.frame_height;
                                frame_mime_type = frame.mime_type.to_string();
                                frame_bytes = frame.encoded_bytes.len();
                                checks.push(DiagnosticCheck {
                                    name: "capture.frame",
                                    ok: frame.frame_width > 0
                                        && frame.frame_height > 0
                                        && !frame.encoded_bytes.is_empty(),
                                    status: "passed",
                                    detail: format!(
                                        "mime={} size={}x{} bytes={}",
                                        frame.mime_type,
                                        frame.frame_width,
                                        frame.frame_height,
                                        frame.encoded_bytes.len()
                                    ),
                                });
                            }
                            Err(error) => checks.push(DiagnosticCheck {
                                name: "capture.frame",
                                ok: false,
                                status: "failed",
                                detail: error,
                            }),
                        }

                        match capture::capture_get_stream_endpoint() {
                            Ok(endpoint) => {
                                stream_endpoint = endpoint.url;
                                checks.push(DiagnosticCheck {
                                    name: "capture.stream_endpoint",
                                    ok: stream_endpoint.starts_with("http://127.0.0.1:"),
                                    status: "passed",
                                    detail: format!("boundary={}", endpoint.boundary),
                                });
                            }
                            Err(error) => checks.push(DiagnosticCheck {
                                name: "capture.stream_endpoint",
                                ok: false,
                                status: "failed",
                                detail: error,
                            }),
                        }
                    }
                }
            }
            Err(error) => checks.push(DiagnosticCheck {
                name: "capture.sources",
                ok: false,
                status: "failed",
                detail: error,
            }),
        }
    }

    if platform_ok {
        if dispatch_probe_enabled {
            let can_run_dispatch_probe = match desktop_platform {
                platform::DesktopPlatform::Macos => capture_started,
                platform::DesktopPlatform::Windows => true,
                platform::DesktopPlatform::Unsupported => false,
            };
            if can_run_dispatch_probe {
                let session_id = "desktop-self-test-dispatch-session".to_string();
                let controller_id = "desktop-self-test-controller".to_string();
                let agent_id = "desktop-self-test-agent".to_string();
                let _ = host::sync_session(Some(host::HostSessionContext {
                    session_id: session_id.clone(),
                    controller_device_id: controller_id.clone(),
                    agent_device_id: agent_id.clone(),
                    local_device_id: agent_id,
                }));
                let dispatch_result =
                    apply_host_input_dispatch_sequence(&session_id, &controller_id);
                checks.push(DiagnosticCheck {
                    name: "host_input.dispatch_sequence",
                    ok: dispatch_result.ok,
                    status: if dispatch_result.ok {
                        "passed"
                    } else {
                        "failed"
                    },
                    detail: dispatch_result.detail,
                });
                let _ = host::sync_session(None);
            } else {
                checks.push(DiagnosticCheck {
                    name: "host_input.dispatch_sequence",
                    ok: false,
                    status: "failed",
                    detail: "real dispatch probe requires a started capture source on macOS so pointer coordinates can be mapped".to_string(),
                });
            }
        } else {
            checks.push(DiagnosticCheck {
                name: "host_input.dispatch_sequence",
                ok: true,
                status: "skipped",
                detail: "set RD_DESKTOP_SELF_TEST_APPLY_INPUT=1 to apply real pointer, click, drag, keyboard, and wheel input through the host input bridge".to_string(),
            });
        }

        let sender_capabilities = native_sender::native_sender_get_capabilities();
        native_sender_support_level = sender_capabilities.support_level.clone();
        checks.push(DiagnosticCheck {
            name: "native_sender.capabilities",
            ok: sender_capabilities.supported,
            status: "checked",
            detail: format!(
                "support={} capture_backend={} capture_support={} blocker={}",
                sender_capabilities.support_level,
                sender_capabilities.capture_backend,
                sender_capabilities.capture_support_level,
                sender_capabilities.blocker_code
            ),
        });

        if capture_started && sender_capabilities.supported {
            let sender_session_id = format!("desktop-self-test-native-sender-{}", now_ms());
            let start_result =
                native_sender::native_sender_start(native_sender::NativeSenderStartRequest {
                    session_id: sender_session_id.clone(),
                    dry_run: false,
                    ice_servers: Vec::new(),
                });
            match start_result {
                Ok(start_status) => {
                    let start_ok = start_status.lifecycle == "running"
                        && start_status.shadow_runtime_ready
                        && start_status.shadow_track_bound
                        && start_status.local_offer_count > 0;
                    checks.push(DiagnosticCheck {
                        name: "native_sender.start",
                        ok: start_ok,
                        status: if start_ok { "passed" } else { "failed" },
                        detail: format!(
                            "lifecycle={} shadow_runtime={} shadow_track={} offers={} signals={} error={}",
                            start_status.lifecycle,
                            start_status.shadow_runtime_ready,
                            start_status.shadow_track_bound,
                            start_status.local_offer_count,
                            start_status.signal_count,
                            if start_status.last_error_detail.is_empty() {
                                "-"
                            } else {
                                start_status.last_error_detail.as_str()
                            }
                        ),
                    });

                    match native_sender::native_sender_drain_outbound_signals(
                        native_sender::NativeSenderDrainSignalsRequest {
                            session_id: sender_session_id.clone(),
                            limit: 16,
                        },
                    ) {
                        Ok(signals) => {
                            let offer_signal = signals.iter().find(|signal| {
                                signal.signal_type == "webrtc.offer" && !signal.sdp.is_empty()
                            });
                            checks.push(DiagnosticCheck {
                                name: "native_sender.offer_signal",
                                ok: offer_signal.is_some(),
                                status: if offer_signal.is_some() {
                                    "passed"
                                } else {
                                    "failed"
                                },
                                detail: match offer_signal {
                                    Some(signal) => format!(
                                        "drained={} offer_sdp_bytes={} trace={}",
                                        signals.len(),
                                        signal.sdp.len(),
                                        signal.trace_id
                                    ),
                                    None => format!("drained={} offer_sdp_bytes=0", signals.len()),
                                },
                            });
                        }
                        Err(error) => checks.push(DiagnosticCheck {
                            name: "native_sender.offer_signal",
                            ok: false,
                            status: "failed",
                            detail: error,
                        }),
                    }

                    std::thread::sleep(Duration::from_millis(1500));
                    let probe_status = native_sender::native_sender_status();
                    native_sender_lifecycle = probe_status.lifecycle.clone();
                    native_sender_signal_count = probe_status.signal_count;
                    native_sender_offer_count = probe_status.local_offer_count;
                    native_sender_probe_frame_count = probe_status.media_probe_frame_count;
                    native_sender_probe_total_bytes = probe_status.media_probe_total_bytes;
                    native_sender_last_error_code = probe_status.last_error_code.clone();
                    native_sender_last_error_detail = probe_status.last_error_detail.clone();
                    let probe_ok = probe_status.media_probe_frame_count > 0
                        && probe_status.media_probe_total_bytes > 0
                        && !probe_status
                            .last_error_code
                            .starts_with("native_sender.encoder.");
                    checks.push(DiagnosticCheck {
                        name: "native_sender.media_probe",
                        ok: probe_ok,
                        status: if probe_ok { "passed" } else { "failed" },
                        detail: format!(
                            "frames={} bytes={} last_frame={} size={}x{} fps={:.2} kbps={:.1} error_code={} error_detail={}",
                            probe_status.media_probe_frame_count,
                            probe_status.media_probe_total_bytes,
                            probe_status.media_probe_last_frame_ts_ms,
                            probe_status.media_probe_last_width,
                            probe_status.media_probe_last_height,
                            probe_status.media_probe_fps,
                            probe_status.media_probe_kbps,
                            if probe_status.last_error_code.is_empty() {
                                "-"
                            } else {
                                probe_status.last_error_code.as_str()
                            },
                            if probe_status.last_error_detail.is_empty() {
                                "-"
                            } else {
                                probe_status.last_error_detail.as_str()
                            }
                        ),
                    });
                }
                Err(error) => checks.push(DiagnosticCheck {
                    name: "native_sender.start",
                    ok: false,
                    status: "failed",
                    detail: error,
                }),
            }
            let _ = native_sender::native_sender_stop(Some("desktop_self_test".to_string()));
        } else {
            checks.push(DiagnosticCheck {
                name: "native_sender.start",
                ok: true,
                status: "skipped",
                detail: if capture_started {
                    "native sender is not supported in this build".to_string()
                } else {
                    "skipped because capture.start did not pass".to_string()
                },
            });
        }
    }

    if capture_started {
        let _ = capture::capture_stop();
    }
    if let Some(config) = original_capture_config {
        let _ = capture::capture_update_config(capture::CaptureConfigPatch {
            max_width: Some(config.max_width),
            max_height: Some(config.max_height),
            max_fps: Some(config.max_fps),
            codec: Some(config.codec),
        });
    }
    if original_capture_was_active {
        let _ = capture::capture_start(Some(capture::CaptureStartRequest {
            source_id: original_capture_source_id.unwrap_or_default(),
            config: None,
        }));
    }
    let ok = checks.iter().all(|check| check.ok);

    DesktopSelfTestReport {
        platform: platform_name,
        ok,
        duration_ms: started_at.elapsed().as_millis() as u64,
        capture_backend: capabilities.capture.backend.to_string(),
        host_input_backend: capabilities.host_input.backend.to_string(),
        source_id,
        source_title,
        frame_width,
        frame_height,
        frame_mime_type,
        frame_bytes,
        stream_endpoint,
        native_sender_support_level,
        native_sender_lifecycle,
        native_sender_signal_count,
        native_sender_offer_count,
        native_sender_probe_frame_count,
        native_sender_probe_total_bytes,
        native_sender_last_error_code,
        native_sender_last_error_detail,
        checks,
    }
}

#[tauri::command]
fn capture_get_capabilities() -> capture::CaptureCapabilities {
    capture::capture_get_capabilities()
}

#[tauri::command]
fn capture_get_permission_state() -> capture::CapturePermissionState {
    capture::capture_get_permission_state()
}

#[tauri::command]
fn capture_request_permission() -> Result<capture::CapturePermissionState, String> {
    capture::capture_request_permission()
}

#[tauri::command]
fn capture_list_sources() -> Result<Vec<capture::CaptureSourceSummary>, String> {
    capture::capture_list_sources()
}

#[tauri::command]
fn capture_take_frame() -> Result<capture::CaptureFrameEnvelope, String> {
    capture::capture_take_frame()
}

#[tauri::command]
fn capture_get_stream_endpoint() -> Result<capture::CaptureStreamEndpoint, String> {
    capture::capture_get_stream_endpoint()
}

#[tauri::command]
fn capture_start(
    request: Option<capture::CaptureStartRequest>,
) -> Result<capture::CaptureStatus, String> {
    capture::capture_start(request)
}

#[tauri::command]
fn capture_stop() -> Result<capture::CaptureStatus, String> {
    capture::capture_stop()
}

#[tauri::command]
fn capture_pause() -> Result<capture::CaptureStatus, String> {
    capture::capture_pause()
}

#[tauri::command]
fn capture_resume() -> Result<capture::CaptureStatus, String> {
    capture::capture_resume()
}

#[tauri::command]
fn capture_status() -> Result<capture::CaptureStatus, String> {
    capture::capture_status()
}

#[tauri::command]
fn capture_update_config(
    patch: capture::CaptureConfigPatch,
) -> Result<capture::CaptureStatus, String> {
    capture::capture_update_config(patch)
}

#[tauri::command]
fn native_sender_get_capabilities() -> native_sender::NativeSenderCapabilities {
    native_sender::native_sender_get_capabilities()
}

#[tauri::command]
fn native_sender_status() -> native_sender::NativeSenderStatus {
    native_sender::native_sender_status()
}

#[tauri::command]
fn native_sender_start(
    request: native_sender::NativeSenderStartRequest,
) -> Result<native_sender::NativeSenderStatus, String> {
    native_sender::native_sender_start(request)
}

#[tauri::command]
fn native_sender_stop(reason: Option<String>) -> Result<native_sender::NativeSenderStatus, String> {
    native_sender::native_sender_stop(reason)
}

#[tauri::command]
fn native_sender_push_signal(
    signal: native_sender::NativeSenderSignalEnvelope,
) -> Result<native_sender::NativeSenderStatus, String> {
    native_sender::native_sender_push_signal(signal)
}

#[tauri::command]
fn native_sender_create_offer(
    request: native_sender::NativeSenderCreateOfferRequest,
) -> Result<native_sender::NativeSenderStatus, String> {
    native_sender::native_sender_create_offer(request)
}

#[tauri::command]
fn native_sender_drain_outbound_signals(
    request: native_sender::NativeSenderDrainSignalsRequest,
) -> Result<Vec<native_sender::NativeSenderOutgoingSignal>, String> {
    native_sender::native_sender_drain_outbound_signals(request)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            bootstrap_status,
            bootstrap_context,
            debug_log,
            host_bridge_status,
            host_apply_input,
            host_sync_session,
            platform_get_capabilities,
            desktop_self_test,
            windows_self_test,
            capture_get_capabilities,
            capture_get_permission_state,
            capture_request_permission,
            capture_list_sources,
            capture_take_frame,
            capture_get_stream_endpoint,
            capture_start,
            capture_stop,
            capture_pause,
            capture_resume,
            capture_status,
            capture_update_config,
            native_sender_get_capabilities,
            native_sender_status,
            native_sender_start,
            native_sender_stop,
            native_sender_push_signal,
            native_sender_create_offer,
            native_sender_drain_outbound_signals
        ])
        .run(tauri::generate_context!())
        .expect("failed to run tauri application");
}
