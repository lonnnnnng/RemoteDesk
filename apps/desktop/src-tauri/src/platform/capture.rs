use super::{current_platform, DesktopPlatform};
use crate::capture::{
    CaptureConfig, CapturePermissionState, CaptureSourceKind, CaptureSourceSummary,
};

#[cfg(all(target_os = "macos", not(test)))]
use objc2_core_graphics::{
    CGDataProvider, CGDirectDisplayID, CGDisplayCreateImage, CGDisplayPixelsHigh,
    CGDisplayPixelsWide, CGError, CGGetOnlineDisplayList, CGImage, CGImageAlphaInfo,
    CGImageByteOrderInfo, CGMainDisplayID,
};
#[cfg(all(target_os = "macos", not(test)))]
use screencapturekit::{
    cg::CGRect,
    cm::{CMSampleBuffer, SCFrameStatus},
    cv::CVPixelBufferLockFlags,
    shareable_content::{SCDisplay, SCShareableContent},
    stream::{
        configuration::{PixelFormat, SCStreamConfiguration},
        content_filter::SCContentFilter,
        output_type::SCStreamOutputType,
        SCStream,
    },
};
#[cfg(all(target_os = "windows", not(test)))]
use std::mem::size_of;
#[cfg(all(target_os = "macos", not(test)))]
use std::sync::{Arc, Condvar, Mutex, OnceLock};
#[cfg(all(target_os = "macos", not(test)))]
use std::time::{Duration, Instant};
#[cfg(all(target_os = "windows", not(test)))]
use windows::Win32::Graphics::Gdi::{
    BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject, GetDC, GetDIBits,
    ReleaseDC, SelectObject, StretchBlt, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, CAPTUREBLT,
    DIB_RGB_COLORS, HGDIOBJ, SRCCOPY,
};
#[cfg(all(target_os = "windows", not(test)))]
use windows::Win32::UI::WindowsAndMessaging::{
    GetSystemMetrics, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN,
};

const SCREEN_RECORDING_CAPABILITY: &str = "screen_recording";
const WINDOWS_VIRTUAL_DESKTOP_SOURCE_ID: &str = "display:virtual_desktop";
const PNG_FRAME_CODEC: &str = "png-frame-stream";
const JPEG_FRAME_CODEC: &str = "jpeg-frame-stream";
const RAW_BGRA_FRAME_CODEC: &str = "raw-bgra-frame-stream";
const JPEG_MIME_TYPE: &str = "image/jpeg";
const PNG_MIME_TYPE: &str = "image/png";
const RAW_BGRA_MIME_TYPE: &str = "application/x-rd-raw-bgra";
const JPEG_QUALITY_INTERACTIVE: u8 = 46;
const JPEG_QUALITY_FULLSCREEN: u8 = 54;
const JPEG_QUALITY_DETAIL_BALANCED: u8 = 80;
const JPEG_QUALITY_DETAIL_SHARP: u8 = 84;
const SOURCE_RECT_UNITS: u32 = 1_000_000;
const CROPPED_REGION_MAX_UPSCALE: f64 = 1.35;
#[cfg(all(target_os = "macos", not(test)))]
const MACOS_STREAM_QUEUE_DEPTH: u32 = 3;
#[cfg(all(target_os = "macos", not(test)))]
const MACOS_FORCE_CORE_GRAPHICS_ENV: &str = "RD_CAPTURE_FORCE_COREGRAPHICS";

#[derive(Debug, Clone)]
pub struct CaptureFrameData {
    pub encoded_bytes: Vec<u8>,
    pub mime_type: &'static str,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct CapturePixelRegion {
    x: u32,
    y: u32,
    width: u32,
    height: u32,
    cropped: bool,
}

#[cfg(all(target_os = "macos", not(test)))]
struct MacosStreamRuntime {
    source_id: String,
    target_width: u32,
    target_height: u32,
    target_fps: u16,
    source_region: CapturePixelRegion,
    stream: SCStream,
    frame_buffer: Arc<LatestFrameBuffer>,
    last_frame_seq: u64,
}

#[cfg(all(target_os = "macos", not(test)))]
#[derive(Debug, Clone, Copy)]
struct MacosCoreGraphicsDisplayInfo {
    display_id: CGDirectDisplayID,
    width: u32,
    height: u32,
}

#[cfg(all(target_os = "macos", not(test)))]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MacosCoreGraphicsPixelLayout {
    Bgra,
    Argb,
    Rgba,
    Abgr,
}

#[cfg(all(target_os = "macos", not(test)))]
impl Drop for MacosStreamRuntime {
    fn drop(&mut self) {
        let _ = self.stream.stop_capture();
    }
}

#[cfg(all(target_os = "macos", not(test)))]
#[derive(Default)]
struct LatestFrameState {
    seq: u64,
    frame: Option<CMSampleBuffer>,
}

#[cfg(all(target_os = "macos", not(test)))]
#[derive(Default)]
struct LatestFrameBuffer {
    state: Mutex<LatestFrameState>,
    condvar: Condvar,
}

#[cfg(all(target_os = "macos", not(test)))]
impl LatestFrameBuffer {
    fn push(&self, sample: CMSampleBuffer) {
        if let Ok(mut state) = self.state.lock() {
            state.seq = state.seq.saturating_add(1);
            state.frame = Some(sample);
            self.condvar.notify_one();
        }
    }

    fn wait_for_new(
        &self,
        last_seq: u64,
        timeout: Duration,
    ) -> Result<(u64, CMSampleBuffer), String> {
        let deadline = Instant::now() + timeout;
        let mut state = self
            .state
            .lock()
            .map_err(|_| "macOS latest frame buffer lock poisoned".to_string())?;
        loop {
            if state.seq > last_seq {
                if let Some(frame) = state.frame.take() {
                    return Ok((state.seq, frame));
                }
            }

            let now = Instant::now();
            if now >= deadline {
                return Err(format!(
                    "timed out after {}ms waiting for macOS stream sample",
                    timeout.as_millis()
                ));
            }
            let wait_for = deadline.saturating_duration_since(now);
            let (next_state, wait_result) = self
                .condvar
                .wait_timeout(state, wait_for)
                .map_err(|_| "macOS latest frame buffer wait poisoned".to_string())?;
            state = next_state;
            if wait_result.timed_out() {
                continue;
            }
        }
    }
}

#[cfg(all(target_os = "macos", not(test)))]
static MACOS_STREAM_RUNTIME: OnceLock<Mutex<Option<MacosStreamRuntime>>> = OnceLock::new();

pub fn capture_backend_name() -> &'static str {
    match current_platform() {
        DesktopPlatform::Macos => "macos.screen_capture",
        DesktopPlatform::Windows => {
            if cfg!(test) {
                "windows.test_stub"
            } else {
                "windows.gdi"
            }
        }
        DesktopPlatform::Unsupported => "unsupported",
    }
}

pub fn capture_supports_permission_check() -> bool {
    matches!(current_platform(), DesktopPlatform::Macos)
}

pub fn capture_supports_permission_request() -> bool {
    matches!(current_platform(), DesktopPlatform::Macos)
}

pub fn capture_supports_source_listing() -> bool {
    matches!(
        current_platform(),
        DesktopPlatform::Macos | DesktopPlatform::Windows
    )
}

pub fn capture_supports_frame_streaming() -> bool {
    matches!(
        current_platform(),
        DesktopPlatform::Macos | DesktopPlatform::Windows
    )
}

pub fn capture_supports_pause_resume() -> bool {
    false
}

pub fn capture_supported_source_kinds() -> Vec<CaptureSourceKind> {
    match current_platform() {
        DesktopPlatform::Macos | DesktopPlatform::Windows => vec![CaptureSourceKind::Display],
        DesktopPlatform::Unsupported => Vec::new(),
    }
}

pub fn capture_get_permission_state() -> CapturePermissionState {
    match current_platform() {
        DesktopPlatform::Macos => macos_capture_permission_state(),
        DesktopPlatform::Windows => windows_capture_permission_state(),
        DesktopPlatform::Unsupported => CapturePermissionState {
            capability: SCREEN_RECORDING_CAPABILITY,
            status: "unsupported",
            can_request: false,
            detail: "screen capture is not supported on this platform".to_string(),
        },
    }
}

pub fn capture_request_permission() -> Result<CapturePermissionState, String> {
    match current_platform() {
        DesktopPlatform::Macos => macos_capture_request_permission(),
        DesktopPlatform::Windows => Ok(capture_get_permission_state()),
        DesktopPlatform::Unsupported => Ok(capture_get_permission_state()),
    }
}

pub fn capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    match current_platform() {
        DesktopPlatform::Macos => macos_capture_list_sources(),
        DesktopPlatform::Windows => windows_capture_list_sources(),
        DesktopPlatform::Unsupported => {
            Err("screen capture is not supported on this platform".to_string())
        }
    }
}

pub fn capture_take_frame(
    source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    match current_platform() {
        DesktopPlatform::Macos => macos_capture_take_frame(source, config),
        DesktopPlatform::Windows => windows_capture_take_frame(source, config),
        DesktopPlatform::Unsupported => {
            Err("screen capture is not supported on this platform".to_string())
        }
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_permission_state() -> CapturePermissionState {
    let granted = unsafe { CGPreflightScreenCaptureAccess() };
    CapturePermissionState {
        capability: SCREEN_RECORDING_CAPABILITY,
        status: if granted { "granted" } else { "denied" },
        can_request: !granted,
        detail: if granted {
            "macOS screen recording permission is granted".to_string()
        } else {
            "macOS screen capture requires Screen Recording permission for this app".to_string()
        },
    }
}

#[cfg(all(target_os = "macos", test))]
fn macos_capture_permission_state() -> CapturePermissionState {
    CapturePermissionState {
        capability: SCREEN_RECORDING_CAPABILITY,
        status: "denied",
        can_request: false,
        detail: "macOS test build does not query screen recording permission".to_string(),
    }
}

#[cfg(not(target_os = "macos"))]
fn macos_capture_permission_state() -> CapturePermissionState {
    CapturePermissionState {
        capability: SCREEN_RECORDING_CAPABILITY,
        status: "unsupported",
        can_request: false,
        detail: "macOS screen capture backend is unavailable on this build target".to_string(),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_request_permission() -> Result<CapturePermissionState, String> {
    unsafe {
        CGRequestScreenCaptureAccess();
    }
    Ok(macos_capture_permission_state())
}

#[cfg(all(target_os = "macos", test))]
fn macos_capture_request_permission() -> Result<CapturePermissionState, String> {
    Ok(macos_capture_permission_state())
}

#[cfg(not(target_os = "macos"))]
fn macos_capture_request_permission() -> Result<CapturePermissionState, String> {
    Ok(macos_capture_permission_state())
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    let primary_display_id = primary_display_id();
    if !macos_force_core_graphics() {
        if let Ok(displays) = shareable_displays() {
            return Ok(displays
                .into_iter()
                .enumerate()
                .map(|(index, display)| {
                    let display_id = display.display_id();
                    let is_primary = display_id == primary_display_id;

                    CaptureSourceSummary {
                        source_id: format!("display:{display_id}"),
                        kind: CaptureSourceKind::Display,
                        title: if is_primary {
                            format!("Primary Display {}", index + 1)
                        } else {
                            format!("Display {}", index + 1)
                        },
                        backend: capture_backend_name().to_string(),
                        width: display.width().max(1),
                        height: display.height().max(1),
                        is_primary,
                    }
                })
                .collect());
        }
    }

    let displays = core_graphics_display_infos()?;
    Ok(displays
        .into_iter()
        .enumerate()
        .map(|(index, info)| {
            let display_id = info.display_id;
            let is_primary = display_id == primary_display_id;

            CaptureSourceSummary {
                source_id: format!("display:{display_id}"),
                kind: CaptureSourceKind::Display,
                title: if is_primary {
                    format!("Primary Display {}", index + 1)
                } else {
                    format!("Display {}", index + 1)
                },
                backend: capture_backend_name().to_string(),
                width: info.width.max(1),
                height: info.height.max(1),
                is_primary,
            }
        })
        .collect())
}

#[cfg(all(target_os = "macos", test))]
fn macos_capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    Ok(vec![CaptureSourceSummary {
        source_id: "display:1".to_string(),
        kind: CaptureSourceKind::Display,
        title: "Primary Display".to_string(),
        backend: "macos.test_stub".to_string(),
        width: 1728,
        height: 1117,
        is_primary: true,
    }])
}

#[cfg(not(target_os = "macos"))]
fn macos_capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    Err("macOS screen capture backend is unavailable on this build target".to_string())
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_take_frame(
    source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    match macos_capture_take_frame_via_stream(source, config) {
        Ok(frame) => Ok(frame),
        Err(stream_error) => macos_capture_take_frame_via_screenshot(source, config)
            .map_err(|screenshot_error| {
                format!(
                    "macOS stream capture failed: {stream_error}; screenshot fallback failed: {screenshot_error}"
                )
            }),
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_take_frame_via_stream(
    source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    if macos_force_core_graphics() {
        return Err(format!(
            "macOS stream capture skipped because {} is enabled",
            MACOS_FORCE_CORE_GRAPHICS_ENV
        ));
    }
    let display_id = parse_display_source_id(&source.source_id)?;
    let display = shareable_display_for_id(display_id)?;
    let source_width = display.width().max(1);
    let source_height = display.height().max(1);
    let region = capture_pixel_region(source_width, source_height, config);
    let (target_width, target_height) = if region.cropped {
        // 作者: long；手机双指放大后只需要当前可视区域，直接让 ScreenCaptureKit 裁剪源区域，避免 Rust 每帧处理整张 Retina 屏再裁剪导致局部高清降到个位数 FPS。
        fit_cropped_capture_size(region.width, region.height, config)
    } else {
        fit_capture_size(source_width, source_height, config)
    };
    let sample = {
        let mut runtime_guard = macos_stream_runtime()
            .lock()
            .map_err(|_| "macOS stream runtime state poisoned".to_string())?;
        let should_restart = runtime_guard.as_ref().is_none_or(|runtime| {
            runtime.source_id != source.source_id
                || runtime.target_width != target_width
                || runtime.target_height != target_height
                || runtime.target_fps != config.max_fps
                || runtime.source_region != region
        });
        if should_restart {
            *runtime_guard = Some(macos_start_stream_runtime(
                source,
                &display,
                target_width,
                target_height,
                config.max_fps,
                region,
            )?);
        }
        let runtime = runtime_guard
            .as_mut()
            .ok_or_else(|| "macOS stream runtime is not initialized".to_string())?;
        macos_take_latest_sample(runtime)?
    };
    macos_stream_sample_to_frame(&sample, &source.source_id, config, region)
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_take_frame_via_screenshot(
    source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    let display_id = parse_display_source_id(&source.source_id)?;
    if macos_force_core_graphics() {
        return macos_capture_take_frame_via_core_graphics(source, config);
    }
    if let Ok(display) = shareable_display_for_id(display_id) {
        let source_width = display.width().max(1);
        let source_height = display.height().max(1);
        let region = capture_pixel_region(source_width, source_height, config);
        let (target_width, target_height) =
            fit_region_capture_size(source_width, source_height, config);
        let filter = SCContentFilter::create()
            .with_display(&display)
            .with_excluding_windows(&[])
            .build();
        let mut stream_config = SCStreamConfiguration::new()
            .with_width(target_width)
            .with_height(target_height);
        if region.cropped {
            // 作者: long；截图兜底同样按手机当前可视区域截取，主链路失败时也不能退回整屏缩放导致局部输入框看不清。
            stream_config = stream_config
                .with_source_rect(capture_region_to_cg_rect(region))
                .with_scales_to_fit(true);
        }
        let image = screencapturekit::screenshot_manager::SCScreenshotManager::capture_image(
            &filter,
            &stream_config,
        )
        .map_err(|error| {
            format!(
                "failed to capture macOS display image for {}: {error}",
                source.source_id
            )
        })?;
        let frame_width = image.width() as u32;
        let frame_height = image.height() as u32;
        if frame_width == 0 || frame_height == 0 {
            return Err(format!(
                "captured macOS display image has invalid size {}x{}",
                frame_width, frame_height
            ));
        }

        let rgba_bytes = image.rgba_data().map_err(|error| {
            format!(
                "failed to extract RGBA bytes for {}: {error}",
                source.source_id
            )
        })?;
        let expected_len = frame_width as usize * frame_height as usize * 4;
        if rgba_bytes.len() != expected_len {
            return Err(format!(
                "captured macOS display image for {} returned {} RGBA bytes, expected {}",
                source.source_id,
                rgba_bytes.len(),
                expected_len
            ));
        }
        let bgra_bytes = rgba_to_bgra_bytes(&rgba_bytes)?;
        let (bgra_bytes, frame_width, frame_height) = if region.cropped {
            (bgra_bytes, frame_width, frame_height)
        } else {
            crop_and_resize_bgra_for_config(bgra_bytes, frame_width, frame_height, config)?
        };
        let (encoded_bytes, mime_type) =
            encode_owned_bgra(bgra_bytes, frame_width, frame_height, config)?;
        return Ok(CaptureFrameData {
            encoded_bytes,
            mime_type,
            width: frame_width,
            height: frame_height,
        });
    }

    macos_capture_take_frame_via_core_graphics(source, config)
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_stream_runtime() -> &'static Mutex<Option<MacosStreamRuntime>> {
    MACOS_STREAM_RUNTIME.get_or_init(|| Mutex::new(None))
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_start_stream_runtime(
    source: &CaptureSourceSummary,
    display: &SCDisplay,
    target_width: u32,
    target_height: u32,
    target_fps: u16,
    source_region: CapturePixelRegion,
) -> Result<MacosStreamRuntime, String> {
    let filter = SCContentFilter::create()
        .with_display(display)
        .with_excluding_windows(&[])
        .build();
    let mut config = SCStreamConfiguration::new()
        .with_width(target_width)
        .with_height(target_height)
        .with_fps(u32::from(target_fps.max(1)))
        .with_queue_depth(MACOS_STREAM_QUEUE_DEPTH)
        .with_pixel_format(PixelFormat::BGRA)
        .with_shows_cursor(true);
    if source_region.cropped {
        // 作者: long；Android 局部放大请求的是完整桌面中的可视区域，sourceRect 让 macOS 在采集层完成裁剪和缩放，避免后端先拿整屏再裁剪造成缩放后既卡又糊。
        config = config
            .with_source_rect(capture_region_to_cg_rect(source_region))
            .with_scales_to_fit(true);
    }

    let frame_buffer = Arc::new(LatestFrameBuffer::default());
    let frame_buffer_for_handler = Arc::clone(&frame_buffer);
    let mut stream = SCStream::new(&filter, &config);
    let attached = stream.add_output_handler(
        move |sample: CMSampleBuffer, output_type: SCStreamOutputType| {
            if output_type != SCStreamOutputType::Screen {
                return;
            }
            frame_buffer_for_handler.push(sample);
        },
        SCStreamOutputType::Screen,
    );
    if attached.is_none() {
        return Err("failed to attach macOS stream output handler".to_string());
    }
    stream.start_capture().map_err(|error| {
        format!(
            "failed to start macOS stream capture for {}: {error}",
            source.source_id
        )
    })?;

    Ok(MacosStreamRuntime {
        source_id: source.source_id.clone(),
        target_width,
        target_height,
        target_fps,
        source_region,
        stream,
        frame_buffer,
        last_frame_seq: 0,
    })
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_take_latest_sample(runtime: &mut MacosStreamRuntime) -> Result<CMSampleBuffer, String> {
    let timeout_ms = capture_frame_timeout_ms(runtime.target_fps);
    let (seq, sample) = runtime
        .frame_buffer
        .wait_for_new(runtime.last_frame_seq, Duration::from_millis(timeout_ms))?;
    runtime.last_frame_seq = seq;
    Ok(sample)
}

#[cfg(all(target_os = "macos", not(test)))]
fn capture_frame_timeout_ms(target_fps: u16) -> u64 {
    let fps = u64::from(target_fps.max(1));
    ((1000 / fps) * 4).clamp(300, 2000)
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_stream_sample_to_frame(
    sample: &CMSampleBuffer,
    source_id: &str,
    config: &CaptureConfig,
    source_region: CapturePixelRegion,
) -> Result<CaptureFrameData, String> {
    if let Some(frame_status) = sample.frame_status() {
        if frame_status != SCFrameStatus::Complete {
            return Err(format!(
                "captured macOS stream sample for {source_id} is not complete: {frame_status}"
            ));
        }
    }

    let pixel_buffer = sample.image_buffer().ok_or_else(|| {
        format!("captured macOS stream sample for {source_id} has no image buffer")
    })?;
    let lock_guard = pixel_buffer
        .lock(CVPixelBufferLockFlags::READ_ONLY)
        .map_err(|error| {
            format!(
                "failed to lock macOS stream pixel buffer for {}: {}",
                source_id, error
            )
        })?;

    let frame_width = lock_guard.width() as u32;
    let frame_height = lock_guard.height() as u32;
    if frame_width == 0 || frame_height == 0 {
        return Err(format!(
            "captured macOS stream sample for {} has invalid frame size {}x{}",
            source_id, frame_width, frame_height
        ));
    }

    let bytes_per_row = lock_guard.bytes_per_row();
    let row_len = frame_width as usize * 4;
    if bytes_per_row < row_len {
        return Err(format!(
            "captured macOS stream sample for {} has invalid bytes_per_row {} for width {}",
            source_id, bytes_per_row, frame_width
        ));
    }

    let raw_bytes = lock_guard.as_slice();
    let expected_total = bytes_per_row * frame_height as usize;
    if raw_bytes.len() < expected_total {
        return Err(format!(
            "captured macOS stream sample for {} returned {} bytes, expected at least {}",
            source_id,
            raw_bytes.len(),
            expected_total
        ));
    }

    if source_region.cropped {
        if bytes_per_row == row_len {
            let tight_bgra = &raw_bytes[..row_len * frame_height as usize];
            // 作者: long；ScreenCaptureKit 配了 sourceRect 后已经把手机当前视口裁剪并缩放到目标尺寸；行数据紧凑时直接编码，避免局部高清每帧再复制一张 960 宽位图拖慢缩放停手后的清晰补帧。
            let (encoded_bytes, mime_type) =
                encode_frame_bgra(tight_bgra, frame_width, frame_height, config)?;
            return Ok(CaptureFrameData {
                encoded_bytes,
                mime_type,
                width: frame_width,
                height: frame_height,
            });
        }
        let bgra_bytes =
            compact_strided_bgra_frame(raw_bytes, frame_width, frame_height, bytes_per_row)?;
        let (encoded_bytes, mime_type) =
            encode_owned_bgra(bgra_bytes, frame_width, frame_height, config)?;
        return Ok(CaptureFrameData {
            encoded_bytes,
            mime_type,
            width: frame_width,
            height: frame_height,
        });
    }

    if bytes_per_row == row_len {
        let tight_bgra = &raw_bytes[..row_len * frame_height as usize];
        // 作者: long；全屏真机基础档是当前最热路径，ScreenCaptureKit 已经按目标尺寸产出紧凑 BGRA；直接借用像素切片编码，避免每帧再复制一张 800x517 或 640x416 的中间帧。
        let (encoded_bytes, mime_type) =
            encode_frame_bgra(tight_bgra, frame_width, frame_height, config)?;
        return Ok(CaptureFrameData {
            encoded_bytes,
            mime_type,
            width: frame_width,
            height: frame_height,
        });
    }

    let (bgra_bytes, frame_width, frame_height) = crop_and_resize_strided_bgra_for_config(
        raw_bytes,
        frame_width,
        frame_height,
        bytes_per_row,
        config,
    )?;
    let (encoded_bytes, mime_type) =
        encode_owned_bgra(bgra_bytes, frame_width, frame_height, config)?;
    Ok(CaptureFrameData {
        encoded_bytes,
        mime_type,
        width: frame_width,
        height: frame_height,
    })
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_capture_take_frame_via_core_graphics(
    source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    let display_id = parse_display_source_id(&source.source_id)?;
    let _display = core_graphics_display_info_for_id(display_id)?;
    let image = CGDisplayCreateImage(display_id).ok_or_else(|| {
        format!(
            "failed to capture macOS display image via CoreGraphics for {}",
            source.source_id
        )
    })?;
    let (bgra_bytes, source_width, source_height) =
        core_graphics_image_to_bgra_bytes(&image, &source.source_id)?;
    let (bgra_bytes, target_width, target_height) =
        crop_and_resize_bgra_for_config(bgra_bytes, source_width, source_height, config)?;
    let (encoded_bytes, mime_type) =
        encode_owned_bgra(bgra_bytes, target_width, target_height, config)?;
    Ok(CaptureFrameData {
        encoded_bytes,
        mime_type,
        width: target_width,
        height: target_height,
    })
}

#[cfg(all(target_os = "macos", not(test)))]
fn core_graphics_image_to_bgra_bytes(
    image: &CGImage,
    source_id: &str,
) -> Result<(Vec<u8>, u32, u32), String> {
    let width = CGImage::width(Some(image)) as u32;
    let height = CGImage::height(Some(image)) as u32;
    if width == 0 || height == 0 {
        return Err(format!(
            "captured CoreGraphics image for {} has invalid size {}x{}",
            source_id, width, height
        ));
    }

    let bits_per_component = CGImage::bits_per_component(Some(image));
    let bits_per_pixel = CGImage::bits_per_pixel(Some(image));
    if bits_per_component != 8 || bits_per_pixel != 32 {
        return Err(format!(
            "captured CoreGraphics image for {} has unsupported pixel format (bits_per_component={}, bits_per_pixel={})",
            source_id, bits_per_component, bits_per_pixel
        ));
    }

    let bytes_per_row = CGImage::bytes_per_row(Some(image));
    let row_len = width as usize * 4;
    if bytes_per_row < row_len {
        return Err(format!(
            "captured CoreGraphics image for {} has invalid bytes_per_row {} for width {}",
            source_id, bytes_per_row, width
        ));
    }

    let provider = CGImage::data_provider(Some(image)).ok_or_else(|| {
        format!(
            "captured CoreGraphics image for {} has no data provider",
            source_id
        )
    })?;
    let data = CGDataProvider::data(Some(&provider)).ok_or_else(|| {
        format!(
            "captured CoreGraphics image for {} has no readable pixel data",
            source_id
        )
    })?;
    let raw_bytes = data.to_vec();
    let expected_total = bytes_per_row * height as usize;
    if raw_bytes.len() < expected_total {
        return Err(format!(
            "captured CoreGraphics image for {} returned {} bytes, expected at least {}",
            source_id,
            raw_bytes.len(),
            expected_total
        ));
    }

    let alpha_info = CGImage::alpha_info(Some(image));
    let byte_order = CGImage::byte_order_info(Some(image));
    let layout = core_graphics_pixel_layout(byte_order, alpha_info).ok_or_else(|| {
        format!(
            "captured CoreGraphics image for {} uses unsupported byte order {:?} and alpha {:?}",
            source_id, byte_order, alpha_info
        )
    })?;
    let has_explicit_alpha = !matches!(
        alpha_info,
        CGImageAlphaInfo::None | CGImageAlphaInfo::NoneSkipFirst | CGImageAlphaInfo::NoneSkipLast
    );

    let mut bgra_bytes = vec![0_u8; width as usize * height as usize * 4];
    if layout == MacosCoreGraphicsPixelLayout::Bgra {
        for row in 0..height as usize {
            let src_offset = row * bytes_per_row;
            let dst_offset = row * row_len;
            let src_row = &raw_bytes[src_offset..src_offset + row_len];
            let dst_row = &mut bgra_bytes[dst_offset..dst_offset + row_len];
            dst_row.copy_from_slice(src_row);
        }
        if !has_explicit_alpha {
            for pixel in bgra_bytes.chunks_exact_mut(4) {
                pixel[3] = 0xff;
            }
        }
        return Ok((bgra_bytes, width, height));
    }
    for row in 0..height as usize {
        let src_offset = row * bytes_per_row;
        let dst_offset = row * row_len;
        let src_row = &raw_bytes[src_offset..src_offset + row_len];
        let dst_row = &mut bgra_bytes[dst_offset..dst_offset + row_len];
        for (src_pixel, dst_pixel) in src_row.chunks_exact(4).zip(dst_row.chunks_exact_mut(4)) {
            let (r, g, b, a) = match layout {
                MacosCoreGraphicsPixelLayout::Bgra => {
                    (src_pixel[2], src_pixel[1], src_pixel[0], src_pixel[3])
                }
                MacosCoreGraphicsPixelLayout::Argb => {
                    (src_pixel[1], src_pixel[2], src_pixel[3], src_pixel[0])
                }
                MacosCoreGraphicsPixelLayout::Rgba => {
                    (src_pixel[0], src_pixel[1], src_pixel[2], src_pixel[3])
                }
                MacosCoreGraphicsPixelLayout::Abgr => {
                    (src_pixel[3], src_pixel[2], src_pixel[1], src_pixel[0])
                }
            };
            dst_pixel[0] = b;
            dst_pixel[1] = g;
            dst_pixel[2] = r;
            dst_pixel[3] = if has_explicit_alpha { a } else { 0xff };
        }
    }

    Ok((bgra_bytes, width, height))
}

#[cfg(all(target_os = "macos", not(test)))]
fn core_graphics_pixel_layout(
    byte_order: CGImageByteOrderInfo,
    alpha_info: CGImageAlphaInfo,
) -> Option<MacosCoreGraphicsPixelLayout> {
    let alpha_is_first = matches!(
        alpha_info,
        CGImageAlphaInfo::First
            | CGImageAlphaInfo::PremultipliedFirst
            | CGImageAlphaInfo::NoneSkipFirst
    );
    let alpha_is_last = matches!(
        alpha_info,
        CGImageAlphaInfo::Last
            | CGImageAlphaInfo::PremultipliedLast
            | CGImageAlphaInfo::NoneSkipLast
    );

    if byte_order == CGImageByteOrderInfo::Order32Little
        || byte_order == CGImageByteOrderInfo::OrderDefault
    {
        if alpha_is_first || alpha_info == CGImageAlphaInfo::None {
            return Some(MacosCoreGraphicsPixelLayout::Bgra);
        }
        if alpha_is_last {
            return Some(MacosCoreGraphicsPixelLayout::Abgr);
        }
    }

    if byte_order == CGImageByteOrderInfo::Order32Big {
        if alpha_is_first {
            return Some(MacosCoreGraphicsPixelLayout::Argb);
        }
        if alpha_is_last || alpha_info == CGImageAlphaInfo::None {
            return Some(MacosCoreGraphicsPixelLayout::Rgba);
        }
    }

    None
}

#[cfg(all(target_os = "macos", not(test)))]
fn core_graphics_display_infos() -> Result<Vec<MacosCoreGraphicsDisplayInfo>, String> {
    const MAX_DISPLAYS: usize = 32;
    let mut display_ids = [0_u32; MAX_DISPLAYS];
    let mut display_count = 0_u32;
    let error = unsafe {
        CGGetOnlineDisplayList(
            MAX_DISPLAYS as u32,
            display_ids.as_mut_ptr(),
            &mut display_count as *mut u32,
        )
    };
    if error != CGError::Success {
        return Err(format!(
            "failed to list macOS displays via CoreGraphics: {:?}",
            error
        ));
    }
    if display_count == 0 {
        return Err("CoreGraphics returned zero online displays".to_string());
    }

    let mut displays = Vec::with_capacity(display_count as usize);
    for display_id in display_ids.iter().copied().take(display_count as usize) {
        let width = CGDisplayPixelsWide(display_id) as u32;
        let height = CGDisplayPixelsHigh(display_id) as u32;
        if width == 0 || height == 0 {
            continue;
        }
        displays.push(MacosCoreGraphicsDisplayInfo {
            display_id,
            width,
            height,
        });
    }
    if displays.is_empty() {
        return Err(
            "CoreGraphics online display list contained no display with valid dimensions"
                .to_string(),
        );
    }
    Ok(displays)
}

#[cfg(all(target_os = "macos", not(test)))]
fn core_graphics_display_info_for_id(
    display_id: CGDirectDisplayID,
) -> Result<MacosCoreGraphicsDisplayInfo, String> {
    core_graphics_display_infos()?
        .into_iter()
        .find(|display| display.display_id == display_id)
        .ok_or_else(|| format!("macOS CoreGraphics display {display_id} was not found"))
}

fn resize_bgra_bilinear(
    bgra_bytes: &[u8],
    source_width: u32,
    source_height: u32,
    target_width: u32,
    target_height: u32,
) -> Result<Vec<u8>, String> {
    if source_width == 0 || source_height == 0 {
        return Err(format!(
            "invalid BGRA resize source {}x{}",
            source_width, source_height
        ));
    }
    if target_width == 0 || target_height == 0 {
        return Err(format!(
            "invalid BGRA resize target {}x{}",
            target_width, target_height
        ));
    }

    let expected_len = source_width as usize * source_height as usize * 4;
    if bgra_bytes.len() != expected_len {
        return Err(format!(
            "invalid BGRA buffer size {}, expected {} for {}x{}",
            bgra_bytes.len(),
            expected_len,
            source_width,
            source_height
        ));
    }

    if source_width == target_width && source_height == target_height {
        return Ok(bgra_bytes.to_vec());
    }

    let mut resized = vec![0_u8; target_width as usize * target_height as usize * 4];
    let scale_x = source_width as f64 / target_width as f64;
    let scale_y = source_height as f64 / target_height as f64;
    for target_y in 0..target_height as usize {
        let sample_y = ((target_y as f64 + 0.5) * scale_y - 0.5)
            .clamp(0.0, source_height.saturating_sub(1) as f64);
        let y0 = sample_y.floor() as usize;
        let y1 = (y0 + 1).min(source_height as usize - 1);
        let wy = sample_y - y0 as f64;
        for target_x in 0..target_width as usize {
            let sample_x = ((target_x as f64 + 0.5) * scale_x - 0.5)
                .clamp(0.0, source_width.saturating_sub(1) as f64);
            let x0 = sample_x.floor() as usize;
            let x1 = (x0 + 1).min(source_width as usize - 1);
            let wx = sample_x - x0 as f64;
            let target_offset = (target_y * target_width as usize + target_x) * 4;

            // 作者: long；桌面文字和细线被缩小后还会被手机端再次放大，最近邻会放大锯齿；双线性采样让 CoreGraphics 兜底路径的局部放大观感更接近 ScreenCaptureKit 主路径。
            for channel in 0..4 {
                let p00 = bgra_bytes[(y0 * source_width as usize + x0) * 4 + channel] as f64;
                let p10 = bgra_bytes[(y0 * source_width as usize + x1) * 4 + channel] as f64;
                let p01 = bgra_bytes[(y1 * source_width as usize + x0) * 4 + channel] as f64;
                let p11 = bgra_bytes[(y1 * source_width as usize + x1) * 4 + channel] as f64;
                let top = p00 + (p10 - p00) * wx;
                let bottom = p01 + (p11 - p01) * wx;
                resized[target_offset + channel] = (top + (bottom - top) * wy).round() as u8;
            }
        }
    }
    Ok(resized)
}

#[cfg(all(target_os = "macos", test))]
fn macos_capture_take_frame(
    _source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    let (width, height) = fit_capture_size(1728, 1117, config);
    let mut rgba_bytes = Vec::with_capacity(width as usize * height as usize * 4);

    for y in 0..height {
        for x in 0..width {
            rgba_bytes.push((x % 255) as u8);
            rgba_bytes.push((y % 255) as u8);
            rgba_bytes.push(0x7f);
            rgba_bytes.push(0xff);
        }
    }

    let (encoded_bytes, mime_type) = encode_frame_rgba(&rgba_bytes, width, height, config)?;
    Ok(CaptureFrameData {
        encoded_bytes,
        mime_type,
        width,
        height,
    })
}

#[cfg(not(target_os = "macos"))]
fn macos_capture_take_frame(
    _source: &CaptureSourceSummary,
    _config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    Err("macOS screen capture backend is unavailable on this build target".to_string())
}

#[cfg(all(target_os = "windows", not(test)))]
#[derive(Debug, Clone, Copy)]
struct WindowsVirtualDesktopBounds {
    left: i32,
    top: i32,
    width: i32,
    height: i32,
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_capture_permission_state() -> CapturePermissionState {
    CapturePermissionState {
        capability: SCREEN_RECORDING_CAPABILITY,
        status: "granted",
        can_request: false,
        detail: "Windows GDI screen capture is available without an extra permission prompt"
            .to_string(),
    }
}

#[cfg(all(target_os = "windows", test))]
fn windows_capture_permission_state() -> CapturePermissionState {
    CapturePermissionState {
        capability: SCREEN_RECORDING_CAPABILITY,
        status: "granted",
        can_request: false,
        detail: "Windows test capture backend returns synthetic frame data".to_string(),
    }
}

#[cfg(not(target_os = "windows"))]
fn windows_capture_permission_state() -> CapturePermissionState {
    CapturePermissionState {
        capability: SCREEN_RECORDING_CAPABILITY,
        status: "unsupported",
        can_request: false,
        detail: "Windows screen capture backend is unavailable on this build target".to_string(),
    }
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    let bounds = windows_virtual_desktop_bounds()?;

    Ok(vec![CaptureSourceSummary {
        source_id: WINDOWS_VIRTUAL_DESKTOP_SOURCE_ID.to_string(),
        kind: CaptureSourceKind::Display,
        title: "Virtual Desktop".to_string(),
        backend: capture_backend_name().to_string(),
        width: bounds.width as u32,
        height: bounds.height as u32,
        is_primary: true,
    }])
}

#[cfg(all(target_os = "windows", test))]
fn windows_capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    Ok(vec![CaptureSourceSummary {
        source_id: WINDOWS_VIRTUAL_DESKTOP_SOURCE_ID.to_string(),
        kind: CaptureSourceKind::Display,
        title: "Virtual Desktop".to_string(),
        backend: "windows.test_stub".to_string(),
        width: 1920,
        height: 1080,
        is_primary: true,
    }])
}

#[cfg(not(target_os = "windows"))]
fn windows_capture_list_sources() -> Result<Vec<CaptureSourceSummary>, String> {
    Err("Windows screen capture backend is unavailable on this build target".to_string())
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_capture_take_frame(
    source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    if source.source_id != WINDOWS_VIRTUAL_DESKTOP_SOURCE_ID {
        return Err(format!(
            "unsupported Windows capture source id: {}",
            source.source_id
        ));
    }

    let bounds = windows_virtual_desktop_bounds()?;
    let region = capture_pixel_region(bounds.width as u32, bounds.height as u32, config);
    let (target_width, target_height) = fit_capture_size(region.width, region.height, config);

    let screen_dc = unsafe { GetDC(None) };
    if screen_dc.0.is_null() {
        return Err("failed to acquire Windows desktop device context".to_string());
    }

    let memory_dc = unsafe { CreateCompatibleDC(Some(screen_dc)) };
    if memory_dc.0.is_null() {
        unsafe {
            ReleaseDC(None, screen_dc);
        }
        return Err("failed to create Windows memory device context".to_string());
    }

    let bitmap =
        unsafe { CreateCompatibleBitmap(screen_dc, target_width as i32, target_height as i32) };
    if bitmap.0.is_null() {
        unsafe {
            let _ = DeleteDC(memory_dc);
            ReleaseDC(None, screen_dc);
        }
        return Err(format!(
            "failed to create Windows capture bitmap for {}x{} frame",
            target_width, target_height
        ));
    }

    let previous_bitmap = unsafe { SelectObject(memory_dc, HGDIOBJ(bitmap.0)) };
    if previous_bitmap.0.is_null() {
        unsafe {
            let _ = DeleteObject(HGDIOBJ(bitmap.0));
            let _ = DeleteDC(memory_dc);
            ReleaseDC(None, screen_dc);
        }
        return Err(
            "failed to select Windows capture bitmap into memory device context".to_string(),
        );
    }

    let raster_operation = SRCCOPY | CAPTUREBLT;
    let copied = unsafe {
        if target_width as i32 == bounds.width && target_height as i32 == bounds.height {
            BitBlt(
                memory_dc,
                0,
                0,
                target_width as i32,
                target_height as i32,
                Some(screen_dc),
                bounds.left,
                bounds.top,
                raster_operation,
            )
            .is_ok()
        } else {
            StretchBlt(
                memory_dc,
                0,
                0,
                target_width as i32,
                target_height as i32,
                Some(screen_dc),
                bounds.left + region.x as i32,
                bounds.top + region.y as i32,
                region.width as i32,
                region.height as i32,
                raster_operation,
            )
            .as_bool()
        }
    };

    if !copied {
        unsafe {
            SelectObject(memory_dc, previous_bitmap);
            let _ = DeleteObject(HGDIOBJ(bitmap.0));
            let _ = DeleteDC(memory_dc);
            ReleaseDC(None, screen_dc);
        }
        return Err(format!(
            "failed to copy Windows desktop pixels for {} source into {}x{} frame",
            source.source_id, target_width, target_height
        ));
    }

    let mut bitmap_info = BITMAPINFO::default();
    bitmap_info.bmiHeader.biSize = size_of::<BITMAPINFOHEADER>() as u32;
    bitmap_info.bmiHeader.biWidth = target_width as i32;
    bitmap_info.bmiHeader.biHeight = -(target_height as i32);
    bitmap_info.bmiHeader.biPlanes = 1;
    bitmap_info.bmiHeader.biBitCount = 32;
    bitmap_info.bmiHeader.biCompression = BI_RGB.0;

    let mut bgra_bytes = vec![0_u8; target_width as usize * target_height as usize * 4];
    let copied_scan_lines = unsafe {
        GetDIBits(
            memory_dc,
            bitmap,
            0,
            target_height,
            Some(bgra_bytes.as_mut_ptr().cast()),
            &mut bitmap_info,
            DIB_RGB_COLORS,
        )
    };

    unsafe {
        SelectObject(memory_dc, previous_bitmap);
        let _ = DeleteObject(HGDIOBJ(bitmap.0));
        let _ = DeleteDC(memory_dc);
        ReleaseDC(None, screen_dc);
    }

    if copied_scan_lines == 0 {
        return Err(format!(
            "failed to extract Windows desktop pixels for {} from the capture bitmap",
            source.source_id
        ));
    }

    if copied_scan_lines != target_height as i32 {
        return Err(format!(
            "Windows capture bitmap for {} returned {} scan lines, expected {}",
            source.source_id, copied_scan_lines, target_height
        ));
    }

    let (encoded_bytes, mime_type) =
        encode_owned_bgra(bgra_bytes, target_width, target_height, config)?;
    Ok(CaptureFrameData {
        encoded_bytes,
        mime_type,
        width: target_width,
        height: target_height,
    })
}

#[cfg(all(target_os = "windows", test))]
fn windows_capture_take_frame(
    _source: &CaptureSourceSummary,
    config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    let (width, height) = fit_capture_size(1920, 1080, config);
    let mut rgba_bytes = Vec::with_capacity(width as usize * height as usize * 4);

    for y in 0..height {
        for x in 0..width {
            rgba_bytes.push(0x33);
            rgba_bytes.push((x % 255) as u8);
            rgba_bytes.push((y % 255) as u8);
            rgba_bytes.push(0xff);
        }
    }

    let (encoded_bytes, mime_type) = encode_frame_rgba(&rgba_bytes, width, height, config)?;
    Ok(CaptureFrameData {
        encoded_bytes,
        mime_type,
        width,
        height,
    })
}

#[cfg(not(target_os = "windows"))]
fn windows_capture_take_frame(
    _source: &CaptureSourceSummary,
    _config: &CaptureConfig,
) -> Result<CaptureFrameData, String> {
    Err("Windows screen capture backend is unavailable on this build target".to_string())
}

#[cfg(all(target_os = "windows", not(test)))]
fn windows_virtual_desktop_bounds() -> Result<WindowsVirtualDesktopBounds, String> {
    let left = unsafe { GetSystemMetrics(SM_XVIRTUALSCREEN) };
    let top = unsafe { GetSystemMetrics(SM_YVIRTUALSCREEN) };
    let width = unsafe { GetSystemMetrics(SM_CXVIRTUALSCREEN) };
    let height = unsafe { GetSystemMetrics(SM_CYVIRTUALSCREEN) };

    if width <= 0 || height <= 0 {
        return Err(format!(
            "Windows virtual desktop metrics are invalid: origin=({}, {}), size={}x{}",
            left, top, width, height
        ));
    }

    Ok(WindowsVirtualDesktopBounds {
        left,
        top,
        width,
        height,
    })
}

fn fit_capture_size(source_width: u32, source_height: u32, config: &CaptureConfig) -> (u32, u32) {
    if source_width == 0 || source_height == 0 {
        return (1, 1);
    }

    let scale = (config.max_width as f64 / source_width as f64)
        .min(config.max_height as f64 / source_height as f64)
        .min(1.0);

    (
        ((source_width as f64) * scale).round().max(1.0) as u32,
        ((source_height as f64) * scale).round().max(1.0) as u32,
    )
}

fn capture_pixel_region(
    source_width: u32,
    source_height: u32,
    config: &CaptureConfig,
) -> CapturePixelRegion {
    if source_width == 0 || source_height == 0 {
        return CapturePixelRegion {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
            cropped: false,
        };
    }
    let x_ppm = config.source_rect_x_ppm.min(SOURCE_RECT_UNITS);
    let y_ppm = config.source_rect_y_ppm.min(SOURCE_RECT_UNITS);
    let width_ppm = config
        .source_rect_width_ppm
        .clamp(1, SOURCE_RECT_UNITS.saturating_sub(x_ppm).max(1));
    let height_ppm = config
        .source_rect_height_ppm
        .clamp(1, SOURCE_RECT_UNITS.saturating_sub(y_ppm).max(1));

    let mut x = ((source_width as u64 * x_ppm as u64) / SOURCE_RECT_UNITS as u64) as u32;
    let mut y = ((source_height as u64 * y_ppm as u64) / SOURCE_RECT_UNITS as u64) as u32;
    x = x.min(source_width.saturating_sub(1));
    y = y.min(source_height.saturating_sub(1));
    let right_ppm = x_ppm.saturating_add(width_ppm).min(SOURCE_RECT_UNITS);
    let bottom_ppm = y_ppm.saturating_add(height_ppm).min(SOURCE_RECT_UNITS);
    let right = ((source_width as u64 * right_ppm as u64 + SOURCE_RECT_UNITS as u64 - 1)
        / SOURCE_RECT_UNITS as u64) as u32;
    let bottom = ((source_height as u64 * bottom_ppm as u64 + SOURCE_RECT_UNITS as u64 - 1)
        / SOURCE_RECT_UNITS as u64) as u32;
    let width = right
        .saturating_sub(x)
        .clamp(1, source_width.saturating_sub(x).max(1));
    let height = bottom
        .saturating_sub(y)
        .clamp(1, source_height.saturating_sub(y).max(1));
    let cropped = x > 0 || y > 0 || width < source_width || height < source_height;
    CapturePixelRegion {
        x,
        y,
        width,
        height,
        cropped,
    }
}

fn fit_region_capture_size(
    source_width: u32,
    source_height: u32,
    config: &CaptureConfig,
) -> (u32, u32) {
    let region = capture_pixel_region(source_width, source_height, config);
    if region.cropped {
        fit_cropped_capture_size(region.width, region.height, config)
    } else {
        fit_capture_size(region.width, region.height, config)
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn capture_region_to_cg_rect(region: CapturePixelRegion) -> CGRect {
    CGRect::new(
        region.x as f64,
        region.y as f64,
        region.width as f64,
        region.height as f64,
    )
}

fn fit_cropped_capture_size(
    region_width: u32,
    region_height: u32,
    config: &CaptureConfig,
) -> (u32, u32) {
    if region_width == 0 || region_height == 0 {
        return (1, 1);
    }

    let fit_scale = (config.max_width as f64 / region_width as f64)
        .min(config.max_height as f64 / region_height as f64);
    let scale = if fit_scale < 1.0 {
        fit_scale
    } else {
        fit_scale.min(CROPPED_REGION_MAX_UPSCALE)
    };

    (
        ((region_width as f64) * scale).round().max(1.0) as u32,
        ((region_height as f64) * scale).round().max(1.0) as u32,
    )
}

fn crop_bgra_region(
    bgra_bytes: &[u8],
    source_width: u32,
    source_height: u32,
    region: CapturePixelRegion,
) -> Result<Vec<u8>, String> {
    let expected_len = source_width as usize * source_height as usize * 4;
    if bgra_bytes.len() != expected_len {
        return Err(format!(
            "invalid BGRA buffer size {}, expected {} for {}x{}",
            bgra_bytes.len(),
            expected_len,
            source_width,
            source_height
        ));
    }
    if !region.cropped {
        return Ok(bgra_bytes.to_vec());
    }
    let row_len = region.width as usize * 4;
    let mut cropped = vec![0_u8; region.width as usize * region.height as usize * 4];
    for row in 0..region.height as usize {
        let src_y = region.y as usize + row;
        let src_offset = (src_y * source_width as usize + region.x as usize) * 4;
        let dst_offset = row * row_len;
        cropped[dst_offset..dst_offset + row_len]
            .copy_from_slice(&bgra_bytes[src_offset..src_offset + row_len]);
    }
    Ok(cropped)
}

fn crop_bgra_region_from_strided(
    bgra_bytes: &[u8],
    source_width: u32,
    source_height: u32,
    bytes_per_row: usize,
    region: CapturePixelRegion,
) -> Result<Vec<u8>, String> {
    if source_width == 0 || source_height == 0 {
        return Err(format!(
            "invalid BGRA source size {}x{}",
            source_width, source_height
        ));
    }
    let min_row_len = source_width as usize * 4;
    if bytes_per_row < min_row_len {
        return Err(format!(
            "invalid BGRA bytes_per_row {}, expected at least {} for {}x{}",
            bytes_per_row, min_row_len, source_width, source_height
        ));
    }
    let expected_len = bytes_per_row * source_height as usize;
    if bgra_bytes.len() < expected_len {
        return Err(format!(
            "invalid strided BGRA buffer size {}, expected at least {} for {}x{} row_bytes={}",
            bgra_bytes.len(),
            expected_len,
            source_width,
            source_height,
            bytes_per_row
        ));
    }
    if !region.cropped && bytes_per_row == min_row_len {
        return Ok(bgra_bytes[..min_row_len * source_height as usize].to_vec());
    }

    let row_len = region.width as usize * 4;
    let mut cropped = vec![0_u8; region.width as usize * region.height as usize * 4];
    for row in 0..region.height as usize {
        let src_y = region.y as usize + row;
        let src_offset = src_y * bytes_per_row + region.x as usize * 4;
        let dst_offset = row * row_len;
        cropped[dst_offset..dst_offset + row_len]
            .copy_from_slice(&bgra_bytes[src_offset..src_offset + row_len]);
    }
    Ok(cropped)
}

fn compact_strided_bgra_frame(
    bgra_bytes: &[u8],
    frame_width: u32,
    frame_height: u32,
    bytes_per_row: usize,
) -> Result<Vec<u8>, String> {
    if frame_width == 0 || frame_height == 0 {
        return Err(format!(
            "invalid BGRA frame size {}x{}",
            frame_width, frame_height
        ));
    }
    let row_len = frame_width as usize * 4;
    if bytes_per_row < row_len {
        return Err(format!(
            "invalid BGRA bytes_per_row {}, expected at least {} for {}x{}",
            bytes_per_row, row_len, frame_width, frame_height
        ));
    }
    let expected_len = bytes_per_row * frame_height as usize;
    if bgra_bytes.len() < expected_len {
        return Err(format!(
            "invalid strided BGRA buffer size {}, expected at least {} for {}x{} row_bytes={}",
            bgra_bytes.len(),
            expected_len,
            frame_width,
            frame_height,
            bytes_per_row
        ));
    }
    if bytes_per_row == row_len {
        return Ok(bgra_bytes[..row_len * frame_height as usize].to_vec());
    }

    let mut compact = vec![0_u8; row_len * frame_height as usize];
    for row in 0..frame_height as usize {
        let src_offset = row * bytes_per_row;
        let dst_offset = row * row_len;
        compact[dst_offset..dst_offset + row_len]
            .copy_from_slice(&bgra_bytes[src_offset..src_offset + row_len]);
    }
    Ok(compact)
}

fn crop_and_resize_bgra_for_config(
    bgra_bytes: Vec<u8>,
    source_width: u32,
    source_height: u32,
    config: &CaptureConfig,
) -> Result<(Vec<u8>, u32, u32), String> {
    let region = capture_pixel_region(source_width, source_height, config);
    let (target_width, target_height) = if region.cropped {
        // 作者: long；手机全屏局部放大时，源裁剪区域会被 Android 再铺满远控窗口；仅对局部高清做有限上采样，减少二次放大带来的文字发虚，同时不改变整屏低延迟链路。
        fit_cropped_capture_size(region.width, region.height, config)
    } else {
        fit_capture_size(region.width, region.height, config)
    };
    if !region.cropped && region.width == target_width && region.height == target_height {
        // 作者: long；全屏和鼠标移动档本来就是完整帧低延迟路径，避免为了“不裁剪”再复制一次整块 BGRA，给后续 JPEG 编码留下更多时间片。
        return Ok((bgra_bytes, target_width, target_height));
    }
    let cropped = crop_bgra_region(&bgra_bytes, source_width, source_height, region)?;
    if region.width == target_width && region.height == target_height {
        return Ok((cropped, target_width, target_height));
    }
    let resized = resize_bgra_bilinear(
        &cropped,
        region.width,
        region.height,
        target_width,
        target_height,
    )?;
    Ok((resized, target_width, target_height))
}

fn crop_and_resize_strided_bgra_for_config(
    bgra_bytes: &[u8],
    source_width: u32,
    source_height: u32,
    bytes_per_row: usize,
    config: &CaptureConfig,
) -> Result<(Vec<u8>, u32, u32), String> {
    let region = capture_pixel_region(source_width, source_height, config);
    let (target_width, target_height) = if region.cropped {
        fit_cropped_capture_size(region.width, region.height, config)
    } else {
        fit_capture_size(region.width, region.height, config)
    };
    // 作者: long；ScreenCaptureKit 局部高清帧来自带 stride 的整屏 CVPixelBuffer，直接按行裁出手机当前视口，避免先复制一整张 Mac 屏再裁局部。
    let cropped = crop_bgra_region_from_strided(
        bgra_bytes,
        source_width,
        source_height,
        bytes_per_row,
        region,
    )?;
    if region.width == target_width && region.height == target_height {
        return Ok((cropped, target_width, target_height));
    }
    let resized = resize_bgra_bilinear(
        &cropped,
        region.width,
        region.height,
        target_width,
        target_height,
    )?;
    Ok((resized, target_width, target_height))
}

fn selected_capture_codec(config: &CaptureConfig) -> &'static str {
    let codec = config.codec.trim();
    if codec.eq_ignore_ascii_case(RAW_BGRA_FRAME_CODEC)
        || codec.eq_ignore_ascii_case(RAW_BGRA_MIME_TYPE)
    {
        return RAW_BGRA_FRAME_CODEC;
    }
    if codec.eq_ignore_ascii_case(JPEG_FRAME_CODEC) || codec.eq_ignore_ascii_case(JPEG_MIME_TYPE) {
        return JPEG_FRAME_CODEC;
    }
    PNG_FRAME_CODEC
}

fn encode_frame_rgba(
    rgba_bytes: &[u8],
    width: u32,
    height: u32,
    config: &CaptureConfig,
) -> Result<(Vec<u8>, &'static str), String> {
    match selected_capture_codec(config) {
        RAW_BGRA_FRAME_CODEC => {
            let bgra_bytes = rgba_to_bgra_bytes(rgba_bytes)?;
            Ok((bgra_bytes, RAW_BGRA_MIME_TYPE))
        }
        JPEG_FRAME_CODEC => Ok((
            encode_jpeg_rgba(rgba_bytes, width, height, config)?,
            JPEG_MIME_TYPE,
        )),
        _ => Ok((encode_png_rgba(rgba_bytes, width, height)?, PNG_MIME_TYPE)),
    }
}

fn encode_frame_bgra(
    bgra_bytes: &[u8],
    width: u32,
    height: u32,
    config: &CaptureConfig,
) -> Result<(Vec<u8>, &'static str), String> {
    match selected_capture_codec(config) {
        RAW_BGRA_FRAME_CODEC => Ok((bgra_bytes.to_vec(), RAW_BGRA_MIME_TYPE)),
        JPEG_FRAME_CODEC => Ok((
            encode_jpeg_bgra(bgra_bytes, width, height, config)?,
            JPEG_MIME_TYPE,
        )),
        _ => {
            let rgba_bytes = bgra_to_rgba_bytes(bgra_bytes)?;
            Ok((encode_png_rgba(&rgba_bytes, width, height)?, PNG_MIME_TYPE))
        }
    }
}

fn encode_owned_bgra(
    bgra_bytes: Vec<u8>,
    width: u32,
    height: u32,
    config: &CaptureConfig,
) -> Result<(Vec<u8>, &'static str), String> {
    match selected_capture_codec(config) {
        RAW_BGRA_FRAME_CODEC => Ok((bgra_bytes, RAW_BGRA_MIME_TYPE)),
        _ => encode_frame_bgra(&bgra_bytes, width, height, config),
    }
}

fn encode_png_rgba(rgba_bytes: &[u8], width: u32, height: u32) -> Result<Vec<u8>, String> {
    let mut png_bytes = Vec::new();
    let mut encoder = png::Encoder::new(&mut png_bytes, width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);

    let mut writer = encoder
        .write_header()
        .map_err(|error| format!("failed to write PNG header: {error}"))?;
    writer
        .write_image_data(rgba_bytes)
        .map_err(|error| format!("failed to encode PNG image: {error}"))?;
    drop(writer);

    Ok(png_bytes)
}

fn encode_jpeg_rgba(
    rgba_bytes: &[u8],
    width: u32,
    height: u32,
    config: &CaptureConfig,
) -> Result<Vec<u8>, String> {
    if width > u16::MAX as u32 || height > u16::MAX as u32 {
        return Err(format!(
            "jpeg encoder only supports dimensions up to {}x{}, got {}x{}",
            u16::MAX,
            u16::MAX,
            width,
            height
        ));
    }

    // 作者: long；jpeg-encoder 能直接读取 RGBA，避免每帧先复制成 RGB 中间缓冲，减少 legacy JPEG 兜底链路的捕获/编码耗时。
    encode_jpeg_pixels(
        rgba_bytes,
        width,
        height,
        jpeg_quality_for_config(config, width, height),
        jpeg_encoder::ColorType::Rgba,
        4,
    )
}

fn encode_jpeg_bgra(
    bgra_bytes: &[u8],
    width: u32,
    height: u32,
    config: &CaptureConfig,
) -> Result<Vec<u8>, String> {
    if width > u16::MAX as u32 || height > u16::MAX as u32 {
        return Err(format!(
            "jpeg encoder only supports dimensions up to {}x{}, got {}x{}",
            u16::MAX,
            u16::MAX,
            width,
            height
        ));
    }

    let expected_len = (width as usize) * (height as usize) * 4;
    if bgra_bytes.len() != expected_len {
        return Err(format!(
            "invalid BGRA buffer size {}, expected {} for {}x{}",
            bgra_bytes.len(),
            expected_len,
            width,
            height
        ));
    }

    // 作者: long；ScreenCaptureKit/CoreGraphics 热路径产出 BGRA，直接交给编码器可以少一次逐像素通道重排和内存分配。
    encode_jpeg_pixels(
        bgra_bytes,
        width,
        height,
        jpeg_quality_for_config(config, width, height),
        jpeg_encoder::ColorType::Bgra,
        4,
    )
}

fn jpeg_quality_for_config(config: &CaptureConfig, width: u32, height: u32) -> u8 {
    let pixels = u64::from(width) * u64::from(height);
    if capture_source_rect_is_cropped(config) {
        // 作者: long；局部静止帧承担缩放后读文字和点击输入框，1280 宽以内继续保持锐利质量；更大的裁剪帧再降码量，避免 JPEG 解码把真机拖死。
        return if pixels <= 1_120_000 {
            JPEG_QUALITY_DETAIL_SHARP
        } else {
            JPEG_QUALITY_DETAIL_BALANCED
        };
    }
    // 作者: long；Android 真机兜底流同时受 JPEG 编码、Base64 信令和 Bitmap 解码约束，移动档优先降低码量，停手后的高清档再提高质量补足文字清晰度。
    if config.max_width <= 700 || pixels <= 360_000 {
        JPEG_QUALITY_INTERACTIVE
    } else if config.max_width <= 1_000 || pixels <= 700_000 {
        JPEG_QUALITY_FULLSCREEN
    } else {
        JPEG_QUALITY_DETAIL_BALANCED
    }
}

fn capture_source_rect_is_cropped(config: &CaptureConfig) -> bool {
    config.source_rect_x_ppm > 0
        || config.source_rect_y_ppm > 0
        || config.source_rect_width_ppm < SOURCE_RECT_UNITS
        || config.source_rect_height_ppm < SOURCE_RECT_UNITS
}

fn encode_jpeg_pixels(
    pixel_bytes: &[u8],
    width: u32,
    height: u32,
    quality: u8,
    color_type: jpeg_encoder::ColorType,
    bytes_per_pixel: usize,
) -> Result<Vec<u8>, String> {
    let expected_len = (width as usize) * (height as usize) * bytes_per_pixel;
    if pixel_bytes.len() != expected_len {
        return Err(format!(
            "invalid JPEG source buffer size {}, expected {} for {}x{}",
            pixel_bytes.len(),
            expected_len,
            width,
            height
        ));
    }

    let mut jpeg_bytes = Vec::new();
    let encoder = jpeg_encoder::Encoder::new(&mut jpeg_bytes, quality);
    encoder
        .encode(pixel_bytes, width as u16, height as u16, color_type)
        .map_err(|error| format!("failed to encode JPEG image: {error}"))?;

    Ok(jpeg_bytes)
}

fn bgra_to_rgba_bytes(bgra_bytes: &[u8]) -> Result<Vec<u8>, String> {
    if bgra_bytes.len() % 4 != 0 {
        return Err(format!(
            "invalid BGRA buffer length {}, expected multiple of 4",
            bgra_bytes.len()
        ));
    }
    let mut rgba_bytes = Vec::with_capacity(bgra_bytes.len());
    for pixel in bgra_bytes.chunks_exact(4) {
        rgba_bytes.push(pixel[2]);
        rgba_bytes.push(pixel[1]);
        rgba_bytes.push(pixel[0]);
        rgba_bytes.push(pixel[3]);
    }
    Ok(rgba_bytes)
}

fn rgba_to_bgra_bytes(rgba_bytes: &[u8]) -> Result<Vec<u8>, String> {
    if rgba_bytes.len() % 4 != 0 {
        return Err(format!(
            "invalid RGBA buffer length {}, expected multiple of 4",
            rgba_bytes.len()
        ));
    }
    let mut bgra_bytes = Vec::with_capacity(rgba_bytes.len());
    for pixel in rgba_bytes.chunks_exact(4) {
        bgra_bytes.push(pixel[2]);
        bgra_bytes.push(pixel[1]);
        bgra_bytes.push(pixel[0]);
        bgra_bytes.push(pixel[3]);
    }
    Ok(bgra_bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config_with_codec(codec: &str) -> CaptureConfig {
        CaptureConfig {
            codec: codec.to_string(),
            ..CaptureConfig::default()
        }
    }

    #[test]
    fn selected_capture_codec_accepts_raw_bgra_aliases() {
        assert_eq!(
            selected_capture_codec(&config_with_codec("raw-bgra-frame-stream")),
            RAW_BGRA_FRAME_CODEC
        );
        assert_eq!(
            selected_capture_codec(&config_with_codec("application/x-rd-raw-bgra")),
            RAW_BGRA_FRAME_CODEC
        );
    }

    #[test]
    fn rgba_to_bgra_conversion_is_channel_swapped() {
        let rgba = vec![0x10, 0x20, 0x30, 0x40, 0xa1, 0xb2, 0xc3, 0xd4];
        let expected_bgra = vec![0x30, 0x20, 0x10, 0x40, 0xc3, 0xb2, 0xa1, 0xd4];
        assert_eq!(rgba_to_bgra_bytes(&rgba).unwrap(), expected_bgra);
        assert_eq!(bgra_to_rgba_bytes(&expected_bgra).unwrap(), rgba);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn resize_bgra_bilinear_keeps_same_size_buffer() {
        let bgra = vec![0, 10, 20, 255, 30, 40, 50, 255];
        assert_eq!(resize_bgra_bilinear(&bgra, 2, 1, 2, 1).unwrap(), bgra);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn resize_bgra_bilinear_blends_neighboring_pixels() {
        let bgra = vec![0, 0, 0, 255, 100, 0, 0, 255, 200, 0, 0, 255, 255, 0, 0, 255];
        let resized = resize_bgra_bilinear(&bgra, 2, 2, 1, 1).unwrap();
        assert_eq!(resized, vec![139, 0, 0, 255]);
    }

    #[test]
    fn capture_pixel_region_maps_normalized_ppm_to_pixels() {
        let config = CaptureConfig {
            source_rect_x_ppm: 250_000,
            source_rect_y_ppm: 100_000,
            source_rect_width_ppm: 500_000,
            source_rect_height_ppm: 500_000,
            ..CaptureConfig::default()
        };
        let region = capture_pixel_region(1000, 800, &config);
        assert_eq!(
            region,
            CapturePixelRegion {
                x: 250,
                y: 80,
                width: 500,
                height: 400,
                cropped: true,
            }
        );
    }

    #[test]
    fn cropped_capture_size_allows_bounded_upscale() {
        let config = CaptureConfig {
            max_width: 1280,
            max_height: 800,
            ..CaptureConfig::default()
        };
        assert_eq!(fit_cropped_capture_size(1000, 600, &config), (1280, 768));
        assert_eq!(fit_cropped_capture_size(400, 200, &config), (540, 270));
    }

    #[test]
    fn cropped_capture_size_still_honors_max_bounds() {
        let config = CaptureConfig {
            max_width: 960,
            max_height: 600,
            ..CaptureConfig::default()
        };
        assert_eq!(fit_cropped_capture_size(1000, 600, &config), (960, 576));
    }

    #[test]
    fn cropped_jpeg_quality_keeps_phone_zoom_regions_sharp_until_large_frames() {
        let config = CaptureConfig {
            codec: JPEG_FRAME_CODEC.to_string(),
            source_rect_x_ppm: 250_000,
            source_rect_y_ppm: 200_000,
            source_rect_width_ppm: 500_000,
            source_rect_height_ppm: 450_000,
            ..CaptureConfig::default()
        };
        assert_eq!(
            jpeg_quality_for_config(&config, 960, 624),
            JPEG_QUALITY_DETAIL_SHARP
        );
        assert_eq!(
            jpeg_quality_for_config(&config, 1024, 600),
            JPEG_QUALITY_DETAIL_SHARP
        );
        assert_eq!(
            jpeg_quality_for_config(&config, 1120, 728),
            JPEG_QUALITY_DETAIL_SHARP
        );
        assert_eq!(
            jpeg_quality_for_config(&config, 1280, 832),
            JPEG_QUALITY_DETAIL_SHARP
        );
        assert_eq!(
            jpeg_quality_for_config(&config, 800, 520),
            JPEG_QUALITY_DETAIL_SHARP
        );
        assert_eq!(
            jpeg_quality_for_config(&config, 512, 334),
            JPEG_QUALITY_DETAIL_SHARP
        );
        assert_eq!(
            jpeg_quality_for_config(&config, 1440, 900),
            JPEG_QUALITY_DETAIL_BALANCED
        );
    }

    #[test]
    fn crop_bgra_region_extracts_expected_pixels() {
        let bgra = vec![
            1, 0, 0, 255, 2, 0, 0, 255, 3, 0, 0, 255, 4, 0, 0, 255, 5, 0, 0, 255, 6, 0, 0, 255,
        ];
        let cropped = crop_bgra_region(
            &bgra,
            3,
            2,
            CapturePixelRegion {
                x: 1,
                y: 0,
                width: 2,
                height: 2,
                cropped: true,
            },
        )
        .unwrap();
        assert_eq!(
            cropped,
            vec![2, 0, 0, 255, 3, 0, 0, 255, 5, 0, 0, 255, 6, 0, 0, 255,]
        );
    }

    #[test]
    fn crop_bgra_region_from_strided_ignores_row_padding() {
        let bgra = vec![
            1, 0, 0, 255, 2, 0, 0, 255, 3, 0, 0, 255, 99, 99, 99, 99, 4, 0, 0, 255, 5, 0, 0, 255,
            6, 0, 0, 255, 88, 88, 88, 88,
        ];
        let cropped = crop_bgra_region_from_strided(
            &bgra,
            3,
            2,
            16,
            CapturePixelRegion {
                x: 1,
                y: 0,
                width: 2,
                height: 2,
                cropped: true,
            },
        )
        .unwrap();
        assert_eq!(
            cropped,
            vec![2, 0, 0, 255, 3, 0, 0, 255, 5, 0, 0, 255, 6, 0, 0, 255,]
        );
    }
}

#[cfg(all(target_os = "macos", not(test)))]
fn macos_force_core_graphics() -> bool {
    std::env::var(MACOS_FORCE_CORE_GRAPHICS_ENV)
        .ok()
        .map(|value| {
            matches!(
                value.trim().to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(false)
}

#[cfg(all(target_os = "macos", not(test)))]
fn shareable_displays() -> Result<Vec<SCDisplay>, String> {
    if macos_force_core_graphics() {
        return Err(format!(
            "macOS ScreenCaptureKit source query is disabled by {}",
            MACOS_FORCE_CORE_GRAPHICS_ENV
        ));
    }
    let shareable_content = SCShareableContent::get()
        .map_err(|error| format!("failed to query macOS shareable displays: {error}"))?;
    let mut displays = shareable_content.displays();
    if displays.is_empty() {
        if let Ok(current_process_content) = SCShareableContent::current_process() {
            displays = current_process_content.displays();
        }
    }
    if displays.is_empty() {
        let permission = macos_capture_permission_state();
        return Err(format!(
            "no macOS shareable displays found (permission_status={} detail={})",
            permission.status, permission.detail
        ));
    }
    Ok(displays)
}

#[cfg(all(target_os = "macos", not(test)))]
fn shareable_display_for_id(display_id: CGDirectDisplayID) -> Result<SCDisplay, String> {
    shareable_displays()?
        .into_iter()
        .find(|display| display.display_id() == display_id)
        .ok_or_else(|| format!("macOS shareable display {display_id} was not found"))
}

#[cfg(all(target_os = "macos", not(test)))]
fn parse_display_source_id(source_id: &str) -> Result<CGDirectDisplayID, String> {
    let raw_value = source_id
        .strip_prefix("display:")
        .ok_or_else(|| format!("unsupported macOS capture source id: {source_id}"))?;
    raw_value
        .parse::<CGDirectDisplayID>()
        .map_err(|error| format!("invalid macOS capture source id {source_id}: {error}"))
}

#[cfg(all(target_os = "macos", not(test)))]
fn primary_display_id() -> CGDirectDisplayID {
    CGMainDisplayID()
}

#[cfg(all(target_os = "macos", not(test)))]
#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    fn CGPreflightScreenCaptureAccess() -> bool;
    fn CGRequestScreenCaptureAccess() -> bool;
}
