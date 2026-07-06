#[cfg(all(target_os = "macos", not(test)))]
use crate::capture as runtime_capture;
use crate::{capture::CaptureSourceKind, codec};
use serde::Serialize;

#[cfg(all(target_os = "macos", not(test)))]
use objc2_core_foundation::{CFRetained, CGPoint};
#[cfg(all(target_os = "macos", not(test)))]
use objc2_core_graphics::{
    CGDirectDisplayID, CGDisplayBounds, CGEvent, CGEventSource, CGEventSourceStateID,
    CGEventTapLocation, CGEventType, CGKeyCode, CGMouseButton, CGRectGetMaxX, CGRectGetMaxY,
    CGRectGetMinX, CGRectGetMinY, CGScrollEventUnit,
};
#[cfg(all(target_os = "windows", not(test)))]
use std::mem::size_of;
#[cfg(all(target_os = "macos", not(test)))]
use std::sync::{Mutex, OnceLock};
#[cfg(all(target_os = "windows", not(test)))]
use windows::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYBD_EVENT_FLAGS,
    KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_RIGHTDOWN,
    MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_WHEEL, MOUSEINPUT, MOUSE_EVENT_FLAGS, VIRTUAL_KEY, VK_0, VK_1,
    VK_2, VK_3, VK_4, VK_5, VK_6, VK_7, VK_8, VK_9, VK_A, VK_ADD, VK_B, VK_BACK, VK_C, VK_CAPITAL,
    VK_D, VK_DECIMAL, VK_DELETE, VK_DIVIDE, VK_DOWN, VK_E, VK_END, VK_ESCAPE, VK_F, VK_F1, VK_F10,
    VK_F11, VK_F12, VK_F2, VK_F3, VK_F4, VK_F5, VK_F6, VK_F7, VK_F8, VK_F9, VK_G, VK_H, VK_HOME,
    VK_I, VK_INSERT, VK_J, VK_K, VK_L, VK_LCONTROL, VK_LEFT, VK_LMENU, VK_LSHIFT, VK_LWIN, VK_M,
    VK_MULTIPLY, VK_N, VK_NEXT, VK_NUMLOCK, VK_NUMPAD0, VK_NUMPAD1, VK_NUMPAD2, VK_NUMPAD3,
    VK_NUMPAD4, VK_NUMPAD5, VK_NUMPAD6, VK_NUMPAD7, VK_NUMPAD8, VK_NUMPAD9, VK_O, VK_OEM_1,
    VK_OEM_2, VK_OEM_3, VK_OEM_4, VK_OEM_5, VK_OEM_6, VK_OEM_7, VK_OEM_COMMA, VK_OEM_MINUS,
    VK_OEM_PERIOD, VK_OEM_PLUS, VK_P, VK_PAUSE, VK_PRIOR, VK_Q, VK_R, VK_RCONTROL, VK_RETURN,
    VK_RIGHT, VK_RMENU, VK_RSHIFT, VK_RWIN, VK_S, VK_SCROLL, VK_SNAPSHOT, VK_SPACE, VK_SUBTRACT,
    VK_T, VK_TAB, VK_U, VK_UP, VK_V, VK_W, VK_X, VK_Y, VK_Z,
};
#[cfg(all(target_os = "windows", not(test)))]
use windows::Win32::UI::WindowsAndMessaging::{
    GetSystemMetrics, SetCursorPos, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN,
    SM_YVIRTUALSCREEN,
};

pub mod capture;
pub use capture::{
    capture_backend_name, capture_get_permission_state, capture_list_sources,
    capture_request_permission, capture_supported_source_kinds, capture_supports_frame_streaming,
    capture_supports_pause_resume, capture_supports_permission_check,
    capture_supports_permission_request, capture_supports_source_listing, capture_take_frame,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopPlatform {
    Macos,
    Windows,
    Unsupported,
}

impl DesktopPlatform {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Macos => "macos",
            Self::Windows => "windows",
            Self::Unsupported => "unsupported",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PointerButton {
    Left,
    Right,
    Middle,
}

impl PointerButton {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Left => "left",
            Self::Right => "right",
            Self::Middle => "middle",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputAction {
    Down,
    Up,
}

impl InputAction {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Down => "down",
            Self::Up => "up",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum HostInputCommand {
    MouseMove {
        x: f64,
        y: f64,
    },
    MouseButton {
        button: PointerButton,
        action: InputAction,
        x: f64,
        y: f64,
    },
    KeyboardKey {
        key_code: String,
        action: InputAction,
        modifiers: Vec<String>,
    },
    WheelScroll {
        delta_x: f64,
        delta_y: f64,
    },
}

pub fn current_platform() -> DesktopPlatform {
    if cfg!(target_os = "macos") {
        DesktopPlatform::Macos
    } else if cfg!(target_os = "windows") {
        DesktopPlatform::Windows
    } else {
        DesktopPlatform::Unsupported
    }
}

pub fn current_platform_stub() -> &'static str {
    current_platform().as_str()
}

const SCREEN_RECORDING_CAPABILITY: &str = "screen_recording";
const ACCESSIBILITY_CAPABILITY: &str = "accessibility";

#[cfg(all(target_os = "macos", not(test)))]
static MACOS_ACTIVE_MOUSE_BUTTON: OnceLock<Mutex<Option<PointerButton>>> = OnceLock::new();

#[derive(Debug, Clone, Serialize)]
pub struct DesktopPlatformCapabilities {
    pub platform: &'static str,
    pub capture: DesktopCaptureCapabilities,
    pub host_input: DesktopHostInputCapabilities,
}

#[derive(Debug, Clone, Serialize)]
pub struct DesktopCaptureCapabilities {
    pub backend: &'static str,
    pub default_codec: &'static str,
    pub support_level: &'static str,
    pub support_detail: String,
    pub requires_permission: bool,
    pub permission_capability: &'static str,
    pub supports_permission_check: bool,
    pub supports_permission_request: bool,
    pub supports_source_listing: bool,
    pub supports_frame_streaming: bool,
    pub supports_pause_resume: bool,
    pub supported_source_kinds: Vec<CaptureSourceKind>,
}

#[derive(Debug, Clone, Serialize)]
pub struct DesktopHostInputCapabilities {
    pub backend: &'static str,
    pub support_level: &'static str,
    pub support_detail: String,
    pub requires_permission: bool,
    pub permission_capability: &'static str,
    pub permission: DesktopHostInputPermissionState,
    pub supports_pointer_input: bool,
    pub supports_pointer_move: bool,
    pub supported_pointer_buttons: Vec<&'static str>,
    pub supports_keyboard_input: bool,
    pub supports_wheel_input: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct DesktopHostInputPermissionState {
    pub capability: &'static str,
    pub status: &'static str,
    pub can_request: bool,
    pub detail: String,
}

pub fn platform_get_capabilities() -> DesktopPlatformCapabilities {
    DesktopPlatformCapabilities {
        platform: current_platform_stub(),
        capture: capture_capabilities(),
        host_input: host_input_capabilities(),
    }
}

pub fn capture_capabilities() -> DesktopCaptureCapabilities {
    DesktopCaptureCapabilities {
        backend: capture_backend_name(),
        default_codec: codec::codec_name(),
        support_level: capture_support_level(),
        support_detail: capture_support_detail(),
        requires_permission: matches!(current_platform(), DesktopPlatform::Macos),
        permission_capability: SCREEN_RECORDING_CAPABILITY,
        supports_permission_check: capture_supports_permission_check(),
        supports_permission_request: capture_supports_permission_request(),
        supports_source_listing: capture_supports_source_listing(),
        supports_frame_streaming: capture_supports_frame_streaming(),
        supports_pause_resume: capture_supports_pause_resume(),
        supported_source_kinds: capture_supported_source_kinds(),
    }
}

pub fn host_input_capabilities() -> DesktopHostInputCapabilities {
    DesktopHostInputCapabilities {
        backend: host_input_backend_name(),
        support_level: host_input_support_level(),
        support_detail: host_input_support_detail(),
        requires_permission: host_input_requires_permission(),
        permission_capability: ACCESSIBILITY_CAPABILITY,
        permission: host_input_permission_state(),
        supports_pointer_input: host_input_supports_pointer_input(),
        supports_pointer_move: host_input_supports_pointer_input(),
        supported_pointer_buttons: host_input_supported_pointer_buttons(),
        supports_keyboard_input: host_input_supports_keyboard_input(),
        supports_wheel_input: host_input_supports_wheel_input(),
    }
}

fn capture_support_level() -> &'static str {
    match current_platform() {
        DesktopPlatform::Macos => {
            if cfg!(test) {
                "stub"
            } else {
                "implemented"
            }
        }
        DesktopPlatform::Windows => {
            if cfg!(test) {
                "stub"
            } else {
                "implemented"
            }
        }
        DesktopPlatform::Unsupported => "unsupported",
    }
}

fn capture_support_detail() -> String {
    match current_platform() {
        DesktopPlatform::Macos => {
            if cfg!(test) {
                "macOS test capture backend returns synthetic frame data".to_string()
            } else {
                "native macOS screen capture backend is available".to_string()
            }
        }
        DesktopPlatform::Windows => {
            if cfg!(test) {
                "Windows test capture backend returns synthetic frame data".to_string()
            } else {
                "Windows GDI screen capture backend is available".to_string()
            }
        }
        DesktopPlatform::Unsupported => {
            "screen capture is not supported on this platform".to_string()
        }
    }
}

fn host_input_backend_name() -> &'static str {
    match current_platform() {
        DesktopPlatform::Macos => {
            if cfg!(test) {
                "macos.test_stub"
            } else {
                "macos.cg_event"
            }
        }
        DesktopPlatform::Windows => {
            if cfg!(test) {
                "windows.test_stub"
            } else {
                "windows.send_input"
            }
        }
        DesktopPlatform::Unsupported => "unsupported",
    }
}

fn host_input_support_level() -> &'static str {
    match current_platform() {
        DesktopPlatform::Macos => {
            if cfg!(test) {
                "stub"
            } else {
                "implemented"
            }
        }
        DesktopPlatform::Windows => {
            if cfg!(test) {
                "stub"
            } else {
                "implemented"
            }
        }
        DesktopPlatform::Unsupported => "unsupported",
    }
}

fn host_input_support_detail() -> String {
    match current_platform() {
        DesktopPlatform::Macos => {
            if cfg!(test) {
                "macOS test executor skips real host input execution".to_string()
            } else {
                "host input is dispatched via Core Graphics events".to_string()
            }
        }
        DesktopPlatform::Windows => {
            if cfg!(test) {
                "Windows test executor skips real host input execution".to_string()
            } else {
                "host input is dispatched via SendInput and SetCursorPos".to_string()
            }
        }
        DesktopPlatform::Unsupported => "host input is not supported on this platform".to_string(),
    }
}

fn host_input_requires_permission() -> bool {
    matches!(current_platform(), DesktopPlatform::Macos) && !cfg!(test)
}

pub fn host_input_permission_state() -> DesktopHostInputPermissionState {
    match current_platform() {
        DesktopPlatform::Macos => macos_host_input_permission_state(),
        DesktopPlatform::Windows => DesktopHostInputPermissionState {
            capability: ACCESSIBILITY_CAPABILITY,
            status: "granted",
            can_request: false,
            detail: "Windows host input is available without an extra permission prompt"
                .to_string(),
        },
        DesktopPlatform::Unsupported => DesktopHostInputPermissionState {
            capability: ACCESSIBILITY_CAPABILITY,
            status: "unsupported",
            can_request: false,
            detail: "host input is not supported on this platform".to_string(),
        },
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_host_input_permission_state() -> DesktopHostInputPermissionState {
    let granted = macos_input_permission_granted();
    DesktopHostInputPermissionState {
        capability: ACCESSIBILITY_CAPABILITY,
        status: if granted { "granted" } else { "denied" },
        can_request: false,
        detail: if granted {
            "macOS Accessibility permission is granted".to_string()
        } else {
            "macOS input injection requires Accessibility permission for this app".to_string()
        },
    }
}

#[cfg(all(target_os = "macos", test))]
fn macos_host_input_permission_state() -> DesktopHostInputPermissionState {
    DesktopHostInputPermissionState {
        capability: ACCESSIBILITY_CAPABILITY,
        status: "denied",
        can_request: false,
        detail: "macOS test build does not query Accessibility permission".to_string(),
    }
}

#[cfg(not(target_os = "macos"))]
fn macos_host_input_permission_state() -> DesktopHostInputPermissionState {
    DesktopHostInputPermissionState {
        capability: ACCESSIBILITY_CAPABILITY,
        status: "unsupported",
        can_request: false,
        detail: "macOS host input backend is unavailable on this build target".to_string(),
    }
}

fn host_input_supports_pointer_input() -> bool {
    matches!(
        current_platform(),
        DesktopPlatform::Macos | DesktopPlatform::Windows
    ) && !cfg!(test)
}

fn host_input_supported_pointer_buttons() -> Vec<&'static str> {
    if !host_input_supports_pointer_input() {
        return Vec::new();
    }

    match current_platform() {
        DesktopPlatform::Macos => vec!["left", "right"],
        DesktopPlatform::Windows => vec!["left", "right", "middle"],
        DesktopPlatform::Unsupported => Vec::new(),
    }
}

fn host_input_supports_keyboard_input() -> bool {
    matches!(
        current_platform(),
        DesktopPlatform::Macos | DesktopPlatform::Windows
    ) && !cfg!(test)
}

fn host_input_supports_wheel_input() -> bool {
    matches!(
        current_platform(),
        DesktopPlatform::Macos | DesktopPlatform::Windows
    ) && !cfg!(test)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostDispatchResult {
    pub executor: &'static str,
    pub applied: bool,
    pub status_code: &'static str,
    pub status_detail: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostDispatchError {
    pub executor: &'static str,
    pub code: &'static str,
    pub detail: String,
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_dispatch_error(code: &'static str, detail: impl Into<String>) -> HostDispatchError {
    HostDispatchError {
        executor: "macos.cg_event",
        code,
        detail: detail.into(),
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_dispatch_error(code: &'static str, detail: impl Into<String>) -> HostDispatchError {
    HostDispatchError {
        executor: "windows.send_input",
        code,
        detail: detail.into(),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
#[derive(Debug, Clone, PartialEq)]
struct PointerLocationResult {
    point: CGPoint,
}

#[cfg(all(target_os = "windows", not(test)))]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct WindowsPointerLocation {
    x: i32,
    y: i32,
}

pub fn dispatch_host_input(
    input: &HostInputCommand,
) -> Result<HostDispatchResult, HostDispatchError> {
    match current_platform() {
        DesktopPlatform::Macos => dispatch_macos_input(input),
        DesktopPlatform::Windows => dispatch_windows_input(input),
        DesktopPlatform::Unsupported => Err(HostDispatchError {
            executor: "unsupported",
            code: "platform.unsupported",
            detail: "host input is not supported on this platform".to_string(),
        }),
    }
}

#[cfg(all(target_os = "macos", test))]
fn dispatch_macos_input(
    _input: &HostInputCommand,
) -> Result<HostDispatchResult, HostDispatchError> {
    Ok(HostDispatchResult {
        executor: "macos.test_stub",
        applied: false,
        status_code: "stub.macos_test",
        status_detail: "macOS test executor skips real event posting".to_string(),
    })
}

#[cfg(all(target_os = "macos", not(test)))]
fn dispatch_macos_input(input: &HostInputCommand) -> Result<HostDispatchResult, HostDispatchError> {
    ensure_macos_input_permission()?;

    match input {
        HostInputCommand::MouseMove { x, y } => {
            let location = pointer_location(*x, *y)?;
            let (event_type, mouse_button) = mouse_move_event();
            post_mouse_event(event_type, location.point, mouse_button)?;
        }
        HostInputCommand::MouseButton {
            button,
            action,
            x,
            y,
        } => {
            let location = pointer_location(*x, *y)?;
            let (event_type, mouse_button) = mouse_button_event(*button, *action)?;
            post_mouse_event(event_type, location.point, mouse_button)?;
            update_active_mouse_button(*button, *action);
        }
        HostInputCommand::KeyboardKey {
            key_code,
            action,
            modifiers,
        } => {
            dispatch_macos_keyboard_input(key_code, *action, modifiers)?;
        }
        HostInputCommand::WheelScroll { delta_x, delta_y } => {
            post_scroll_event(*delta_x, *delta_y)?;
        }
    };

    Ok(HostDispatchResult {
        executor: "macos.cg_event",
        applied: true,
        status_code: "applied",
        status_detail: "input dispatched via Core Graphics".to_string(),
    })
}

#[cfg(not(target_os = "macos"))]
fn dispatch_macos_input(
    _input: &HostInputCommand,
) -> Result<HostDispatchResult, HostDispatchError> {
    Ok(HostDispatchResult {
        executor: "macos.stub",
        applied: false,
        status_code: "stub.macos",
        status_detail: "macOS executor is unavailable on this build target".to_string(),
    })
}

#[cfg(all(target_os = "windows", test))]
fn dispatch_windows_input(
    _input: &HostInputCommand,
) -> Result<HostDispatchResult, HostDispatchError> {
    Ok(HostDispatchResult {
        executor: "windows.test_stub",
        applied: false,
        status_code: "stub.windows_test",
        status_detail: "Windows test executor skips real host input execution".to_string(),
    })
}

#[cfg(all(target_os = "windows", not(test)))]
fn dispatch_windows_input(
    input: &HostInputCommand,
) -> Result<HostDispatchResult, HostDispatchError> {
    match input {
        HostInputCommand::MouseMove { x, y } => {
            let location = windows_pointer_location(*x, *y)?;
            set_windows_cursor_position(location)?;
        }
        HostInputCommand::MouseButton {
            button,
            action,
            x,
            y,
        } => {
            let location = windows_pointer_location(*x, *y)?;
            set_windows_cursor_position(location)?;
            send_windows_inputs(&[mouse_input(windows_mouse_button_flag(*button, *action)?, 0)])?;
        }
        HostInputCommand::KeyboardKey {
            key_code,
            action,
            modifiers,
        } => {
            let inputs = windows_keyboard_inputs(key_code, *action, modifiers)?;
            send_windows_inputs(&inputs)?;
        }
        HostInputCommand::WheelScroll { delta_x, delta_y } => {
            let mut inputs = Vec::new();
            let vertical = wheel_delta_data(*delta_y);
            if vertical != 0 {
                inputs.push(mouse_input(MOUSEEVENTF_WHEEL, vertical as u32));
            }
            let horizontal = wheel_delta_data(*delta_x);
            if horizontal != 0 {
                inputs.push(mouse_input(MOUSEEVENTF_HWHEEL, horizontal as u32));
            }
            send_windows_inputs(&inputs)?;
        }
    }

    Ok(HostDispatchResult {
        executor: "windows.send_input",
        applied: true,
        status_code: "applied",
        status_detail: "input dispatched via SendInput and SetCursorPos".to_string(),
    })
}

#[cfg(not(target_os = "windows"))]
fn dispatch_windows_input(
    _input: &HostInputCommand,
) -> Result<HostDispatchResult, HostDispatchError> {
    Ok(HostDispatchResult {
        executor: "windows.stub",
        applied: false,
        status_code: "stub.windows",
        status_detail: "Windows executor is unavailable on this build target".to_string(),
    })
}

#[cfg(all(target_os = "windows", not(test)))]
fn send_windows_inputs(inputs: &[INPUT]) -> Result<(), HostDispatchError> {
    if inputs.is_empty() {
        return Ok(());
    }

    let sent = unsafe { SendInput(inputs, size_of::<INPUT>() as i32) };
    if sent as usize == inputs.len() {
        Ok(())
    } else {
        Err(windows_dispatch_error(
            "event.send_input.failed",
            format!("SendInput sent {sent} of {} events", inputs.len()),
        ))
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn set_windows_cursor_position(location: WindowsPointerLocation) -> Result<(), HostDispatchError> {
    unsafe { SetCursorPos(location.x, location.y) }.map_err(|error| {
        windows_dispatch_error(
            "event.cursor.set_failed",
            format!(
                "SetCursorPos({}, {}) failed: {error}",
                location.x, location.y
            ),
        )
    })
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_pointer_location(x: f64, y: f64) -> Result<WindowsPointerLocation, HostDispatchError> {
    if !x.is_finite() || !y.is_finite() || !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
        return Err(windows_dispatch_error(
            "input.mouse.coordinate.invalid",
            format!(
                "pointer coordinates must be finite normalized values between 0.0 and 1.0, got x={x:.4}, y={y:.4}"
            ),
        ));
    }

    let left = unsafe { GetSystemMetrics(SM_XVIRTUALSCREEN) };
    let top = unsafe { GetSystemMetrics(SM_YVIRTUALSCREEN) };
    let width = unsafe { GetSystemMetrics(SM_CXVIRTUALSCREEN) };
    let height = unsafe { GetSystemMetrics(SM_CYVIRTUALSCREEN) };

    if width <= 0 || height <= 0 {
        return Err(windows_dispatch_error(
            "input.mouse.mapping_unavailable",
            format!("Windows virtual screen metrics are invalid: {width}x{height}"),
        ));
    }

    let normalized_x = x.clamp(0.0, 1.0 - f64::EPSILON);
    let normalized_y = y.clamp(0.0, 1.0 - f64::EPSILON);
    let x = left + (((width - 1).max(0) as f64) * normalized_x).round() as i32;
    let y = top + (((height - 1).max(0) as f64) * normalized_y).round() as i32;

    Ok(WindowsPointerLocation { x, y })
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_mouse_button_flag(
    button: PointerButton,
    action: InputAction,
) -> Result<MOUSE_EVENT_FLAGS, HostDispatchError> {
    match (button, action) {
        (PointerButton::Left, InputAction::Down) => Ok(MOUSEEVENTF_LEFTDOWN),
        (PointerButton::Left, InputAction::Up) => Ok(MOUSEEVENTF_LEFTUP),
        (PointerButton::Right, InputAction::Down) => Ok(MOUSEEVENTF_RIGHTDOWN),
        (PointerButton::Right, InputAction::Up) => Ok(MOUSEEVENTF_RIGHTUP),
        (PointerButton::Middle, InputAction::Down) => Ok(MOUSEEVENTF_MIDDLEDOWN),
        (PointerButton::Middle, InputAction::Up) => Ok(MOUSEEVENTF_MIDDLEUP),
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_key_code(key_code: &str) -> Result<(VIRTUAL_KEY, KEYBD_EVENT_FLAGS), HostDispatchError> {
    match key_code {
        "KeyA" => Ok((VK_A, KEYBD_EVENT_FLAGS(0))),
        "KeyS" => Ok((VK_S, KEYBD_EVENT_FLAGS(0))),
        "KeyD" => Ok((VK_D, KEYBD_EVENT_FLAGS(0))),
        "KeyF" => Ok((VK_F, KEYBD_EVENT_FLAGS(0))),
        "KeyH" => Ok((VK_H, KEYBD_EVENT_FLAGS(0))),
        "KeyG" => Ok((VK_G, KEYBD_EVENT_FLAGS(0))),
        "KeyZ" => Ok((VK_Z, KEYBD_EVENT_FLAGS(0))),
        "KeyX" => Ok((VK_X, KEYBD_EVENT_FLAGS(0))),
        "KeyC" => Ok((VK_C, KEYBD_EVENT_FLAGS(0))),
        "KeyV" => Ok((VK_V, KEYBD_EVENT_FLAGS(0))),
        "KeyB" => Ok((VK_B, KEYBD_EVENT_FLAGS(0))),
        "KeyQ" => Ok((VK_Q, KEYBD_EVENT_FLAGS(0))),
        "KeyW" => Ok((VK_W, KEYBD_EVENT_FLAGS(0))),
        "KeyE" => Ok((VK_E, KEYBD_EVENT_FLAGS(0))),
        "KeyR" => Ok((VK_R, KEYBD_EVENT_FLAGS(0))),
        "KeyY" => Ok((VK_Y, KEYBD_EVENT_FLAGS(0))),
        "KeyT" => Ok((VK_T, KEYBD_EVENT_FLAGS(0))),
        "Digit1" => Ok((VK_1, KEYBD_EVENT_FLAGS(0))),
        "Digit2" => Ok((VK_2, KEYBD_EVENT_FLAGS(0))),
        "Digit3" => Ok((VK_3, KEYBD_EVENT_FLAGS(0))),
        "Digit4" => Ok((VK_4, KEYBD_EVENT_FLAGS(0))),
        "Digit5" => Ok((VK_5, KEYBD_EVENT_FLAGS(0))),
        "Digit6" => Ok((VK_6, KEYBD_EVENT_FLAGS(0))),
        "Digit7" => Ok((VK_7, KEYBD_EVENT_FLAGS(0))),
        "Digit8" => Ok((VK_8, KEYBD_EVENT_FLAGS(0))),
        "Digit9" => Ok((VK_9, KEYBD_EVENT_FLAGS(0))),
        "Digit0" => Ok((VK_0, KEYBD_EVENT_FLAGS(0))),
        "Equal" => Ok((VK_OEM_PLUS, KEYBD_EVENT_FLAGS(0))),
        "Minus" => Ok((VK_OEM_MINUS, KEYBD_EVENT_FLAGS(0))),
        "BracketRight" => Ok((VK_OEM_6, KEYBD_EVENT_FLAGS(0))),
        "KeyO" => Ok((VK_O, KEYBD_EVENT_FLAGS(0))),
        "KeyU" => Ok((VK_U, KEYBD_EVENT_FLAGS(0))),
        "BracketLeft" => Ok((VK_OEM_4, KEYBD_EVENT_FLAGS(0))),
        "KeyI" => Ok((VK_I, KEYBD_EVENT_FLAGS(0))),
        "KeyP" => Ok((VK_P, KEYBD_EVENT_FLAGS(0))),
        "Enter" => Ok((VK_RETURN, KEYBD_EVENT_FLAGS(0))),
        "KeyL" => Ok((VK_L, KEYBD_EVENT_FLAGS(0))),
        "KeyJ" => Ok((VK_J, KEYBD_EVENT_FLAGS(0))),
        "Quote" => Ok((VK_OEM_7, KEYBD_EVENT_FLAGS(0))),
        "KeyK" => Ok((VK_K, KEYBD_EVENT_FLAGS(0))),
        "Semicolon" => Ok((VK_OEM_1, KEYBD_EVENT_FLAGS(0))),
        "Backslash" => Ok((VK_OEM_5, KEYBD_EVENT_FLAGS(0))),
        "Comma" => Ok((VK_OEM_COMMA, KEYBD_EVENT_FLAGS(0))),
        "Slash" => Ok((VK_OEM_2, KEYBD_EVENT_FLAGS(0))),
        "KeyN" => Ok((VK_N, KEYBD_EVENT_FLAGS(0))),
        "KeyM" => Ok((VK_M, KEYBD_EVENT_FLAGS(0))),
        "Period" => Ok((VK_OEM_PERIOD, KEYBD_EVENT_FLAGS(0))),
        "Tab" => Ok((VK_TAB, KEYBD_EVENT_FLAGS(0))),
        "Space" => Ok((VK_SPACE, KEYBD_EVENT_FLAGS(0))),
        "Backquote" => Ok((VK_OEM_3, KEYBD_EVENT_FLAGS(0))),
        "Backspace" => Ok((VK_BACK, KEYBD_EVENT_FLAGS(0))),
        "Escape" => Ok((VK_ESCAPE, KEYBD_EVENT_FLAGS(0))),
        "MetaLeft" => Ok((VK_LWIN, KEYBD_EVENT_FLAGS(0))),
        "MetaRight" => Ok((VK_RWIN, KEYBD_EVENT_FLAGS(0))),
        "ShiftLeft" => Ok((VK_LSHIFT, KEYBD_EVENT_FLAGS(0))),
        "CapsLock" => Ok((VK_CAPITAL, KEYBD_EVENT_FLAGS(0))),
        "AltLeft" => Ok((VK_LMENU, KEYBD_EVENT_FLAGS(0))),
        "ControlLeft" => Ok((VK_LCONTROL, KEYBD_EVENT_FLAGS(0))),
        "ShiftRight" => Ok((VK_RSHIFT, KEYBD_EVENT_FLAGS(0))),
        "AltRight" => Ok((VK_RMENU, KEYEVENTF_EXTENDEDKEY)),
        "ControlRight" => Ok((VK_RCONTROL, KEYEVENTF_EXTENDEDKEY)),
        // 作者: long；Android 电脑键盘面板发送标准键位码，目标端必须把功能键、编辑导航键和数字小键盘落到原生输入事件。
        "F1" => Ok((VK_F1, KEYBD_EVENT_FLAGS(0))),
        "F2" => Ok((VK_F2, KEYBD_EVENT_FLAGS(0))),
        "F3" => Ok((VK_F3, KEYBD_EVENT_FLAGS(0))),
        "F4" => Ok((VK_F4, KEYBD_EVENT_FLAGS(0))),
        "F5" => Ok((VK_F5, KEYBD_EVENT_FLAGS(0))),
        "F6" => Ok((VK_F6, KEYBD_EVENT_FLAGS(0))),
        "F7" => Ok((VK_F7, KEYBD_EVENT_FLAGS(0))),
        "F8" => Ok((VK_F8, KEYBD_EVENT_FLAGS(0))),
        "F9" => Ok((VK_F9, KEYBD_EVENT_FLAGS(0))),
        "F10" => Ok((VK_F10, KEYBD_EVENT_FLAGS(0))),
        "F11" => Ok((VK_F11, KEYBD_EVENT_FLAGS(0))),
        "F12" => Ok((VK_F12, KEYBD_EVENT_FLAGS(0))),
        "Insert" => Ok((VK_INSERT, KEYEVENTF_EXTENDEDKEY)),
        "Delete" => Ok((VK_DELETE, KEYEVENTF_EXTENDEDKEY)),
        "Home" => Ok((VK_HOME, KEYEVENTF_EXTENDEDKEY)),
        "End" => Ok((VK_END, KEYEVENTF_EXTENDEDKEY)),
        "PageUp" => Ok((VK_PRIOR, KEYEVENTF_EXTENDEDKEY)),
        "PageDown" => Ok((VK_NEXT, KEYEVENTF_EXTENDEDKEY)),
        "PrintScreen" => Ok((VK_SNAPSHOT, KEYBD_EVENT_FLAGS(0))),
        "ScrollLock" => Ok((VK_SCROLL, KEYBD_EVENT_FLAGS(0))),
        "Pause" => Ok((VK_PAUSE, KEYBD_EVENT_FLAGS(0))),
        "NumLock" => Ok((VK_NUMLOCK, KEYEVENTF_EXTENDEDKEY)),
        "Numpad0" => Ok((VK_NUMPAD0, KEYBD_EVENT_FLAGS(0))),
        "Numpad1" => Ok((VK_NUMPAD1, KEYBD_EVENT_FLAGS(0))),
        "Numpad2" => Ok((VK_NUMPAD2, KEYBD_EVENT_FLAGS(0))),
        "Numpad3" => Ok((VK_NUMPAD3, KEYBD_EVENT_FLAGS(0))),
        "Numpad4" => Ok((VK_NUMPAD4, KEYBD_EVENT_FLAGS(0))),
        "Numpad5" => Ok((VK_NUMPAD5, KEYBD_EVENT_FLAGS(0))),
        "Numpad6" => Ok((VK_NUMPAD6, KEYBD_EVENT_FLAGS(0))),
        "Numpad7" => Ok((VK_NUMPAD7, KEYBD_EVENT_FLAGS(0))),
        "Numpad8" => Ok((VK_NUMPAD8, KEYBD_EVENT_FLAGS(0))),
        "Numpad9" => Ok((VK_NUMPAD9, KEYBD_EVENT_FLAGS(0))),
        "NumpadAdd" => Ok((VK_ADD, KEYBD_EVENT_FLAGS(0))),
        "NumpadSubtract" => Ok((VK_SUBTRACT, KEYBD_EVENT_FLAGS(0))),
        "NumpadMultiply" => Ok((VK_MULTIPLY, KEYBD_EVENT_FLAGS(0))),
        "NumpadDivide" => Ok((VK_DIVIDE, KEYEVENTF_EXTENDEDKEY)),
        "NumpadDecimal" => Ok((VK_DECIMAL, KEYBD_EVENT_FLAGS(0))),
        "NumpadEnter" => Ok((VK_RETURN, KEYEVENTF_EXTENDEDKEY)),
        "NumpadEqual" => Ok((VK_OEM_PLUS, KEYBD_EVENT_FLAGS(0))),
        "ArrowDown" => Ok((VK_DOWN, KEYEVENTF_EXTENDEDKEY)),
        "ArrowUp" => Ok((VK_UP, KEYEVENTF_EXTENDEDKEY)),
        "ArrowLeft" => Ok((VK_LEFT, KEYEVENTF_EXTENDEDKEY)),
        "ArrowRight" => Ok((VK_RIGHT, KEYEVENTF_EXTENDEDKEY)),
        _ => Err(windows_dispatch_error(
            "input.keyboard.unsupported_key",
            format!("unsupported Windows key_code: {key_code}"),
        )),
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn keyboard_input(virtual_key: VIRTUAL_KEY, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: virtual_key,
                wScan: 0,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_keyboard_inputs(
    key_code: &str,
    action: InputAction,
    modifiers: &[String],
) -> Result<Vec<INPUT>, HostDispatchError> {
    let (virtual_key, extra_flags) = windows_key_code(key_code)?;
    let modifier_keys = modifiers
        .iter()
        .map(|modifier| windows_key_code(modifier))
        .collect::<Result<Vec<_>, _>>()?;
    let mut inputs = Vec::with_capacity(modifier_keys.len() + 1);

    match action {
        InputAction::Down => {
            for (modifier_key, modifier_flags) in &modifier_keys {
                inputs.push(keyboard_input(*modifier_key, *modifier_flags));
            }
            inputs.push(keyboard_input(virtual_key, extra_flags));
        }
        InputAction::Up => {
            inputs.push(keyboard_input(virtual_key, extra_flags | KEYEVENTF_KEYUP));
            for (modifier_key, modifier_flags) in modifier_keys.iter().rev() {
                inputs.push(keyboard_input(
                    *modifier_key,
                    *modifier_flags | KEYEVENTF_KEYUP,
                ));
            }
        }
    }

    Ok(inputs)
}

#[cfg(all(target_os = "windows", not(test)))]
fn mouse_input(flags: MOUSE_EVENT_FLAGS, mouse_data: u32) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx: 0,
                dy: 0,
                mouseData: mouse_data,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn wheel_delta_data(delta: f64) -> i32 {
    delta.round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

#[cfg(all(target_os = "macos", not(test)))]
fn post_mouse_event(
    event_type: CGEventType,
    location: CGPoint,
    button: CGMouseButton,
) -> Result<(), HostDispatchError> {
    let source = event_source()?;
    let event = CGEvent::new_mouse_event(Some(source.as_ref()), event_type, location, button)
        .ok_or_else(|| {
            macos_dispatch_error(
                "event.mouse.create_failed",
                "failed to create macOS mouse event",
            )
        })?;
    CGEvent::post(CGEventTapLocation::HIDEventTap, Some(event.as_ref()));
    Ok(())
}

#[cfg(all(target_os = "macos", not(test)))]
fn post_keyboard_event(key_code: CGKeyCode, key_down: bool) -> Result<(), HostDispatchError> {
    let source = event_source()?;
    let event = CGEvent::new_keyboard_event(Some(source.as_ref()), key_code, key_down).ok_or_else(
        || {
            macos_dispatch_error(
                "event.keyboard.create_failed",
                "failed to create macOS keyboard event",
            )
        },
    )?;
    CGEvent::post(CGEventTapLocation::HIDEventTap, Some(event.as_ref()));
    Ok(())
}

#[cfg(all(target_os = "macos", not(test)))]
fn dispatch_macos_keyboard_input(
    key_code: &str,
    action: InputAction,
    modifiers: &[String],
) -> Result<(), HostDispatchError> {
    let key = macos_key_code(key_code)?;
    let modifier_keys = modifiers
        .iter()
        .map(|modifier| macos_key_code(modifier))
        .collect::<Result<Vec<_>, _>>()?;

    match action {
        InputAction::Down => {
            for modifier in &modifier_keys {
                post_keyboard_event(*modifier, true)?;
            }
            post_keyboard_event(key, true)?;
        }
        InputAction::Up => {
            post_keyboard_event(key, false)?;
            for modifier in modifier_keys.iter().rev() {
                post_keyboard_event(*modifier, false)?;
            }
        }
    }

    Ok(())
}

#[cfg(all(target_os = "macos", not(test)))]
fn post_scroll_event(delta_x: f64, delta_y: f64) -> Result<(), HostDispatchError> {
    let source = event_source()?;
    let event = CGEvent::new_scroll_wheel_event2(
        Some(source.as_ref()),
        CGScrollEventUnit::Line,
        2,
        delta_y.round() as i32,
        delta_x.round() as i32,
        0,
    )
    .ok_or_else(|| {
        macos_dispatch_error(
            "event.scroll.create_failed",
            "failed to create macOS scroll event",
        )
    })?;
    CGEvent::post(CGEventTapLocation::HIDEventTap, Some(event.as_ref()));
    Ok(())
}

#[cfg(all(target_os = "macos", not(test)))]
fn event_source() -> Result<CFRetained<CGEventSource>, HostDispatchError> {
    CGEventSource::new(CGEventSourceStateID::HIDSystemState).ok_or_else(|| {
        macos_dispatch_error(
            "event.source.create_failed",
            "failed to create macOS event source",
        )
    })
}

#[cfg(all(target_os = "macos", not(test)))]
fn ensure_macos_input_permission() -> Result<(), HostDispatchError> {
    if macos_input_permission_granted() {
        Ok(())
    } else {
        Err(HostDispatchError {
            executor: "macos.cg_event",
            code: "permission.accessibility_required",
            detail: "macOS input injection requires Accessibility permission for this app"
                .to_string(),
        })
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn active_mouse_button_state() -> &'static Mutex<Option<PointerButton>> {
    MACOS_ACTIVE_MOUSE_BUTTON.get_or_init(|| Mutex::new(None))
}

#[cfg(all(target_os = "macos", not(test)))]
fn current_active_mouse_button() -> Option<PointerButton> {
    active_mouse_button_state()
        .lock()
        .map(|guard| *guard)
        .unwrap_or(None)
}

#[cfg(all(target_os = "macos", not(test)))]
fn update_active_mouse_button(button: PointerButton, action: InputAction) {
    if let Ok(mut active_button) = active_mouse_button_state().lock() {
        match action {
            InputAction::Down => {
                *active_button = Some(button);
            }
            InputAction::Up => {
                if *active_button == Some(button) {
                    *active_button = None;
                }
            }
        }
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn mouse_move_event() -> (CGEventType, CGMouseButton) {
    match current_active_mouse_button() {
        Some(PointerButton::Left) => (CGEventType::LeftMouseDragged, CGMouseButton::Left),
        Some(PointerButton::Right) => (CGEventType::RightMouseDragged, CGMouseButton::Right),
        _ => (CGEventType::MouseMoved, CGMouseButton::Left),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_input_permission_granted() -> bool {
    unsafe { AXIsProcessTrusted() }
}

#[cfg(all(target_os = "macos", not(test)))]
#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    fn AXIsProcessTrusted() -> bool;
}

#[cfg(all(target_os = "macos", not(test)))]
fn pointer_location(x: f64, y: f64) -> Result<PointerLocationResult, HostDispatchError> {
    if !x.is_finite() || !y.is_finite() || !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
        return Err(macos_dispatch_error(
            "input.mouse.coordinate.invalid",
            format!(
                "pointer coordinates must be finite normalized values between 0.0 and 1.0, got x={x:.4}, y={y:.4}"
            ),
        ));
    }

    let bounds = active_capture_bounds()
        .map_err(|detail| macos_dispatch_error("input.mouse.mapping_unavailable", detail))?;

    Ok(PointerLocationResult {
        point: point_in_bounds(bounds, x, y),
    })
}

#[cfg(all(target_os = "macos", not(test)))]
fn active_capture_bounds() -> Result<objc2_core_foundation::CGRect, String> {
    let Some(source) = runtime_capture::capture_active_source() else {
        return Err("active capture source is not selected".to_string());
    };

    match source.kind {
        CaptureSourceKind::Display => display_bounds_for_source_id(&source.source_id),
        CaptureSourceKind::Window => Err(format!(
            "active capture source {} is not supported for pointer mapping",
            source.source_id
        )),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn display_bounds_for_source_id(source_id: &str) -> Result<objc2_core_foundation::CGRect, String> {
    let raw_value = source_id
        .strip_prefix("display:")
        .ok_or_else(|| format!("unsupported macOS capture source id: {source_id}"))?;
    let display_id = raw_value
        .parse::<CGDirectDisplayID>()
        .map_err(|error| format!("invalid macOS capture source id {source_id}: {error}"))?;

    let bounds = CGDisplayBounds(display_id);
    let width = CGRectGetMaxX(bounds) - CGRectGetMinX(bounds);
    let height = CGRectGetMaxY(bounds) - CGRectGetMinY(bounds);
    if !width.is_finite() || !height.is_finite() || width <= 0.0 || height <= 0.0 {
        return Err(format!(
            "macOS display source {source_id} has invalid bounds {width}x{height}"
        ));
    }

    Ok(bounds)
}

#[cfg(all(target_os = "macos", not(test)))]
fn point_in_bounds(bounds: objc2_core_foundation::CGRect, x: f64, y: f64) -> CGPoint {
    let min_x = CGRectGetMinX(bounds);
    let min_y = CGRectGetMinY(bounds);
    let max_x = CGRectGetMaxX(bounds);
    let max_y = CGRectGetMaxY(bounds);
    let width = max_x - min_x;
    let height = max_y - min_y;
    let normalized_x = x.clamp(0.0, 1.0 - f64::EPSILON);
    let normalized_y = y.clamp(0.0, 1.0 - f64::EPSILON);

    CGPoint {
        x: min_x + (width * normalized_x),
        y: min_y + (height * normalized_y),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn mouse_button_event(
    button: PointerButton,
    action: InputAction,
) -> Result<(CGEventType, CGMouseButton), HostDispatchError> {
    match (button, action) {
        (PointerButton::Left, InputAction::Down) => {
            Ok((CGEventType::LeftMouseDown, CGMouseButton::Left))
        }
        (PointerButton::Left, InputAction::Up) => {
            Ok((CGEventType::LeftMouseUp, CGMouseButton::Left))
        }
        (PointerButton::Right, InputAction::Down) => {
            Ok((CGEventType::RightMouseDown, CGMouseButton::Right))
        }
        (PointerButton::Right, InputAction::Up) => {
            Ok((CGEventType::RightMouseUp, CGMouseButton::Right))
        }
        (PointerButton::Middle, _) => Err(macos_dispatch_error(
            "input.mouse.unsupported_button",
            "macOS executor does not support middle mouse yet",
        )),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_key_code(key_code: &str) -> Result<CGKeyCode, HostDispatchError> {
    match key_code {
        "KeyA" => Ok(0),
        "KeyS" => Ok(1),
        "KeyD" => Ok(2),
        "KeyF" => Ok(3),
        "KeyH" => Ok(4),
        "KeyG" => Ok(5),
        "KeyZ" => Ok(6),
        "KeyX" => Ok(7),
        "KeyC" => Ok(8),
        "KeyV" => Ok(9),
        "KeyB" => Ok(11),
        "KeyQ" => Ok(12),
        "KeyW" => Ok(13),
        "KeyE" => Ok(14),
        "KeyR" => Ok(15),
        "KeyY" => Ok(16),
        "KeyT" => Ok(17),
        "Digit1" => Ok(18),
        "Digit2" => Ok(19),
        "Digit3" => Ok(20),
        "Digit4" => Ok(21),
        "Digit6" => Ok(22),
        "Digit5" => Ok(23),
        "Equal" => Ok(24),
        "Digit9" => Ok(25),
        "Digit7" => Ok(26),
        "Minus" => Ok(27),
        "Digit8" => Ok(28),
        "Digit0" => Ok(29),
        "BracketRight" => Ok(30),
        "KeyO" => Ok(31),
        "KeyU" => Ok(32),
        "BracketLeft" => Ok(33),
        "KeyI" => Ok(34),
        "KeyP" => Ok(35),
        "Enter" => Ok(36),
        "KeyL" => Ok(37),
        "KeyJ" => Ok(38),
        "Quote" => Ok(39),
        "KeyK" => Ok(40),
        "Semicolon" => Ok(41),
        "Backslash" => Ok(42),
        "Comma" => Ok(43),
        "Slash" => Ok(44),
        "KeyN" => Ok(45),
        "KeyM" => Ok(46),
        "Period" => Ok(47),
        "Tab" => Ok(48),
        "Space" => Ok(49),
        "Backquote" => Ok(50),
        "Backspace" => Ok(51),
        "Escape" => Ok(53),
        "MetaLeft" => Ok(55),
        "MetaRight" => Ok(54),
        "ShiftLeft" => Ok(56),
        "CapsLock" => Ok(57),
        "AltLeft" => Ok(58),
        "ControlLeft" => Ok(59),
        "ShiftRight" => Ok(60),
        "AltRight" => Ok(61),
        "ControlRight" => Ok(62),
        // 作者: long；macOS 的 CGKeyCode 不等同于字符编码，远控键盘按物理键位映射才能让快捷键和导航键稳定生效。
        "F1" => Ok(122),
        "F2" => Ok(120),
        "F3" => Ok(99),
        "F4" => Ok(118),
        "F5" => Ok(96),
        "F6" => Ok(97),
        "F7" => Ok(98),
        "F8" => Ok(100),
        "F9" => Ok(101),
        "F10" => Ok(109),
        "F11" => Ok(103),
        "F12" => Ok(111),
        "Insert" => Ok(114),
        "Delete" => Ok(117),
        "Home" => Ok(115),
        "End" => Ok(119),
        "PageUp" => Ok(116),
        "PageDown" => Ok(121),
        "PrintScreen" => Ok(105),
        "ScrollLock" => Ok(107),
        "Pause" => Ok(113),
        "NumLock" => Ok(71),
        "Numpad0" => Ok(82),
        "Numpad1" => Ok(83),
        "Numpad2" => Ok(84),
        "Numpad3" => Ok(85),
        "Numpad4" => Ok(86),
        "Numpad5" => Ok(87),
        "Numpad6" => Ok(88),
        "Numpad7" => Ok(89),
        "Numpad8" => Ok(91),
        "Numpad9" => Ok(92),
        "NumpadAdd" => Ok(69),
        "NumpadSubtract" => Ok(78),
        "NumpadMultiply" => Ok(67),
        "NumpadDivide" => Ok(75),
        "NumpadDecimal" => Ok(65),
        "NumpadEnter" => Ok(76),
        "NumpadEqual" => Ok(81),
        "ArrowDown" => Ok(125),
        "ArrowUp" => Ok(126),
        "ArrowLeft" => Ok(123),
        "ArrowRight" => Ok(124),
        _ => Err(macos_dispatch_error(
            "input.keyboard.unsupported_key",
            format!("unsupported macOS key_code: {key_code}"),
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_platform_stub_matches_detected_platform() {
        let platform = current_platform();
        let label = current_platform_stub();

        match platform {
            DesktopPlatform::Macos => assert_eq!(label, "macos"),
            DesktopPlatform::Windows => assert_eq!(label, "windows"),
            DesktopPlatform::Unsupported => assert_eq!(label, "unsupported"),
        }
    }

    #[test]
    fn dispatch_host_input_selects_platform_executor() {
        let input = HostInputCommand::MouseMove { x: 0.5, y: 0.25 };
        let result = dispatch_host_input(&input);

        match current_platform() {
            DesktopPlatform::Macos => {
                let result = result.expect("macos should use test executor");
                assert_eq!(result.executor, "macos.test_stub");
                assert!(!result.applied);
                assert_eq!(result.status_code, "stub.macos_test");
            }
            DesktopPlatform::Windows => {
                let result = result.expect("windows should use test executor");
                assert_eq!(result.executor, "windows.test_stub");
                assert!(!result.applied);
                assert_eq!(result.status_code, "stub.windows_test");
            }
            DesktopPlatform::Unsupported => {
                let error = result.expect_err("unsupported platform should fail");
                assert_eq!(error.executor, "unsupported");
                assert_eq!(error.code, "platform.unsupported");
                assert_eq!(error.detail, "host input is not supported on this platform");
            }
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_test_executor_skips_real_event_post() {
        let input = HostInputCommand::KeyboardKey {
            key_code: "KeyA".to_string(),
            action: InputAction::Down,
            modifiers: Vec::new(),
        };

        let result = dispatch_host_input(&input).expect("macos test executor should succeed");
        assert_eq!(result.executor, "macos.test_stub");
        assert!(!result.applied);
        assert_eq!(result.status_code, "stub.macos_test");
    }
}
