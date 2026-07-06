use crate::platform;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    mpsc,
    mpsc::Sender,
    Arc, Mutex, OnceLock,
};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const DEFAULT_CAPTURE_MAX_WIDTH: u32 = 1280;
const DEFAULT_CAPTURE_MAX_HEIGHT: u32 = 720;
const DEFAULT_CAPTURE_MAX_FPS: u16 = 24;
const DEFAULT_CAPTURE_CODEC: &str = "jpeg-frame-stream";
const CAPTURE_STREAM_BOUNDARY: &str = "rdframe";
const CAPTURE_STREAM_PATH: &str = "/capture.mjpeg";
const CAPTURE_SNAPSHOT_FAST_PATH_MAX_AGE_MS: u64 = 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CaptureLifecycleState {
    Idle,
    AwaitingPermission,
    Ready,
    Starting,
    Running,
    Paused,
    Stopping,
    Failed,
}

impl CaptureLifecycleState {
    fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::AwaitingPermission => "awaiting_permission",
            Self::Ready => "ready",
            Self::Starting => "starting",
            Self::Running => "running",
            Self::Paused => "paused",
            Self::Stopping => "stopping",
            Self::Failed => "failed",
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CaptureSourceKind {
    Display,
    Window,
}

#[derive(Debug, Clone, Serialize)]
pub struct CaptureSourceSummary {
    pub source_id: String,
    pub kind: CaptureSourceKind,
    pub title: String,
    pub backend: String,
    pub width: u32,
    pub height: u32,
    pub is_primary: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct CapturePermissionState {
    pub capability: &'static str,
    pub status: &'static str,
    pub can_request: bool,
    pub detail: String,
}

pub type CaptureCapabilities = platform::DesktopCaptureCapabilities;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CaptureConfig {
    pub max_width: u32,
    pub max_height: u32,
    pub max_fps: u16,
    pub codec: String,
    pub source_rect_x_ppm: u32,
    pub source_rect_y_ppm: u32,
    pub source_rect_width_ppm: u32,
    pub source_rect_height_ppm: u32,
}

impl Default for CaptureConfig {
    fn default() -> Self {
        Self {
            max_width: DEFAULT_CAPTURE_MAX_WIDTH,
            max_height: DEFAULT_CAPTURE_MAX_HEIGHT,
            max_fps: DEFAULT_CAPTURE_MAX_FPS,
            codec: DEFAULT_CAPTURE_CODEC.to_string(),
            source_rect_x_ppm: 0,
            source_rect_y_ppm: 0,
            source_rect_width_ppm: 1_000_000,
            source_rect_height_ppm: 1_000_000,
        }
    }
}

impl CaptureConfig {
    fn apply_patch(&mut self, patch: &CaptureConfigPatch) -> Result<(), String> {
        if let Some(max_width) = patch.max_width {
            if max_width == 0 {
                return Err("capture config max_width must be greater than 0".to_string());
            }
            self.max_width = max_width;
        }
        if let Some(max_height) = patch.max_height {
            if max_height == 0 {
                return Err("capture config max_height must be greater than 0".to_string());
            }
            self.max_height = max_height;
        }
        if let Some(max_fps) = patch.max_fps {
            if max_fps == 0 {
                return Err("capture config max_fps must be greater than 0".to_string());
            }
            self.max_fps = max_fps;
        }
        if let Some(codec) = patch.codec.as_ref() {
            let codec = codec.trim();
            if codec.is_empty() {
                return Err("capture config codec must not be blank".to_string());
            }
            self.codec = codec.to_string();
        }
        let next_source_rect_x_ppm = patch.source_rect_x_ppm.unwrap_or(self.source_rect_x_ppm);
        let next_source_rect_y_ppm = patch.source_rect_y_ppm.unwrap_or(self.source_rect_y_ppm);
        let next_source_rect_width_ppm = patch
            .source_rect_width_ppm
            .unwrap_or(self.source_rect_width_ppm);
        let next_source_rect_height_ppm = patch
            .source_rect_height_ppm
            .unwrap_or(self.source_rect_height_ppm);
        validate_source_rect_ppm(
            next_source_rect_x_ppm,
            next_source_rect_y_ppm,
            next_source_rect_width_ppm,
            next_source_rect_height_ppm,
        )?;
        self.source_rect_x_ppm = next_source_rect_x_ppm;
        self.source_rect_y_ppm = next_source_rect_y_ppm;
        self.source_rect_width_ppm = next_source_rect_width_ppm;
        self.source_rect_height_ppm = next_source_rect_height_ppm;

        Ok(())
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureConfigPatch {
    #[serde(default)]
    pub max_width: Option<u32>,
    #[serde(default)]
    pub max_height: Option<u32>,
    #[serde(default)]
    pub max_fps: Option<u16>,
    #[serde(default)]
    pub codec: Option<String>,
    #[serde(default)]
    pub source_rect_x_ppm: Option<u32>,
    #[serde(default)]
    pub source_rect_y_ppm: Option<u32>,
    #[serde(default)]
    pub source_rect_width_ppm: Option<u32>,
    #[serde(default)]
    pub source_rect_height_ppm: Option<u32>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureStartRequest {
    #[serde(default)]
    pub source_id: String,
    #[serde(default)]
    pub config: Option<CaptureConfigPatch>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CaptureStatus {
    pub lifecycle: String,
    pub backend: String,
    pub capabilities: CaptureCapabilities,
    pub active_source: Option<CaptureSourceSummary>,
    pub config: CaptureConfig,
    pub permission: CapturePermissionState,
    pub supports_frame_streaming: bool,
    pub supports_pause_resume: bool,
    pub last_error_code: String,
    pub last_error_detail: String,
    pub last_transition_at: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct CaptureFrameEnvelope {
    pub frame_id: String,
    pub mime_type: &'static str,
    pub content_b64: String,
    pub capture_ts: u64,
    pub frame_width: u32,
    pub frame_height: u32,
}

#[derive(Debug, Clone)]
pub struct CaptureFrameBytes {
    pub mime_type: &'static str,
    pub encoded_bytes: Vec<u8>,
    pub capture_ts: u64,
    pub frame_width: u32,
    pub frame_height: u32,
}

#[derive(Debug, Clone)]
pub(crate) struct CaptureFrameSnapshot {
    // 作者: long；原生 sender 和 MJPEG 流共用最新帧缓存，避免 720p raw 帧在热路径里反复整帧复制。
    pub(crate) mime_type: &'static str,
    pub(crate) encoded_bytes: Arc<[u8]>,
    pub(crate) capture_ts: u64,
    pub(crate) frame_width: u32,
    pub(crate) frame_height: u32,
}

#[derive(Debug, Clone, Serialize)]
pub struct CaptureStreamEndpoint {
    pub url: String,
    pub boundary: String,
}

#[derive(Debug, Clone)]
struct CaptureRuntimeState {
    lifecycle: CaptureLifecycleState,
    active_source: Option<CaptureSourceSummary>,
    config: CaptureConfig,
    last_error_code: String,
    last_error_detail: String,
    last_transition_at: u64,
    frame_seq: u64,
}

impl Default for CaptureRuntimeState {
    fn default() -> Self {
        Self {
            lifecycle: CaptureLifecycleState::Idle,
            active_source: None,
            config: CaptureConfig::default(),
            last_error_code: String::new(),
            last_error_detail: String::new(),
            last_transition_at: now_ms(),
            frame_seq: 0,
        }
    }
}

#[derive(Debug, Default)]
struct CaptureFrameWorkerState {
    latest_frame: Option<CaptureFrameSnapshot>,
    stop_tx: Option<Sender<()>>,
    handle: Option<JoinHandle<()>>,
}

#[derive(Debug, Default)]
struct CaptureStreamServerState {
    endpoint_url: Option<String>,
    stop_tx: Option<Sender<()>>,
    handle: Option<JoinHandle<()>>,
    shutdown: Option<Arc<AtomicBool>>,
}

static CAPTURE_RUNTIME_STATE: OnceLock<Mutex<CaptureRuntimeState>> = OnceLock::new();
static CAPTURE_FRAME_WORKER_STATE: OnceLock<Mutex<CaptureFrameWorkerState>> = OnceLock::new();
static CAPTURE_STREAM_SERVER_STATE: OnceLock<Mutex<CaptureStreamServerState>> = OnceLock::new();

fn trace_capture(event: &str, detail: impl AsRef<str>) {
    eprintln!(
        "[rd.capture] ts_ms={} {event} {}",
        now_ms(),
        detail.as_ref()
    );
}

pub fn capture_get_capabilities() -> CaptureCapabilities {
    platform::capture_capabilities()
}

pub fn capture_get_permission_state() -> CapturePermissionState {
    platform::capture_get_permission_state()
}

pub fn capture_request_permission() -> Result<CapturePermissionState, String> {
    let permission = platform::capture_request_permission()?;
    let mut state = capture_runtime_state()
        .lock()
        .map_err(|_| "capture runtime state poisoned".to_string())?;

    if permission.status == "granted" {
        clear_error(&mut state);
        state.lifecycle = if state.active_source.is_some() {
            CaptureLifecycleState::Ready
        } else {
            CaptureLifecycleState::Idle
        };
    } else if permission.can_request {
        state.lifecycle = CaptureLifecycleState::AwaitingPermission;
        set_error(
            &mut state,
            "capture.permission.required",
            permission.detail.clone(),
        );
    } else {
        state.lifecycle = CaptureLifecycleState::Failed;
        set_error(
            &mut state,
            "capture.permission.unavailable",
            permission.detail.clone(),
        );
    }
    state.last_transition_at = now_ms();

    Ok(permission)
}

pub fn capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    if platform::capture_supports_permission_check() {
        let permission = platform::capture_get_permission_state();
        if permission.status != "granted" {
            return Ok(Vec::new());
        }
    }

    platform::capture_list_sources()
}

pub fn capture_status() -> Result<CaptureStatus, String> {
    let state = capture_runtime_state()
        .lock()
        .map_err(|_| "capture runtime state poisoned".to_string())?
        .clone();
    Ok(snapshot_status(&state))
}

pub fn capture_active_source() -> Option<CaptureSourceSummary> {
    capture_runtime_state()
        .lock()
        .ok()
        .and_then(|state| state.active_source.clone())
}

pub fn capture_take_frame() -> Result<CaptureFrameEnvelope, String> {
    let frame = capture_take_frame_bytes()?;
    let frame_seq = {
        let state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;
        state.frame_seq
    };
    Ok(CaptureFrameEnvelope {
        frame_id: format!("frame-{}-{frame_seq}", frame.capture_ts),
        mime_type: frame.mime_type,
        content_b64: STANDARD.encode(&frame.encoded_bytes),
        capture_ts: frame.capture_ts,
        frame_width: frame.frame_width,
        frame_height: frame.frame_height,
    })
}

pub fn capture_take_frame_bytes() -> Result<CaptureFrameBytes, String> {
    let frame = capture_take_frame_snapshot()?;
    Ok(CaptureFrameBytes {
        mime_type: frame.mime_type,
        encoded_bytes: frame.encoded_bytes.to_vec(),
        capture_ts: frame.capture_ts,
        frame_width: frame.frame_width,
        frame_height: frame.frame_height,
    })
}

pub(crate) fn capture_take_frame_snapshot() -> Result<CaptureFrameSnapshot, String> {
    // 作者: long；native sender 逐帧取缓存时不能重复触发系统权限探测，过旧帧再回到完整校验路径避免输出停滞画面。
    if let Some(frame) = latest_cached_frame() {
        if now_ms().saturating_sub(frame.capture_ts) <= CAPTURE_SNAPSHOT_FAST_PATH_MAX_AGE_MS {
            if let Ok(mut state) = capture_runtime_state().lock() {
                clear_error(&mut state);
                state.lifecycle = CaptureLifecycleState::Running;
                state.last_transition_at = frame.capture_ts;
            }
            return Ok(frame);
        }
    }

    let (source, config) = {
        let mut state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;

        let permission = platform::capture_get_permission_state();
        if permission.status != "granted" {
            trace_capture(
                "take_frame.permission_denied",
                format!("status={} detail={}", permission.status, permission.detail),
            );
            mark_failed(
                &mut state,
                "capture.permission.required",
                permission.detail.clone(),
            );
            return Err(permission.detail);
        }

        let Some(source) = state.active_source.clone() else {
            trace_capture(
                "take_frame.source_missing",
                "no active capture source is selected",
            );
            mark_failed(
                &mut state,
                "capture.source.missing",
                "no active capture source is selected",
            );
            return Err("no active capture source is selected".to_string());
        };

        state.frame_seq += 1;
        (source, state.config.clone())
    };

    if let Some(frame) = latest_cached_frame() {
        if now_ms().saturating_sub(frame.capture_ts) <= CAPTURE_SNAPSHOT_FAST_PATH_MAX_AGE_MS {
            if let Ok(mut state) = capture_runtime_state().lock() {
                clear_error(&mut state);
                state.lifecycle = CaptureLifecycleState::Running;
                state.last_transition_at = frame.capture_ts;
            }
            return Ok(frame);
        }
    }

    let frame = platform::capture_take_frame(&source, &config).map_err(|detail| {
        trace_capture(
            "take_frame.failed",
            format!("source={} reason={detail}", source.source_id),
        );
        if let Ok(mut state) = capture_runtime_state().lock() {
            mark_failed(&mut state, "capture.frame.unavailable", detail.clone());
        }
        detail
    })?;

    let capture_ts = now_ms();
    if let Ok(mut state) = capture_runtime_state().lock() {
        clear_error(&mut state);
        state.lifecycle = CaptureLifecycleState::Running;
        state.last_transition_at = capture_ts;
    }

    Ok(CaptureFrameSnapshot {
        mime_type: frame.mime_type,
        encoded_bytes: Arc::from(frame.encoded_bytes.into_boxed_slice()),
        capture_ts,
        frame_width: frame.width,
        frame_height: frame.height,
    })
}

pub fn capture_get_stream_endpoint() -> Result<CaptureStreamEndpoint, String> {
    {
        let state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;
        if state.active_source.is_none() {
            return Err("no active capture source is selected".to_string());
        }
    }

    let url = start_capture_stream_server()?;
    Ok(CaptureStreamEndpoint {
        url,
        boundary: CAPTURE_STREAM_BOUNDARY.to_string(),
    })
}

pub fn capture_start(request: Option<CaptureStartRequest>) -> Result<CaptureStatus, String> {
    trace_capture(
        "start.request",
        format!(
            "requested_source={} config_patch={}",
            request
                .as_ref()
                .map(|value| value.source_id.trim())
                .unwrap_or_default(),
            request
                .as_ref()
                .and_then(|value| value.config.as_ref())
                .is_some()
        ),
    );
    {
        let mut state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;

        if let Some(config_patch) = request.as_ref().and_then(|value| value.config.as_ref()) {
            state.config.apply_patch(config_patch)?;
        }

        state.lifecycle = CaptureLifecycleState::Starting;
        state.last_transition_at = now_ms();
        clear_error(&mut state);
        state.active_source = None;

        let permission = platform::capture_get_permission_state();
        if permission.status != "granted" {
            if permission.can_request {
                state.lifecycle = CaptureLifecycleState::AwaitingPermission;
                set_error(&mut state, "capture.permission.required", permission.detail);
                state.last_transition_at = now_ms();
                trace_capture("start.awaiting_permission", &state.last_error_detail);
            } else {
                mark_failed(
                    &mut state,
                    "capture.permission.unavailable",
                    permission.detail,
                );
                trace_capture("start.permission_unavailable", &state.last_error_detail);
            }
            return Ok(snapshot_status(&state));
        }

        let sources = match platform::capture_list_sources() {
            Ok(sources) => sources,
            Err(detail) => {
                mark_failed(&mut state, "capture.sources.unavailable", detail);
                trace_capture("start.sources_unavailable", &state.last_error_detail);
                return Ok(snapshot_status(&state));
            }
        };

        let requested_source_id = request
            .as_ref()
            .map(|value| value.source_id.trim())
            .unwrap_or_default();
        let Some(source) = select_capture_source(&sources, requested_source_id) else {
            mark_failed(
                &mut state,
                "capture.source.not_found",
                if requested_source_id.is_empty() {
                    "no capture source is available".to_string()
                } else {
                    format!("capture source {} was not found", requested_source_id)
                },
            );
            trace_capture("start.source_not_found", &state.last_error_detail);
            return Ok(snapshot_status(&state));
        };

        trace_capture(
            "start.source_selected",
            format!(
                "source={} title={} size={}x{}",
                source.source_id, source.title, source.width, source.height
            ),
        );
        state.active_source = Some(source);
        clear_error(&mut state);
        state.lifecycle = CaptureLifecycleState::Ready;
        state.last_transition_at = now_ms();
    }

    if let Err(detail) = start_capture_worker() {
        let mut state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;
        mark_failed(&mut state, "capture.worker.start_failed", detail);
        trace_capture("start.worker_failed", &state.last_error_detail);
        return Ok(snapshot_status(&state));
    }
    trace_capture("start.ok", "capture worker started");

    capture_status()
}

pub fn capture_stop() -> Result<CaptureStatus, String> {
    let snapshot = {
        let mut state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;

        state.lifecycle = CaptureLifecycleState::Stopping;
        state.last_transition_at = now_ms();
        state.active_source = None;
        clear_error(&mut state);
        state.lifecycle = CaptureLifecycleState::Idle;
        state.last_transition_at = now_ms();
        snapshot_status(&state)
    };

    stop_capture_worker();
    stop_capture_stream_server();
    trace_capture("stop.ok", "capture worker stopped");
    Ok(snapshot)
}

pub fn capture_pause() -> Result<CaptureStatus, String> {
    let mut state = capture_runtime_state()
        .lock()
        .map_err(|_| "capture runtime state poisoned".to_string())?;

    if !platform::capture_supports_pause_resume() {
        set_error(
            &mut state,
            "capture.pause.unsupported",
            format!(
                "capture pause is not supported by {}",
                platform::capture_backend_name()
            ),
        );
        state.last_transition_at = now_ms();
        return Ok(snapshot_status(&state));
    }

    if matches!(state.lifecycle, CaptureLifecycleState::Running) {
        clear_error(&mut state);
        state.lifecycle = CaptureLifecycleState::Paused;
        state.last_transition_at = now_ms();
        trace_capture("pause.ok", "capture paused");
    }

    Ok(snapshot_status(&state))
}

pub fn capture_resume() -> Result<CaptureStatus, String> {
    let mut state = capture_runtime_state()
        .lock()
        .map_err(|_| "capture runtime state poisoned".to_string())?;

    if !platform::capture_supports_pause_resume() {
        set_error(
            &mut state,
            "capture.resume.unsupported",
            format!(
                "capture resume is not supported by {}",
                platform::capture_backend_name()
            ),
        );
        state.last_transition_at = now_ms();
        return Ok(snapshot_status(&state));
    }

    if matches!(state.lifecycle, CaptureLifecycleState::Paused) {
        clear_error(&mut state);
        state.lifecycle = CaptureLifecycleState::Running;
        state.last_transition_at = now_ms();
        trace_capture("resume.ok", "capture resumed");
    }

    Ok(snapshot_status(&state))
}

pub fn capture_update_config(patch: CaptureConfigPatch) -> Result<CaptureStatus, String> {
    let (status, config_changed) = {
        let mut state = capture_runtime_state()
            .lock()
            .map_err(|_| "capture runtime state poisoned".to_string())?;
        let previous_config = state.config.clone();

        state.config.apply_patch(&patch)?;
        let config_changed = state.config != previous_config;
        trace_capture(
            "config.updated",
            format!(
                "max_width={} max_height={} max_fps={} codec={} source_rect_ppm={},{},{},{}",
                state.config.max_width,
                state.config.max_height,
                state.config.max_fps,
                state.config.codec,
                state.config.source_rect_x_ppm,
                state.config.source_rect_y_ppm,
                state.config.source_rect_width_ppm,
                state.config.source_rect_height_ppm,
            ),
        );
        clear_error(&mut state);
        state.last_transition_at = now_ms();

        (snapshot_status(&state), config_changed)
    };

    if config_changed {
        clear_latest_cached_frame();
    }

    Ok(status)
}

fn capture_runtime_state() -> &'static Mutex<CaptureRuntimeState> {
    CAPTURE_RUNTIME_STATE.get_or_init(|| Mutex::new(CaptureRuntimeState::default()))
}

fn capture_frame_worker_state() -> &'static Mutex<CaptureFrameWorkerState> {
    CAPTURE_FRAME_WORKER_STATE.get_or_init(|| Mutex::new(CaptureFrameWorkerState::default()))
}

fn capture_stream_server_state() -> &'static Mutex<CaptureStreamServerState> {
    CAPTURE_STREAM_SERVER_STATE.get_or_init(|| Mutex::new(CaptureStreamServerState::default()))
}

fn capture_frame_interval(config: &CaptureConfig) -> Duration {
    let fps = u64::from(config.max_fps.max(1));
    let interval_ms = (1000 / fps).max(16);
    Duration::from_millis(interval_ms)
}

fn latest_cached_frame() -> Option<CaptureFrameSnapshot> {
    capture_frame_worker_state()
        .lock()
        .ok()
        .and_then(|state| state.latest_frame.clone())
}

fn clear_latest_cached_frame() {
    if let Ok(mut state) = capture_frame_worker_state().lock() {
        // 作者: long；远控交互会在低延迟档和清晰档之间切换，清掉旧帧能让下一次兜底推帧立刻按新尺寸重采，避免手机端继续显示上一档发虚画面。
        state.latest_frame = None;
    }
}

fn validate_source_rect_ppm(
    x_ppm: u32,
    y_ppm: u32,
    width_ppm: u32,
    height_ppm: u32,
) -> Result<(), String> {
    const SOURCE_RECT_UNITS: u32 = 1_000_000;
    if width_ppm == 0 || height_ppm == 0 {
        return Err("capture config source rect size must be greater than 0".to_string());
    }
    if x_ppm > SOURCE_RECT_UNITS
        || y_ppm > SOURCE_RECT_UNITS
        || width_ppm > SOURCE_RECT_UNITS
        || height_ppm > SOURCE_RECT_UNITS
        || x_ppm.saturating_add(width_ppm) > SOURCE_RECT_UNITS
        || y_ppm.saturating_add(height_ppm) > SOURCE_RECT_UNITS
    {
        return Err("capture config source rect must stay within [0,1]".to_string());
    }
    Ok(())
}

fn start_capture_worker() -> Result<(), String> {
    stop_capture_worker();

    let (stop_tx, stop_rx) = mpsc::channel::<()>();
    let handle = thread::Builder::new()
        .name("rd-capture-worker".to_string())
        .spawn(move || {
            let mut sampled_frame_count: u64 = 0;
            let mut capture_elapsed_sum_ms: u64 = 0;
            let mut capture_elapsed_max_ms: u64 = 0;
            let mut capture_loop_overrun_count: u64 = 0;
            let mut previous_capture_ts: u64 = 0;
            loop {
                if stop_rx.try_recv().is_ok() {
                    break;
                }

                let (source, config) = match capture_runtime_state().lock() {
                    Ok(state) => match state.active_source.clone() {
                        Some(source) => (source, state.config.clone()),
                        None => break,
                    },
                    Err(_) => break,
                };

                let loop_started_at = now_ms();
                match platform::capture_take_frame(&source, &config) {
                    Ok(frame) => {
                        let capture_ts = now_ms();
                        let capture_elapsed_ms = capture_ts.saturating_sub(loop_started_at);
                        let interval_ms = capture_frame_interval(&config).as_millis() as u64;
                        let capture_gap_ms = if previous_capture_ts > 0 {
                            capture_ts.saturating_sub(previous_capture_ts)
                        } else {
                            0
                        };
                        let cached_frame = CaptureFrameSnapshot {
                            encoded_bytes: Arc::from(frame.encoded_bytes.into_boxed_slice()),
                            mime_type: frame.mime_type,
                            frame_width: frame.width,
                            frame_height: frame.height,
                            capture_ts,
                        };

                        if let Ok(mut worker_state) = capture_frame_worker_state().lock() {
                            worker_state.latest_frame = Some(cached_frame);
                        }
                        if let Ok(mut state) = capture_runtime_state().lock() {
                            clear_error(&mut state);
                            state.lifecycle = CaptureLifecycleState::Running;
                            state.last_transition_at = capture_ts;
                        }
                        sampled_frame_count = sampled_frame_count.saturating_add(1);
                        capture_elapsed_sum_ms =
                            capture_elapsed_sum_ms.saturating_add(capture_elapsed_ms);
                        capture_elapsed_max_ms = capture_elapsed_max_ms.max(capture_elapsed_ms);
                        if capture_elapsed_ms >= interval_ms {
                            capture_loop_overrun_count =
                                capture_loop_overrun_count.saturating_add(1);
                        }
                        previous_capture_ts = capture_ts;
                        if sampled_frame_count % 30 == 0 {
                            let avg_elapsed_ms =
                                capture_elapsed_sum_ms / sampled_frame_count.max(1);
                            trace_capture(
                                "worker.frame_sampled",
                                format!(
                                    "frames={} size={}x{} ts={} avg_capture_ms={} max_capture_ms={} last_capture_ms={} target_interval_ms={} last_gap_ms={} overruns={}",
                                    sampled_frame_count,
                                    frame.width,
                                    frame.height,
                                    capture_ts,
                                    avg_elapsed_ms,
                                    capture_elapsed_max_ms,
                                    capture_elapsed_ms,
                                    interval_ms,
                                    capture_gap_ms,
                                    capture_loop_overrun_count,
                                ),
                            );
                        }
                    }
                    Err(detail) => {
                        trace_capture("worker.frame_failed", &detail);
                        if let Ok(mut state) = capture_runtime_state().lock() {
                            mark_failed(&mut state, "capture.frame.unavailable", detail);
                        }
                    }
                }

                let interval = capture_frame_interval(&config);
                let elapsed_ms = now_ms().saturating_sub(loop_started_at);
                let interval_ms = interval.as_millis() as u64;
                if elapsed_ms >= interval_ms {
                    continue;
                }
                let remaining = Duration::from_millis(interval_ms - elapsed_ms);
                if stop_rx.recv_timeout(remaining).is_ok() {
                    break;
                }
            }
        })
        .map_err(|error| format!("failed to spawn capture worker: {error}"))?;

    let mut worker_state = capture_frame_worker_state()
        .lock()
        .map_err(|_| "capture frame worker state poisoned".to_string())?;
    worker_state.latest_frame = None;
    worker_state.stop_tx = Some(stop_tx);
    worker_state.handle = Some(handle);
    Ok(())
}

fn stop_capture_worker() {
    let (stop_tx, handle) = {
        let Ok(mut worker_state) = capture_frame_worker_state().lock() else {
            return;
        };
        let stop_tx = worker_state.stop_tx.take();
        let handle = worker_state.handle.take();
        worker_state.latest_frame = None;
        (stop_tx, handle)
    };

    if let Some(tx) = stop_tx {
        let _ = tx.send(());
    }
    if let Some(handle) = handle {
        let _ = handle.join();
    }
}

fn start_capture_stream_server() -> Result<String, String> {
    {
        let mut state = capture_stream_server_state()
            .lock()
            .map_err(|_| "capture stream server state poisoned".to_string())?;
        if let Some(url) = state.endpoint_url.clone() {
            if state.handle.as_ref().is_some() {
                return Ok(url);
            }
            state.endpoint_url = None;
        }
    }

    let listener = TcpListener::bind(("127.0.0.1", 0))
        .map_err(|error| format!("failed to bind capture stream server: {error}"))?;
    listener
        .set_nonblocking(true)
        .map_err(|error| format!("failed to configure capture stream listener: {error}"))?;
    let local_addr = listener
        .local_addr()
        .map_err(|error| format!("failed to get capture stream listener address: {error}"))?;
    let token = format!("rd-{}-{}", now_ms(), local_addr.port());
    let endpoint_url = format!(
        "http://127.0.0.1:{}{}?token={}",
        local_addr.port(),
        CAPTURE_STREAM_PATH,
        token
    );

    let shutdown = Arc::new(AtomicBool::new(false));
    let shutdown_for_thread = Arc::clone(&shutdown);
    let (stop_tx, stop_rx) = mpsc::channel::<()>();
    let token_for_thread = token.clone();
    let handle = thread::Builder::new()
        .name("rd-capture-stream-server".to_string())
        .spawn(move || {
            run_capture_stream_server(listener, stop_rx, shutdown_for_thread, token_for_thread);
        })
        .map_err(|error| format!("failed to spawn capture stream server: {error}"))?;

    let mut state = capture_stream_server_state()
        .lock()
        .map_err(|_| "capture stream server state poisoned".to_string())?;
    state.endpoint_url = Some(endpoint_url.clone());
    state.stop_tx = Some(stop_tx);
    state.shutdown = Some(shutdown);
    state.handle = Some(handle);
    trace_capture("stream.server_started", &endpoint_url);
    Ok(endpoint_url)
}

fn stop_capture_stream_server() {
    let (stop_tx, handle, shutdown) = {
        let Ok(mut state) = capture_stream_server_state().lock() else {
            return;
        };
        let stop_tx = state.stop_tx.take();
        let handle = state.handle.take();
        let shutdown = state.shutdown.take();
        state.endpoint_url = None;
        (stop_tx, handle, shutdown)
    };

    if let Some(shutdown) = shutdown {
        shutdown.store(true, Ordering::Relaxed);
    }
    if let Some(tx) = stop_tx {
        let _ = tx.send(());
    }
    if let Some(handle) = handle {
        let _ = handle.join();
    }
}

fn run_capture_stream_server(
    listener: TcpListener,
    stop_rx: mpsc::Receiver<()>,
    shutdown: Arc<AtomicBool>,
    token: String,
) {
    loop {
        if shutdown.load(Ordering::Relaxed) {
            break;
        }
        if stop_rx.try_recv().is_ok() {
            break;
        }
        match listener.accept() {
            Ok((mut stream, peer_addr)) => {
                let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
                let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
                trace_capture("stream.client_accepted", format!("peer={peer_addr}"));
                if let Err(error) = handle_capture_stream_client(&mut stream, &token, &shutdown) {
                    trace_capture("stream.client_closed", error);
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50));
            }
            Err(error) => {
                trace_capture("stream.accept_failed", format!("{error}"));
                thread::sleep(Duration::from_millis(120));
            }
        }
    }
    trace_capture("stream.server_stopped", "capture stream server exited");
}

fn handle_capture_stream_client(
    stream: &mut TcpStream,
    token: &str,
    shutdown: &Arc<AtomicBool>,
) -> Result<(), String> {
    let peer = stream
        .peer_addr()
        .map(|addr| addr.to_string())
        .unwrap_or_else(|_| "-".to_string());
    let mut request_buffer = [0_u8; 2048];
    let read_len = stream
        .read(&mut request_buffer)
        .map_err(|error| format!("failed to read stream request: {error}"))?;
    if read_len == 0 {
        return Err("empty stream request".to_string());
    }
    let request_text = String::from_utf8_lossy(&request_buffer[..read_len]);
    let mut request_line = request_text
        .lines()
        .next()
        .unwrap_or_default()
        .split_whitespace();
    let method = request_line.next().unwrap_or_default();
    let request_target = request_line.next().unwrap_or_default();
    let normalized_target = normalize_stream_request_target(request_target);
    let (path, request_token) = parse_stream_request_target(&normalized_target);
    trace_capture(
        "stream.client_request",
        format!(
            "peer={peer} method={method} target={request_target} normalized={normalized_target}"
        ),
    );
    if method == "OPTIONS" {
        let response = "HTTP/1.1 204 No Content\r\n\
Access-Control-Allow-Origin: *\r\n\
Access-Control-Allow-Methods: GET, OPTIONS\r\n\
Access-Control-Allow-Headers: *\r\n\
Access-Control-Max-Age: 86400\r\n\
Connection: close\r\n\
Content-Length: 0\r\n\r\n";
        stream
            .write_all(response.as_bytes())
            .map_err(|error| format!("failed to write stream options response: {error}"))?;
        stream
            .flush()
            .map_err(|error| format!("failed to flush stream options response: {error}"))?;
        return Ok(());
    }

    let token_ok = request_token.as_deref() == Some(token);
    if method != "GET" || path != CAPTURE_STREAM_PATH || !token_ok {
        let response = "HTTP/1.1 404 Not Found\r\n\
Access-Control-Allow-Origin: *\r\n\
Access-Control-Allow-Methods: GET, OPTIONS\r\n\
Access-Control-Allow-Headers: *\r\n\
Content-Length: 0\r\n\
Connection: close\r\n\r\n";
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.flush();
        return Err(format!(
            "invalid stream request: method={method} target={request_target} path={path} token_ok={token_ok}"
        ));
    }
    trace_capture(
        "stream.client_connected",
        format!("peer={peer} path={path} token_ok={token_ok}"),
    );

    let header = format!(
        "HTTP/1.1 200 OK\r\n\
Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n\
Pragma: no-cache\r\n\
Access-Control-Allow-Origin: *\r\n\
Access-Control-Allow-Methods: GET, OPTIONS\r\n\
Access-Control-Allow-Headers: *\r\n\
Cross-Origin-Resource-Policy: cross-origin\r\n\
Connection: keep-alive\r\n\
Content-Type: multipart/x-mixed-replace; boundary={boundary}\r\n\r\n",
        boundary = CAPTURE_STREAM_BOUNDARY
    );
    stream
        .write_all(header.as_bytes())
        .map_err(|error| format!("failed to write stream response header: {error}"))?;
    stream
        .flush()
        .map_err(|error| format!("failed to flush stream response header: {error}"))?;

    let mut last_capture_ts: u64 = 0;
    let mut sent_frame_count: u64 = 0;
    loop {
        if shutdown.load(Ordering::Relaxed) {
            break;
        }
        let (active_source, config) = match capture_runtime_state().lock() {
            Ok(state) => (state.active_source.clone(), state.config.clone()),
            Err(_) => return Err("capture runtime state poisoned".to_string()),
        };
        let Some(source) = active_source else {
            break;
        };

        let mut frame_for_stream =
            latest_cached_frame().filter(|frame| frame.capture_ts != last_capture_ts);
        if frame_for_stream.is_none() && last_capture_ts == 0 {
            if let Ok(frame) = platform::capture_take_frame(&source, &config) {
                let capture_ts = now_ms();
                let bootstrapped_frame = CaptureFrameSnapshot {
                    encoded_bytes: Arc::from(frame.encoded_bytes.into_boxed_slice()),
                    mime_type: frame.mime_type,
                    frame_width: frame.width,
                    frame_height: frame.height,
                    capture_ts,
                };
                if let Ok(mut worker_state) = capture_frame_worker_state().lock() {
                    worker_state.latest_frame = Some(bootstrapped_frame.clone());
                }
                frame_for_stream = Some(bootstrapped_frame);
                trace_capture(
                    "stream.bootstrap_frame",
                    format!(
                        "peer={peer} size={}x{} ts={capture_ts}",
                        frame.width, frame.height
                    ),
                );
            }
        }

        if let Some(frame) = frame_for_stream {
            let frame_header = format!(
                "--{boundary}\r\n\
Content-Type: {mime}\r\n\
Content-Length: {len}\r\n\
X-Frame-Width: {width}\r\n\
X-Frame-Height: {height}\r\n\
X-Frame-Format: {frame_format}\r\n\
X-Capture-Ts: {capture_ts}\r\n\r\n",
                boundary = CAPTURE_STREAM_BOUNDARY,
                mime = frame.mime_type,
                len = frame.encoded_bytes.len(),
                width = frame.frame_width,
                height = frame.frame_height,
                frame_format = frame_format_for_mime(frame.mime_type),
                capture_ts = frame.capture_ts,
            );
            stream
                .write_all(frame_header.as_bytes())
                .map_err(|error| format!("failed to write stream frame header: {error}"))?;
            stream
                .write_all(frame.encoded_bytes.as_ref())
                .map_err(|error| format!("failed to write stream frame bytes: {error}"))?;
            stream
                .write_all(b"\r\n")
                .map_err(|error| format!("failed to write stream frame terminator: {error}"))?;
            stream
                .flush()
                .map_err(|error| format!("failed to flush stream frame: {error}"))?;
            last_capture_ts = frame.capture_ts;
            sent_frame_count = sent_frame_count.saturating_add(1);
            if sent_frame_count == 1 {
                trace_capture(
                    "stream.client_first_frame",
                    format!(
                        "peer={peer} size={}x{} ts={}",
                        frame.frame_width, frame.frame_height, frame.capture_ts
                    ),
                );
            } else if sent_frame_count % 120 == 0 {
                trace_capture(
                    "stream.client_frame_sampled",
                    format!(
                        "peer={peer} frames={sent_frame_count} ts={}",
                        frame.capture_ts
                    ),
                );
            }
        }

        thread::sleep(capture_frame_interval(&config));
    }

    Ok(())
}

fn normalize_stream_request_target(target: &str) -> String {
    let trimmed = target.trim();
    if trimmed.is_empty() {
        return String::new();
    }

    if let Some(scheme_offset) = trimmed.find("://") {
        let rest = &trimmed[(scheme_offset + 3)..];
        if let Some(path_offset) = rest.find('/') {
            return rest[path_offset..].to_string();
        }
        return "/".to_string();
    }

    trimmed.to_string()
}

fn parse_stream_request_target(target: &str) -> (String, Option<String>) {
    if let Some((path, query)) = target.split_once('?') {
        let token = query.split('&').find_map(|pair| {
            let mut segments = pair.splitn(2, '=');
            let key = segments.next().unwrap_or_default();
            let value = segments.next().unwrap_or_default();
            if key == "token" && !value.is_empty() {
                Some(value.to_string())
            } else {
                None
            }
        });
        return (path.to_string(), token);
    }
    (target.to_string(), None)
}

fn frame_format_for_mime(mime: &str) -> &'static str {
    if mime.eq_ignore_ascii_case("application/x-rd-raw-bgra") {
        return "BGRA";
    }
    "ENCODED"
}

fn snapshot_status(state: &CaptureRuntimeState) -> CaptureStatus {
    let capabilities = capture_get_capabilities();

    CaptureStatus {
        lifecycle: state.lifecycle.as_str().to_string(),
        backend: capabilities.backend.to_string(),
        capabilities: capabilities.clone(),
        active_source: state.active_source.clone(),
        config: state.config.clone(),
        permission: platform::capture_get_permission_state(),
        supports_frame_streaming: capabilities.supports_frame_streaming,
        supports_pause_resume: capabilities.supports_pause_resume,
        last_error_code: state.last_error_code.clone(),
        last_error_detail: state.last_error_detail.clone(),
        last_transition_at: state.last_transition_at,
    }
}

fn select_capture_source(
    sources: &[CaptureSourceSummary],
    requested_source_id: &str,
) -> Option<CaptureSourceSummary> {
    if requested_source_id.is_empty() {
        return sources
            .iter()
            .find(|source| source.is_primary)
            .cloned()
            .or_else(|| sources.first().cloned());
    }

    sources
        .iter()
        .find(|source| source.source_id == requested_source_id)
        .cloned()
}

fn mark_failed(state: &mut CaptureRuntimeState, code: &str, detail: impl Into<String>) {
    state.lifecycle = CaptureLifecycleState::Failed;
    state.last_transition_at = now_ms();
    set_error(state, code, detail);
}

fn clear_error(state: &mut CaptureRuntimeState) {
    state.last_error_code.clear();
    state.last_error_detail.clear();
}

fn set_error(state: &mut CaptureRuntimeState, code: &str, detail: impl Into<String>) {
    state.last_error_code = code.to_string();
    state.last_error_detail = detail.into();
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capture_config_patch_rejects_zero_values() {
        let mut config = CaptureConfig::default();
        let error = config
            .apply_patch(&CaptureConfigPatch {
                max_width: Some(0),
                ..CaptureConfigPatch::default()
            })
            .expect_err("zero width should be rejected");

        assert_eq!(error, "capture config max_width must be greater than 0");
    }

    #[test]
    fn capture_config_patch_updates_values() {
        let mut config = CaptureConfig::default();
        config
            .apply_patch(&CaptureConfigPatch {
                max_width: Some(1920),
                max_height: Some(1080),
                max_fps: Some(12),
                codec: Some("jpeg-frame-stream".to_string()),
                source_rect_x_ppm: Some(100_000),
                source_rect_y_ppm: Some(200_000),
                source_rect_width_ppm: Some(500_000),
                source_rect_height_ppm: Some(400_000),
            })
            .expect("config patch should apply");

        assert_eq!(config.max_width, 1920);
        assert_eq!(config.max_height, 1080);
        assert_eq!(config.max_fps, 12);
        assert_eq!(config.codec, "jpeg-frame-stream");
        assert_eq!(config.source_rect_x_ppm, 100_000);
        assert_eq!(config.source_rect_y_ppm, 200_000);
        assert_eq!(config.source_rect_width_ppm, 500_000);
        assert_eq!(config.source_rect_height_ppm, 400_000);
    }

    #[test]
    fn capture_config_patch_rejects_out_of_bounds_source_rect() {
        let mut config = CaptureConfig::default();
        let error = config
            .apply_patch(&CaptureConfigPatch {
                source_rect_x_ppm: Some(800_000),
                source_rect_width_ppm: Some(300_000),
                ..CaptureConfigPatch::default()
            })
            .expect_err("source rect outside the desktop should be rejected");

        assert_eq!(error, "capture config source rect must stay within [0,1]");
    }

    #[test]
    fn capture_config_default_uses_interactive_fps() {
        let config = CaptureConfig::default();
        assert_eq!(config.max_fps, 24);
    }

    #[test]
    fn frame_format_tracks_raw_bgra_mime() {
        assert_eq!(frame_format_for_mime("application/x-rd-raw-bgra"), "BGRA");
        assert_eq!(frame_format_for_mime("image/jpeg"), "ENCODED");
    }

    fn reset_capture_runtime_state_for_test() {
        stop_capture_worker();
        let mut state = capture_runtime_state()
            .lock()
            .expect("capture runtime state should not be poisoned");
        *state = CaptureRuntimeState::default();
    }

    #[test]
    fn select_capture_source_prefers_primary_display() {
        let sources = vec![
            CaptureSourceSummary {
                source_id: "display:2".to_string(),
                kind: CaptureSourceKind::Display,
                title: "Display 2".to_string(),
                backend: "test".to_string(),
                width: 1920,
                height: 1080,
                is_primary: false,
            },
            CaptureSourceSummary {
                source_id: "display:1".to_string(),
                kind: CaptureSourceKind::Display,
                title: "Primary Display".to_string(),
                backend: "test".to_string(),
                width: 2560,
                height: 1440,
                is_primary: true,
            },
        ];

        let selected =
            select_capture_source(&sources, "").expect("primary display should be selected");
        assert_eq!(selected.source_id, "display:1");
    }

    #[test]
    fn select_capture_source_matches_requested_source_id() {
        let sources = vec![
            CaptureSourceSummary {
                source_id: "display:2".to_string(),
                kind: CaptureSourceKind::Display,
                title: "Display 2".to_string(),
                backend: "test".to_string(),
                width: 1920,
                height: 1080,
                is_primary: false,
            },
            CaptureSourceSummary {
                source_id: "display:1".to_string(),
                kind: CaptureSourceKind::Display,
                title: "Primary Display".to_string(),
                backend: "test".to_string(),
                width: 2560,
                height: 1440,
                is_primary: true,
            },
        ];

        let selected = select_capture_source(&sources, "display:2")
            .expect("requested display should be selected");
        assert_eq!(selected.source_id, "display:2");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn capture_list_sources_returns_empty_when_permission_is_not_granted() {
        reset_capture_runtime_state_for_test();

        let sources =
            capture_list_sources().expect("permission-gated source listing should not error");

        assert!(sources.is_empty());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn capture_start_returns_permission_unavailable_before_source_lookup() {
        reset_capture_runtime_state_for_test();

        let status = capture_start(None).expect("capture start should return status");

        assert_eq!(status.lifecycle, "failed");
        assert_eq!(status.last_error_code, "capture.permission.unavailable");
        assert!(status.active_source.is_none());
    }

    #[test]
    fn capture_start_clears_previous_active_source_on_failure() {
        reset_capture_runtime_state_for_test();
        {
            let mut state = capture_runtime_state()
                .lock()
                .expect("capture runtime state should not be poisoned");
            state.active_source = Some(CaptureSourceSummary {
                source_id: "display:99".to_string(),
                kind: CaptureSourceKind::Display,
                title: "Stale Display".to_string(),
                backend: "test".to_string(),
                width: 1920,
                height: 1080,
                is_primary: false,
            });
        }

        let status = capture_start(Some(CaptureStartRequest {
            source_id: "display:missing".to_string(),
            config: None,
        }))
        .expect("capture start should return status");

        assert!(status.active_source.is_none());
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn capture_list_sources_returns_virtual_desktop_source_on_windows() {
        reset_capture_runtime_state_for_test();

        let sources = capture_list_sources().expect("Windows source listing should succeed");

        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].source_id, "display:virtual_desktop");
        assert_eq!(sources[0].kind, CaptureSourceKind::Display);
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    #[test]
    fn capture_list_sources_returns_platform_error_when_permission_checks_are_unsupported() {
        reset_capture_runtime_state_for_test();

        let error = capture_list_sources()
            .expect_err("unsupported platforms should surface their platform error");
        assert!(!error.is_empty());
    }
}
