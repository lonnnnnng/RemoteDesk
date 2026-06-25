use crate::platform::{self, HostInputCommand, InputAction, PointerButton};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostInputEnvelope {
    pub message_type: String,
    pub session_id: String,
    #[serde(default)]
    pub trace_id: String,
    #[serde(default)]
    pub sender_device_id: String,
    #[serde(default)]
    pub sender_role: String,
    #[serde(default)]
    pub payload: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostSessionContext {
    pub session_id: String,
    pub controller_device_id: String,
    pub agent_device_id: String,
    pub local_device_id: String,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct HostBridgeStatus {
    pub input_count: u64,
    pub last_input_type: String,
    pub last_input_summary: String,
    pub last_session_id: String,
    pub last_trace_id: String,
    pub last_executor: String,
    pub last_status_code: String,
    pub last_status_detail: String,
    pub last_error_code: String,
    pub last_error_detail: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct HostInputResult {
    pub applied: bool,
    pub message_type: String,
    pub session_id: String,
    pub trace_id: String,
    pub summary: String,
    pub input_count: u64,
    pub executor: String,
    pub status_code: String,
    pub status_detail: String,
    pub error_code: String,
    pub error_detail: String,
}

static HOST_BRIDGE_STATE: OnceLock<Mutex<HostBridgeStatus>> = OnceLock::new();
static HOST_SESSION_CONTEXT: OnceLock<Mutex<Option<HostSessionContext>>> = OnceLock::new();
const MIN_POINTER_COORDINATE: f64 = 0.0;
const MAX_POINTER_COORDINATE: f64 = 1.0;
const MAX_WHEEL_DELTA_ABS: f64 = 4096.0;

fn trace_host(event: &str, detail: impl AsRef<str>) {
    eprintln!("[rd.host] ts_ms={} {event} {}", now_ms(), detail.as_ref());
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as u64)
        .unwrap_or(0)
}

pub fn stub_host_status() -> &'static str {
    "host stub ready"
}

pub fn host_bridge_status() -> HostBridgeStatus {
    host_bridge_state()
        .lock()
        .expect("host bridge state poisoned")
        .clone()
}

pub fn sync_session(context: Option<HostSessionContext>) -> Result<HostBridgeStatus, String> {
    let normalized_context = match context {
        Some(context) => Some(normalize_session_context(context)?),
        None => None,
    };

    {
        let mut session_context = host_session_context_state()
            .lock()
            .map_err(|_| "host session context poisoned".to_string())?;
        *session_context = normalized_context;
    }
    if let Ok(context_state) = host_session_context_state().lock() {
        if let Some(context) = context_state.as_ref() {
            trace_host(
                "session_synced",
                format!(
                    "session={} controller={} agent={} local={}",
                    context.session_id,
                    context.controller_device_id,
                    context.agent_device_id,
                    context.local_device_id
                ),
            );
        } else {
            trace_host("session_cleared", "no active session context");
        }
    }

    let mut state = host_bridge_state()
        .lock()
        .map_err(|_| "host bridge state poisoned".to_string())?;
    *state = HostBridgeStatus::default();
    Ok(state.clone())
}

pub fn apply_input(envelope: HostInputEnvelope) -> HostInputResult {
    let HostInputEnvelope {
        message_type,
        session_id,
        trace_id,
        sender_device_id,
        sender_role,
        payload,
    } = envelope;

    let session_id = session_id.trim().to_string();
    let message_type = message_type.trim().to_string();
    let trace_id = trace_id.trim().to_string();
    let sender_device_id = sender_device_id.trim().to_string();
    let sender_role = sender_role.trim().to_string();
    trace_host(
        "input_received",
        format!(
            "type={} session={} trace={} sender={} role={}",
            message_type, session_id, trace_id, sender_device_id, sender_role
        ),
    );

    let mut result = if session_id.is_empty() {
        HostInputResult {
            applied: false,
            message_type: message_type.clone(),
            session_id,
            trace_id,
            summary: "host input 请求缺少 session_id".to_string(),
            input_count: 0,
            executor: "host.bridge".to_string(),
            status_code: "input.session_id.required".to_string(),
            status_detail: "session_id is required".to_string(),
            error_code: "input.session_id.required".to_string(),
            error_detail: "session_id is required".to_string(),
        }
    } else if message_type.is_empty() {
        HostInputResult {
            applied: false,
            message_type,
            session_id,
            trace_id,
            summary: "host input 请求缺少 message_type".to_string(),
            input_count: 0,
            executor: "host.bridge".to_string(),
            status_code: "input.message_type.required".to_string(),
            status_detail: "message_type is required".to_string(),
            error_code: "input.message_type.required".to_string(),
            error_detail: "message_type is required".to_string(),
        }
    } else if !is_supported_input_message_type(&message_type) {
        HostInputResult {
            applied: false,
            message_type: message_type.clone(),
            session_id,
            trace_id,
            summary: format!("不支持的输入类型 {}", message_type),
            input_count: 0,
            executor: "host.bridge".to_string(),
            status_code: "input.message_type.unsupported".to_string(),
            status_detail: format!("unsupported input message type: {message_type}"),
            error_code: "input.message_type.unsupported".to_string(),
            error_detail: format!("unsupported input message type: {message_type}"),
        }
    } else if sender_device_id.is_empty() {
        HostInputResult {
            applied: false,
            message_type: message_type.clone(),
            session_id,
            trace_id,
            summary: format!("{} 缺少发送方设备标识", message_type),
            input_count: 0,
            executor: "host.bridge".to_string(),
            status_code: "input.sender_device.required".to_string(),
            status_detail: "sender_device_id is required".to_string(),
            error_code: "input.sender_device.required".to_string(),
            error_detail: "sender_device_id is required".to_string(),
        }
    } else {
        match validate_input_session(&session_id, &sender_device_id) {
            Ok(()) => match parse_input(&message_type, &payload) {
                Ok(input) => {
                    let summary = summarize_input(&input);
                    match platform::dispatch_host_input(&input) {
                        Ok(dispatch) => HostInputResult {
                            applied: dispatch.applied,
                            message_type,
                            session_id,
                            trace_id,
                            summary,
                            input_count: 0,
                            executor: dispatch.executor.to_string(),
                            status_code: dispatch.status_code.to_string(),
                            status_detail: dispatch.status_detail,
                            error_code: String::new(),
                            error_detail: String::new(),
                        },
                        Err(error) => HostInputResult {
                            applied: false,
                            message_type,
                            session_id,
                            trace_id,
                            summary,
                            input_count: 0,
                            executor: error.executor.to_string(),
                            status_code: error.code.to_string(),
                            status_detail: error.detail.clone(),
                            error_code: error.code.to_string(),
                            error_detail: error.detail,
                        },
                    }
                }
                Err(detail) => HostInputResult {
                    applied: false,
                    message_type: message_type.clone(),
                    session_id,
                    trace_id,
                    summary: format!("{} payload 无效", message_type),
                    input_count: 0,
                    executor: "host.bridge".to_string(),
                    status_code: "input.payload.invalid".to_string(),
                    status_detail: detail.clone(),
                    error_code: "input.payload.invalid".to_string(),
                    error_detail: detail,
                },
            },
            Err((code, detail)) => HostInputResult {
                applied: false,
                message_type: message_type.clone(),
                session_id,
                trace_id,
                summary: format!("{} 未通过会话校验", message_type),
                input_count: 0,
                executor: "host.bridge".to_string(),
                status_code: code.to_string(),
                status_detail: detail.clone(),
                error_code: code.to_string(),
                error_detail: detail,
            },
        }
    };

    let mut state = host_bridge_state()
        .lock()
        .expect("host bridge state poisoned");
    if result.applied {
        state.input_count += 1;
    }
    result.input_count = state.input_count;
    state.last_input_type = result.message_type.clone();
    state.last_input_summary = result.summary.clone();
    state.last_session_id = result.session_id.clone();
    state.last_trace_id = result.trace_id.clone();
    state.last_executor = result.executor.clone();
    state.last_status_code = result.status_code.clone();
    state.last_status_detail = result.status_detail.clone();
    state.last_error_code = result.error_code.clone();
    state.last_error_detail = result.error_detail.clone();
    trace_host(
        "input_result",
        format!(
            "type={} session={} applied={} executor={} status={} error={}",
            result.message_type,
            result.session_id,
            result.applied,
            result.executor,
            result.status_code,
            if result.error_code.is_empty() {
                "-"
            } else {
                result.error_code.as_str()
            }
        ),
    );

    result
}

fn host_bridge_state() -> &'static Mutex<HostBridgeStatus> {
    HOST_BRIDGE_STATE.get_or_init(|| Mutex::new(HostBridgeStatus::default()))
}

fn host_session_context_state() -> &'static Mutex<Option<HostSessionContext>> {
    HOST_SESSION_CONTEXT.get_or_init(|| Mutex::new(None))
}

fn normalize_session_context(context: HostSessionContext) -> Result<HostSessionContext, String> {
    let context = HostSessionContext {
        session_id: context.session_id.trim().to_string(),
        controller_device_id: context.controller_device_id.trim().to_string(),
        agent_device_id: context.agent_device_id.trim().to_string(),
        local_device_id: context.local_device_id.trim().to_string(),
    };

    if context.session_id.is_empty() {
        return Err("host session context requires session_id".to_string());
    }
    if context.controller_device_id.is_empty() {
        return Err("host session context requires controller_device_id".to_string());
    }
    if context.agent_device_id.is_empty() {
        return Err("host session context requires agent_device_id".to_string());
    }
    if context.local_device_id.is_empty() {
        return Err("host session context requires local_device_id".to_string());
    }

    Ok(context)
}

fn validate_input_session(
    session_id: &str,
    sender_device_id: &str,
) -> Result<(), (&'static str, String)> {
    let session_context = host_session_context_state().lock().map_err(|_| {
        (
            "input.session.context_unavailable",
            "host session context poisoned".to_string(),
        )
    })?;
    let Some(context) = session_context.as_ref() else {
        return Err((
            "input.session.not_ready",
            "host session context is not synced".to_string(),
        ));
    };

    if context.local_device_id != context.agent_device_id {
        return Err((
            "input.session.role.invalid",
            "host input is only allowed on the active agent device".to_string(),
        ));
    }
    if context.session_id != session_id {
        return Err((
            "input.session.mismatch",
            format!(
                "input session {} does not match active session {}",
                session_id, context.session_id
            ),
        ));
    }
    if context.controller_device_id != sender_device_id {
        return Err((
            "input.sender_device.mismatch",
            format!(
                "input sender {} does not match active controller {}",
                sender_device_id, context.controller_device_id
            ),
        ));
    }

    Ok(())
}

fn is_supported_input_message_type(message_type: &str) -> bool {
    matches!(
        message_type,
        "input.mouse.move" | "input.mouse.button" | "input.keyboard.key" | "input.wheel.scroll"
    )
}

fn parse_input(message_type: &str, payload: &Value) -> Result<HostInputCommand, String> {
    match message_type {
        "input.mouse.move" => Ok(HostInputCommand::MouseMove {
            x: require_pointer_coordinate(payload, "x")?,
            y: require_pointer_coordinate(payload, "y")?,
        }),
        "input.mouse.button" => Ok(HostInputCommand::MouseButton {
            button: parse_button(&require_text(payload, "button")?)?,
            action: parse_action(&require_text(payload, "action")?)?,
            x: require_pointer_coordinate(payload, "x")?,
            y: require_pointer_coordinate(payload, "y")?,
        }),
        "input.keyboard.key" => Ok(HostInputCommand::KeyboardKey {
            key_code: require_text(payload, "key_code")?,
            action: parse_action(&require_text(payload, "action")?)?,
            modifiers: parse_modifiers(payload)?,
        }),
        "input.wheel.scroll" => Ok(HostInputCommand::WheelScroll {
            delta_x: require_wheel_delta(payload, "delta_x")?,
            delta_y: require_wheel_delta(payload, "delta_y")?,
        }),
        _ => Err(format!("unsupported input message type: {message_type}")),
    }
}

fn summarize_input(input: &HostInputCommand) -> String {
    match input {
        HostInputCommand::MouseMove { x, y } => {
            format!("鼠标移动 ({}, {})", format_numeric(*x), format_numeric(*y))
        }
        HostInputCommand::MouseButton {
            button,
            action,
            x,
            y,
        } => format!(
            "鼠标 {} {} ({}, {})",
            button.as_str(),
            action.as_str(),
            format_numeric(*x),
            format_numeric(*y)
        ),
        HostInputCommand::KeyboardKey {
            key_code,
            action,
            modifiers,
        } => {
            if modifiers.is_empty() {
                format!("键盘 {} {}", key_code, action.as_str())
            } else {
                format!(
                    "键盘 {} {} modifiers={}",
                    key_code,
                    action.as_str(),
                    modifiers.join("+")
                )
            }
        }
        HostInputCommand::WheelScroll { delta_x, delta_y } => format!(
            "滚轮 dx={} dy={}",
            format_numeric(*delta_x),
            format_numeric(*delta_y)
        ),
    }
}

fn require_text(payload: &Value, key: &str) -> Result<String, String> {
    let value = payload
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("payload.{key} is required"))?;

    Ok(value.to_string())
}

fn require_numeric(payload: &Value, key: &str) -> Result<f64, String> {
    payload
        .get(key)
        .and_then(Value::as_f64)
        .ok_or_else(|| format!("payload.{key} must be a number"))
}

fn require_pointer_coordinate(payload: &Value, key: &str) -> Result<f64, String> {
    let value = require_numeric(payload, key)?;
    if !(MIN_POINTER_COORDINATE..=MAX_POINTER_COORDINATE).contains(&value) {
        return Err(format!(
            "payload.{key} must be between {MIN_POINTER_COORDINATE:.2} and {MAX_POINTER_COORDINATE:.2}"
        ));
    }

    Ok(value)
}

fn require_wheel_delta(payload: &Value, key: &str) -> Result<f64, String> {
    let value = require_numeric(payload, key)?;
    if value.abs() > MAX_WHEEL_DELTA_ABS {
        return Err(format!(
            "payload.{key} exceeds maximum absolute delta {MAX_WHEEL_DELTA_ABS:.0}"
        ));
    }

    Ok(value)
}

fn parse_modifiers(payload: &Value) -> Result<Vec<String>, String> {
    let Some(value) = payload.get("modifiers") else {
        return Ok(Vec::new());
    };
    if value.is_null() {
        return Ok(Vec::new());
    }
    let Some(values) = value.as_array() else {
        return Err("payload.modifiers must be an array".to_string());
    };

    let mut modifiers = Vec::new();
    for value in values {
        let modifier = value
            .as_str()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .ok_or_else(|| "payload.modifiers entries must be non-empty strings".to_string())?;
        if !matches!(
            modifier,
            "ShiftLeft"
                | "ShiftRight"
                | "ControlLeft"
                | "ControlRight"
                | "AltLeft"
                | "AltRight"
                | "MetaLeft"
        ) {
            return Err(format!("unsupported keyboard modifier: {modifier}"));
        }
        if !modifiers.iter().any(|existing| existing == modifier) {
            modifiers.push(modifier.to_string());
        }
    }

    Ok(modifiers)
}

fn parse_button(value: &str) -> Result<PointerButton, String> {
    match value {
        "left" => Ok(PointerButton::Left),
        "right" => Ok(PointerButton::Right),
        "middle" => Ok(PointerButton::Middle),
        _ => Err(format!("unsupported pointer button: {value}")),
    }
}

fn parse_action(value: &str) -> Result<InputAction, String> {
    match value {
        "down" => Ok(InputAction::Down),
        "up" => Ok(InputAction::Up),
        _ => Err(format!("unsupported input action: {value}")),
    }
}

fn format_numeric(value: f64) -> String {
    format!("{value:.2}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::{Mutex, OnceLock};

    static HOST_TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

    fn host_test_lock() -> std::sync::MutexGuard<'static, ()> {
        HOST_TEST_LOCK
            .get_or_init(|| Mutex::new(()))
            .lock()
            .expect("host test lock poisoned")
    }

    fn sample_session_context() -> HostSessionContext {
        HostSessionContext {
            session_id: "session-123".to_string(),
            controller_device_id: "controller-456".to_string(),
            agent_device_id: "agent-789".to_string(),
            local_device_id: "agent-789".to_string(),
        }
    }

    fn sample_envelope() -> HostInputEnvelope {
        HostInputEnvelope {
            message_type: "input.mouse.move".to_string(),
            session_id: "session-123".to_string(),
            trace_id: "trace-123".to_string(),
            sender_device_id: "controller-456".to_string(),
            sender_role: "controller".to_string(),
            payload: json!({ "x": 0.5, "y": 0.25 }),
        }
    }

    #[test]
    fn summarize_input_formats_mouse_move() {
        let input = parse_input("input.mouse.move", &json!({ "x": 0.5, "y": 0.25 }))
            .expect("mouse move should parse");

        assert_eq!(summarize_input(&input), "鼠标移动 (0.50, 0.25)");
    }

    #[test]
    fn parse_input_normalizes_mouse_button() {
        let input = parse_input(
            "input.mouse.button",
            &json!({ "button": "left", "action": "up", "x": 0.5, "y": 0.25 }),
        )
        .expect("mouse button should parse");

        assert_eq!(
            input,
            HostInputCommand::MouseButton {
                button: PointerButton::Left,
                action: InputAction::Up,
                x: 0.5,
                y: 0.25,
            }
        );
    }

    #[test]
    fn parse_input_preserves_keyboard_modifiers() {
        let input = parse_input(
            "input.keyboard.key",
            &json!({
                "key_code": "KeyA",
                "action": "down",
                "modifiers": ["ShiftLeft", "ControlLeft", "ShiftLeft"],
            }),
        )
        .expect("keyboard input should parse");

        assert_eq!(
            input,
            HostInputCommand::KeyboardKey {
                key_code: "KeyA".to_string(),
                action: InputAction::Down,
                modifiers: vec!["ShiftLeft".to_string(), "ControlLeft".to_string()],
            }
        );
        assert_eq!(
            summarize_input(&input),
            "键盘 KeyA down modifiers=ShiftLeft+ControlLeft"
        );
    }

    #[test]
    fn parse_input_rejects_unknown_type() {
        let error = parse_input("input.unknown", &json!({})).expect_err("unknown type should fail");

        assert_eq!(error, "unsupported input message type: input.unknown");
    }

    #[test]
    fn parse_input_requires_numeric_coordinates() {
        let error = parse_input("input.mouse.move", &json!({ "x": "bad", "y": 0.25 }))
            .expect_err("mouse move should validate coordinates");

        assert_eq!(error, "payload.x must be a number");
    }

    #[test]
    fn parse_input_rejects_out_of_range_pointer_coordinate() {
        let error = parse_input("input.mouse.move", &json!({ "x": 1.25, "y": 0.25 }))
            .expect_err("mouse move should reject out of range coordinates");

        assert_eq!(error, "payload.x must be between 0.00 and 1.00");
    }

    #[test]
    fn parse_input_rejects_excessive_wheel_delta() {
        let error = parse_input(
            "input.wheel.scroll",
            &json!({ "delta_x": 0, "delta_y": 5000 }),
        )
        .expect_err("wheel delta should be bounded");

        assert_eq!(error, "payload.delta_y exceeds maximum absolute delta 4096");
    }

    #[test]
    fn sync_session_resets_host_bridge_status() {
        let _lock = host_test_lock();
        {
            let mut state = host_bridge_state()
                .lock()
                .expect("host bridge state poisoned");
            state.input_count = 7;
            state.last_input_type = "input.mouse.move".to_string();
            state.last_error_code = "input.session.mismatch".to_string();
        }

        let status =
            sync_session(Some(sample_session_context())).expect("session sync should succeed");

        assert_eq!(status.input_count, 0);
        assert!(status.last_input_type.is_empty());
        assert!(status.last_error_code.is_empty());
    }

    #[test]
    fn apply_input_requires_synced_session_context() {
        let _lock = host_test_lock();
        sync_session(None).expect("session reset should succeed");

        let result = apply_input(sample_envelope());

        assert!(!result.applied);
        assert_eq!(result.error_code, "input.session.not_ready");
        assert_eq!(result.status_code, "input.session.not_ready");
    }

    #[test]
    fn apply_input_rejects_missing_sender_device() {
        let _lock = host_test_lock();
        sync_session(None).expect("session reset should succeed");
        let mut envelope = sample_envelope();
        envelope.sender_device_id = "  ".to_string();

        let result = apply_input(envelope);

        assert!(!result.applied);
        assert_eq!(result.error_code, "input.sender_device.required");
        assert_eq!(result.status_code, "input.sender_device.required");
    }

    #[test]
    fn apply_input_allows_session_controller_with_agent_sender_role() {
        let _lock = host_test_lock();
        sync_session(Some(sample_session_context())).expect("session sync should succeed");
        let mut envelope = sample_envelope();
        envelope.sender_role = "agent".to_string();

        let result = apply_input(envelope);

        assert_ne!(result.error_code, "input.sender_role.invalid");
        assert_ne!(result.status_code, "input.sender_role.invalid");
        match platform::current_platform() {
            platform::DesktopPlatform::Unsupported => {
                assert_eq!(result.error_code, "platform.unsupported");
            }
            _ => {
                assert!(result.error_code.is_empty());
            }
        }
    }

    #[test]
    fn apply_input_rejects_session_mismatch() {
        let _lock = host_test_lock();
        sync_session(Some(sample_session_context())).expect("session sync should succeed");
        let mut envelope = sample_envelope();
        envelope.session_id = "session-other".to_string();

        let result = apply_input(envelope);

        assert!(!result.applied);
        assert_eq!(result.error_code, "input.session.mismatch");
        assert_eq!(result.status_code, "input.session.mismatch");
    }

    #[test]
    fn apply_input_rejects_controller_device_mismatch() {
        let _lock = host_test_lock();
        sync_session(Some(sample_session_context())).expect("session sync should succeed");
        let mut envelope = sample_envelope();
        envelope.sender_device_id = "controller-other".to_string();

        let result = apply_input(envelope);

        assert!(!result.applied);
        assert_eq!(result.error_code, "input.sender_device.mismatch");
        assert_eq!(result.status_code, "input.sender_device.mismatch");
    }

    #[test]
    fn apply_input_requires_local_agent_device() {
        let _lock = host_test_lock();
        sync_session(Some(HostSessionContext {
            session_id: "session-123".to_string(),
            controller_device_id: "controller-456".to_string(),
            agent_device_id: "agent-789".to_string(),
            local_device_id: "controller-456".to_string(),
        }))
        .expect("session sync should succeed");

        let result = apply_input(sample_envelope());

        assert!(!result.applied);
        assert_eq!(result.error_code, "input.session.role.invalid");
        assert_eq!(result.status_code, "input.session.role.invalid");
    }
}
