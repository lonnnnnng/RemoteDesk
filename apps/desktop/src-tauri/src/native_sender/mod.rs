use openh264::encoder::{
    BitRate, Encoder, EncoderConfig, FrameRate, FrameType, IntraFramePeriod, Profile,
    RateControlMode, UsageType,
};
use openh264::formats::{BgraSliceU8, RgbSliceU8, YUVBuffer};
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::io::Cursor;
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use webrtc::api::media_engine::MediaEngine;
use webrtc::api::media_engine::MIME_TYPE_H264;
use webrtc::api::APIBuilder;
use webrtc::ice_transport::ice_candidate::RTCIceCandidateInit;
use webrtc::ice_transport::ice_credential_type::RTCIceCredentialType;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::offer_answer_options::RTCOfferOptions;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::rtp_transceiver::rtp_codec::RTCRtpCodecCapability;
use webrtc::stats::StatsReportType;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;

#[derive(Debug, Clone, Serialize)]
pub struct NativeSenderCapabilities {
    pub supported: bool,
    pub support_level: String,
    pub support_detail: String,
    pub blocker_code: String,
    pub capture_backend: String,
    pub capture_support_level: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct NativeSenderStatus {
    pub lifecycle: String,
    pub session_id: String,
    pub dry_run: bool,
    pub signaling_state: String,
    pub started_at_ms: u64,
    pub stopped_at_ms: u64,
    pub last_signal_type: String,
    pub last_signal_direction: String,
    pub last_signal_trace_id: String,
    pub last_signal_payload_bytes: u64,
    pub signal_count: u64,
    pub inbound_signal_count: u64,
    pub outbound_signal_count: u64,
    pub local_offer_count: u64,
    pub local_answer_count: u64,
    pub local_candidate_count: u64,
    pub remote_offer_count: u64,
    pub remote_answer_count: u64,
    pub remote_candidate_count: u64,
    pub restart_ice_count: u64,
    pub local_restart_ice_count: u64,
    pub remote_restart_ice_count: u64,
    pub remote_answer_sdp_len: usize,
    pub remote_offer_sdp_len: usize,
    pub last_local_candidate_type: String,
    pub last_local_candidate_protocol: String,
    pub last_remote_candidate_type: String,
    pub last_remote_candidate_protocol: String,
    pub candidate_path: String,
    pub candidate_tier: String,
    pub media_probe_running: bool,
    pub media_probe_frame_count: u64,
    pub media_probe_total_bytes: u64,
    pub media_probe_last_frame_ts_ms: u64,
    pub media_probe_last_width: u32,
    pub media_probe_last_height: u32,
    pub media_probe_fps: f64,
    pub media_probe_kbps: f64,
    pub webrtc_outbound_stats_available: bool,
    pub webrtc_outbound_reports: u32,
    pub webrtc_outbound_bytes_sent: u64,
    pub webrtc_outbound_packets_sent: u64,
    pub webrtc_outbound_header_bytes_sent: u64,
    pub webrtc_outbound_kbps: f64,
    pub webrtc_outbound_fps: f64,
    pub webrtc_outbound_rtt_ms: f64,
    pub webrtc_outbound_updated_at_ms: u64,
    pub shadow_runtime_ready: bool,
    pub shadow_track_bound: bool,
    pub shadow_last_apply_action: String,
    pub last_error_code: String,
    pub last_error_detail: String,
    pub updated_at_ms: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct NativeSenderStartRequest {
    pub session_id: String,
    #[serde(default)]
    pub dry_run: bool,
    #[serde(default)]
    pub ice_servers: Vec<NativeSenderIceServer>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct NativeSenderIceServer {
    #[serde(default)]
    pub urls: Vec<String>,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub credential: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct NativeSenderCreateOfferRequest {
    pub session_id: String,
    #[serde(default)]
    pub ice_restart: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct NativeSenderDrainSignalsRequest {
    pub session_id: String,
    #[serde(default)]
    pub limit: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct NativeSenderOutgoingSignal {
    pub session_id: String,
    pub signal_type: String,
    pub signal_direction: String,
    pub trace_id: String,
    pub sdp: String,
    pub candidate: String,
    pub sdp_mid: String,
    pub sdp_mline_index: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct NativeSenderSignalEnvelope {
    pub session_id: String,
    pub signal_type: String,
    #[serde(default)]
    pub signal_direction: String,
    #[serde(default)]
    pub trace_id: String,
    #[serde(default)]
    pub sdp: String,
    #[serde(default)]
    pub candidate: String,
    #[serde(default)]
    pub sdp_mid: String,
    #[serde(default)]
    pub sdp_mline_index: i32,
}

#[derive(Debug, Default)]
struct NativeSenderRuntimeState {
    lifecycle: String,
    session_id: String,
    dry_run: bool,
    signaling_state: String,
    started_at_ms: u64,
    stopped_at_ms: u64,
    last_signal_type: String,
    last_signal_direction: String,
    last_signal_trace_id: String,
    last_signal_payload_bytes: u64,
    signal_count: u64,
    inbound_signal_count: u64,
    outbound_signal_count: u64,
    local_offer_count: u64,
    local_answer_count: u64,
    local_candidate_count: u64,
    remote_offer_count: u64,
    remote_answer_count: u64,
    remote_candidate_count: u64,
    restart_ice_count: u64,
    local_restart_ice_count: u64,
    remote_restart_ice_count: u64,
    remote_answer_sdp_len: usize,
    remote_offer_sdp_len: usize,
    last_local_candidate_type: String,
    last_local_candidate_protocol: String,
    last_remote_candidate_type: String,
    last_remote_candidate_protocol: String,
    candidate_path: String,
    candidate_tier: String,
    media_probe_running: bool,
    media_probe_frame_count: u64,
    media_probe_total_bytes: u64,
    media_probe_last_frame_ts_ms: u64,
    media_probe_last_width: u32,
    media_probe_last_height: u32,
    media_probe_fps: f64,
    media_probe_kbps: f64,
    webrtc_outbound_stats_available: bool,
    webrtc_outbound_reports: u32,
    webrtc_outbound_bytes_sent: u64,
    webrtc_outbound_packets_sent: u64,
    webrtc_outbound_header_bytes_sent: u64,
    webrtc_outbound_kbps: f64,
    webrtc_outbound_fps: f64,
    webrtc_outbound_rtt_ms: f64,
    webrtc_outbound_updated_at_ms: u64,
    shadow_runtime_ready: bool,
    shadow_track_bound: bool,
    shadow_last_apply_action: String,
    last_error_code: String,
    last_error_detail: String,
    updated_at_ms: u64,
}

static NATIVE_SENDER_STATE: OnceLock<Mutex<NativeSenderRuntimeState>> = OnceLock::new();
static NATIVE_SENDER_WORKER_STATE: OnceLock<Mutex<NativeSenderWorkerState>> = OnceLock::new();
static NATIVE_SENDER_WEBRTC_RUNTIME: OnceLock<Mutex<NativeSenderWebRtcRuntime>> = OnceLock::new();

#[derive(Debug, Default)]
struct NativeSenderWorkerState {
    stop_tx: Option<mpsc::Sender<()>>,
    handle: Option<JoinHandle<()>>,
}

#[derive(Default)]
struct NativeSenderWebRtcRuntime {
    session_id: String,
    peer_connection: Option<Arc<RTCPeerConnection>>,
    video_track: Option<Arc<TrackLocalStaticSample>>,
    outbound_signals: VecDeque<NativeSenderOutgoingSignal>,
    outbound_signal_seq: u64,
    initialized_at_ms: u64,
}

#[derive(Debug, Default, Clone, Copy)]
struct NativeSenderOutboundRtpSnapshot {
    report_count: u32,
    bytes_sent: u64,
    packets_sent: u64,
    header_bytes_sent: u64,
    round_trip_time_ms: f64,
    round_trip_time_samples: u32,
}

const NATIVE_SENDER_PROBE_DEFAULT_INTERVAL_MS: u64 = 42;
const NATIVE_SENDER_PROBE_SAMPLE_WINDOW_MS: u64 = 2000;
const NATIVE_SENDER_FORCE_INTRA_INTERVAL_FRAMES: u64 = 72;

fn target_loop_interval_ms(capture_fps: u32) -> u64 {
    let fps = capture_fps.max(1);
    let base = (1000_u64 / u64::from(fps)).max(1);
    // Give a small pacing headroom so end-to-end stays closer to >=24fps under real processing cost.
    if fps >= 20 { base.saturating_sub(2).max(1) } else { base }
}

fn native_sender_state() -> &'static Mutex<NativeSenderRuntimeState> {
    NATIVE_SENDER_STATE.get_or_init(|| {
        Mutex::new(NativeSenderRuntimeState {
            lifecycle: "idle".to_string(),
            signaling_state: "idle".to_string(),
            last_local_candidate_type: "-".to_string(),
            last_local_candidate_protocol: "-".to_string(),
            last_remote_candidate_type: "-".to_string(),
            last_remote_candidate_protocol: "-".to_string(),
            candidate_path: "-".to_string(),
            candidate_tier: "-".to_string(),
            webrtc_outbound_rtt_ms: -1.0,
            updated_at_ms: now_ms(),
            ..NativeSenderRuntimeState::default()
        })
    })
}

fn native_sender_worker_state() -> &'static Mutex<NativeSenderWorkerState> {
    NATIVE_SENDER_WORKER_STATE.get_or_init(|| Mutex::new(NativeSenderWorkerState::default()))
}

fn native_sender_webrtc_runtime() -> &'static Mutex<NativeSenderWebRtcRuntime> {
    NATIVE_SENDER_WEBRTC_RUNTIME.get_or_init(|| Mutex::new(NativeSenderWebRtcRuntime::default()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as u64)
        .unwrap_or(0)
}

fn trace_native_sender(event: &str, detail: impl AsRef<str>) {
    eprintln!(
        "[rd.native_sender] ts_ms={} {event} {}",
        now_ms(),
        detail.as_ref()
    );
}

fn current_capabilities() -> NativeSenderCapabilities {
    let capture = crate::platform::capture_capabilities();
    NativeSenderCapabilities {
        supported: true,
        support_level: "peer_connection_owner_experimental".to_string(),
        support_detail: "native sender runs a Rust-owned RTCPeerConnection, publishes a local H264 track from capture frames, and exposes outbound offer/ice signals for JS relay forwarding".to_string(),
        blocker_code: "-".to_string(),
        capture_backend: capture.backend.to_string(),
        capture_support_level: capture.support_level.to_string(),
    }
}

fn snapshot_status(state: &NativeSenderRuntimeState) -> NativeSenderStatus {
    NativeSenderStatus {
        lifecycle: state.lifecycle.clone(),
        session_id: state.session_id.clone(),
        dry_run: state.dry_run,
        signaling_state: state.signaling_state.clone(),
        started_at_ms: state.started_at_ms,
        stopped_at_ms: state.stopped_at_ms,
        last_signal_type: state.last_signal_type.clone(),
        last_signal_direction: state.last_signal_direction.clone(),
        last_signal_trace_id: state.last_signal_trace_id.clone(),
        last_signal_payload_bytes: state.last_signal_payload_bytes,
        signal_count: state.signal_count,
        inbound_signal_count: state.inbound_signal_count,
        outbound_signal_count: state.outbound_signal_count,
        local_offer_count: state.local_offer_count,
        local_answer_count: state.local_answer_count,
        local_candidate_count: state.local_candidate_count,
        remote_offer_count: state.remote_offer_count,
        remote_answer_count: state.remote_answer_count,
        remote_candidate_count: state.remote_candidate_count,
        restart_ice_count: state.restart_ice_count,
        local_restart_ice_count: state.local_restart_ice_count,
        remote_restart_ice_count: state.remote_restart_ice_count,
        remote_answer_sdp_len: state.remote_answer_sdp_len,
        remote_offer_sdp_len: state.remote_offer_sdp_len,
        last_local_candidate_type: state.last_local_candidate_type.clone(),
        last_local_candidate_protocol: state.last_local_candidate_protocol.clone(),
        last_remote_candidate_type: state.last_remote_candidate_type.clone(),
        last_remote_candidate_protocol: state.last_remote_candidate_protocol.clone(),
        candidate_path: state.candidate_path.clone(),
        candidate_tier: state.candidate_tier.clone(),
        media_probe_running: state.media_probe_running,
        media_probe_frame_count: state.media_probe_frame_count,
        media_probe_total_bytes: state.media_probe_total_bytes,
        media_probe_last_frame_ts_ms: state.media_probe_last_frame_ts_ms,
        media_probe_last_width: state.media_probe_last_width,
        media_probe_last_height: state.media_probe_last_height,
        media_probe_fps: state.media_probe_fps,
        media_probe_kbps: state.media_probe_kbps,
        webrtc_outbound_stats_available: state.webrtc_outbound_stats_available,
        webrtc_outbound_reports: state.webrtc_outbound_reports,
        webrtc_outbound_bytes_sent: state.webrtc_outbound_bytes_sent,
        webrtc_outbound_packets_sent: state.webrtc_outbound_packets_sent,
        webrtc_outbound_header_bytes_sent: state.webrtc_outbound_header_bytes_sent,
        webrtc_outbound_kbps: state.webrtc_outbound_kbps,
        webrtc_outbound_fps: state.webrtc_outbound_fps,
        webrtc_outbound_rtt_ms: state.webrtc_outbound_rtt_ms,
        webrtc_outbound_updated_at_ms: state.webrtc_outbound_updated_at_ms,
        shadow_runtime_ready: state.shadow_runtime_ready,
        shadow_track_bound: state.shadow_track_bound,
        shadow_last_apply_action: state.shadow_last_apply_action.clone(),
        last_error_code: state.last_error_code.clone(),
        last_error_detail: state.last_error_detail.clone(),
        updated_at_ms: state.updated_at_ms,
    }
}

fn reset_media_probe_fields(state: &mut NativeSenderRuntimeState) {
    state.media_probe_running = false;
    state.media_probe_frame_count = 0;
    state.media_probe_total_bytes = 0;
    state.media_probe_last_frame_ts_ms = 0;
    state.media_probe_last_width = 0;
    state.media_probe_last_height = 0;
    state.media_probe_fps = 0.0;
    state.media_probe_kbps = 0.0;
}

fn reset_webrtc_outbound_fields(state: &mut NativeSenderRuntimeState) {
    state.webrtc_outbound_stats_available = false;
    state.webrtc_outbound_reports = 0;
    state.webrtc_outbound_bytes_sent = 0;
    state.webrtc_outbound_packets_sent = 0;
    state.webrtc_outbound_header_bytes_sent = 0;
    state.webrtc_outbound_kbps = 0.0;
    state.webrtc_outbound_fps = 0.0;
    state.webrtc_outbound_rtt_ms = -1.0;
    state.webrtc_outbound_updated_at_ms = 0;
}

fn extract_candidate_type(candidate: &str) -> String {
    let text = candidate.trim();
    if text.is_empty() {
        return "-".to_string();
    }
    if let Some(index) = text.find(" typ ") {
        let start = index + 5;
        if start >= text.len() {
            return "-".to_string();
        }
        let tail = &text[start..];
        let value = tail.split_whitespace().next().unwrap_or("").trim();
        if value.is_empty() {
            "-".to_string()
        } else {
            value.to_string()
        }
    } else {
        "-".to_string()
    }
}

fn extract_candidate_protocol(candidate: &str) -> String {
    let text = candidate.trim();
    if text.is_empty() {
        return "-".to_string();
    }
    let normalized = text.strip_prefix("candidate:").unwrap_or(text);
    let mut iter = normalized.split_whitespace();
    let _foundation = iter.next();
    let _component = iter.next();
    let protocol = iter.next().unwrap_or("").trim().to_ascii_lowercase();
    if protocol.is_empty() {
        "-".to_string()
    } else {
        protocol
    }
}

fn classify_candidate_tier(local_type: &str, remote_type: &str, protocol: &str) -> String {
    let local = local_type.trim().to_ascii_lowercase();
    let remote = remote_type.trim().to_ascii_lowercase();
    let proto = protocol.trim().to_ascii_lowercase();
    let has_relay = local == "relay" || remote == "relay";
    if proto == "udp" && has_relay {
        return "relay_udp".to_string();
    }
    if proto == "udp" {
        return "p2p_udp".to_string();
    }
    if proto == "tcp" && has_relay {
        return "relay_tcp".to_string();
    }
    if proto == "tcp" {
        return "p2p_tcp".to_string();
    }
    if has_relay {
        return "relay_other".to_string();
    }
    if !local.is_empty() || !remote.is_empty() {
        return "p2p_other".to_string();
    }
    "-".to_string()
}

fn refresh_candidate_route(state: &mut NativeSenderRuntimeState) {
    let local_type = state.last_local_candidate_type.trim();
    let remote_type = state.last_remote_candidate_type.trim();
    let protocol = if !state.last_local_candidate_protocol.trim().is_empty()
        && state.last_local_candidate_protocol.trim() != "-"
    {
        state.last_local_candidate_protocol.trim()
    } else {
        state.last_remote_candidate_protocol.trim()
    };

    let mut parts: Vec<String> = Vec::new();
    if !local_type.is_empty() && local_type != "-" {
        parts.push(local_type.to_string());
    }
    if !remote_type.is_empty() && remote_type != "-" {
        parts.push(remote_type.to_string());
    }
    if !protocol.is_empty() && protocol != "-" {
        parts.push(protocol.to_string());
    }
    state.candidate_path = if parts.is_empty() {
        "-".to_string()
    } else {
        parts.join("/")
    };
    state.candidate_tier = classify_candidate_tier(local_type, remote_type, protocol);
}

fn normalize_signal_direction(direction: &str) -> &'static str {
    match direction.trim() {
        "outbound" | "out" | "local" => "outbound",
        _ => "inbound",
    }
}

fn build_h264_encoder(target_fps: u16) -> Result<Encoder, String> {
    let fps_u16 = target_fps.max(1);
    let fps = fps_u16 as f32;
    let intra_period = IntraFramePeriod::from_num_frames(fps_u16.max(10) as u32);
    let config = EncoderConfig::new()
        .usage_type(UsageType::ScreenContentRealTime)
        .rate_control_mode(RateControlMode::Bitrate)
        .profile(Profile::Baseline)
        .bitrate(BitRate::from_bps(2_500_000))
        .max_frame_rate(FrameRate::from_hz(fps))
        .intra_frame_period(intra_period)
        .skip_frames(false);
    Encoder::with_api_config(openh264::OpenH264API::from_source(), config)
        .map_err(|error| format!("native sender build h264 encoder failed: {error}"))
}

fn crop_bgra_even(frame: &crate::capture::CaptureFrameBytes) -> Result<(Vec<u8>, u32, u32), String> {
    let width = frame.frame_width;
    let height = frame.frame_height;
    let target_width = width - (width % 2);
    let target_height = height - (height % 2);
    if target_width == 0 || target_height == 0 {
        return Err(format!(
            "native sender bgra frame size too small for YUV420: {}x{}",
            width, height
        ));
    }
    let expected_len = width as usize * height as usize * 4;
    if frame.encoded_bytes.len() != expected_len {
        return Err(format!(
            "native sender invalid BGRA byte length: got={}, expected={} for {}x{}",
            frame.encoded_bytes.len(),
            expected_len,
            width,
            height
        ));
    }
    if target_width == width && target_height == height {
        return Ok((frame.encoded_bytes.clone(), width, height));
    }
    let src_row_bytes = width as usize * 4;
    let dst_row_bytes = target_width as usize * 4;
    let mut cropped = vec![0_u8; target_width as usize * target_height as usize * 4];
    for y in 0..target_height as usize {
        let src_offset = y * src_row_bytes;
        let dst_offset = y * dst_row_bytes;
        cropped[dst_offset..dst_offset + dst_row_bytes]
            .copy_from_slice(&frame.encoded_bytes[src_offset..src_offset + dst_row_bytes]);
    }
    Ok((cropped, target_width, target_height))
}

fn crop_rgb_even(rgb_bytes: &[u8], width: u32, height: u32) -> Result<(Vec<u8>, u32, u32), String> {
    let target_width = width - (width % 2);
    let target_height = height - (height % 2);
    if target_width == 0 || target_height == 0 {
        return Err(format!(
            "native sender rgb frame size too small for YUV420: {}x{}",
            width, height
        ));
    }
    let expected_len = width as usize * height as usize * 3;
    if rgb_bytes.len() != expected_len {
        return Err(format!(
            "native sender invalid RGB byte length: got={}, expected={} for {}x{}",
            rgb_bytes.len(),
            expected_len,
            width,
            height
        ));
    }
    if target_width == width && target_height == height {
        return Ok((rgb_bytes.to_vec(), width, height));
    }
    let src_row_bytes = width as usize * 3;
    let dst_row_bytes = target_width as usize * 3;
    let mut cropped = vec![0_u8; target_width as usize * target_height as usize * 3];
    for y in 0..target_height as usize {
        let src_offset = y * src_row_bytes;
        let dst_offset = y * dst_row_bytes;
        cropped[dst_offset..dst_offset + dst_row_bytes]
            .copy_from_slice(&rgb_bytes[src_offset..src_offset + dst_row_bytes]);
    }
    Ok((cropped, target_width, target_height))
}

fn decode_jpeg_to_rgb(frame: &crate::capture::CaptureFrameBytes) -> Result<(Vec<u8>, u32, u32), String> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(&frame.encoded_bytes));
    let decoded = decoder
        .decode()
        .map_err(|error| format!("native sender jpeg decode failed: {error}"))?;
    let info = decoder
        .info()
        .ok_or_else(|| "native sender jpeg decoder returned no image info".to_string())?;
    let width = u32::from(info.width);
    let height = u32::from(info.height);
    if info.pixel_format != jpeg_decoder::PixelFormat::RGB24 {
        return Err(format!(
            "native sender jpeg pixel format unsupported: {:?}",
            info.pixel_format
        ));
    }
    crop_rgb_even(&decoded, width, height)
}

fn encode_h264_sample_from_capture_frame(
    encoder: &mut Encoder,
    frame: &crate::capture::CaptureFrameBytes,
) -> Result<(Vec<u8>, FrameType), String> {
    let yuv = if frame.mime_type == "application/x-rd-raw-bgra" {
        let (bgra_bytes, width, height) = crop_bgra_even(frame)?;
        let bgra = BgraSliceU8::new(&bgra_bytes, (width as usize, height as usize));
        YUVBuffer::from_rgb_source(bgra)
    } else if frame.mime_type.eq_ignore_ascii_case("image/jpeg") {
        let (rgb_bytes, width, height) = decode_jpeg_to_rgb(frame)?;
        let rgb = RgbSliceU8::new(&rgb_bytes, (width as usize, height as usize));
        YUVBuffer::from_rgb8_source(rgb)
    } else {
        return Err(format!(
            "native sender unsupported capture mime for h264 encoder: {}",
            frame.mime_type
        ));
    };
    let bitstream = encoder
        .encode(&yuv)
        .map_err(|error| format!("native sender h264 encode failed: {error}"))?;
    let frame_type = bitstream.frame_type();
    let encoded = bitstream.to_vec();
    if encoded.is_empty() {
        return Err("native sender h264 encode returned empty bitstream".to_string());
    }
    Ok((encoded, frame_type))
}

fn shadow_video_track_for_session(session_id: &str) -> Option<Arc<TrackLocalStaticSample>> {
    native_sender_webrtc_runtime()
        .lock()
        .ok()
        .and_then(|runtime| {
            if runtime.session_id == session_id {
                runtime.video_track.clone()
            } else {
                None
            }
        })
}

fn collect_webrtc_outbound_rtp_snapshot(
    session_id: &str,
) -> Result<NativeSenderOutboundRtpSnapshot, String> {
    let peer_connection = {
        let runtime = native_sender_webrtc_runtime()
            .lock()
            .map_err(|_| "native sender webrtc runtime state poisoned".to_string())?;
        if runtime.session_id != session_id {
            return Err(format!(
                "native sender webrtc session mismatch while collecting stats: runtime={} requested={}",
                runtime.session_id, session_id
            ));
        }
        runtime
            .peer_connection
            .clone()
            .ok_or_else(|| "native sender peer connection runtime unavailable".to_string())?
    };
    let report = tauri::async_runtime::block_on(peer_connection.get_stats());
    let mut snapshot = NativeSenderOutboundRtpSnapshot::default();

    for item in report.reports.values() {
        match item {
            StatsReportType::OutboundRTP(stats) => {
                if stats.kind == "video" {
                    snapshot.report_count = snapshot.report_count.saturating_add(1);
                    snapshot.bytes_sent = snapshot.bytes_sent.saturating_add(stats.bytes_sent);
                    snapshot.packets_sent = snapshot.packets_sent.saturating_add(stats.packets_sent);
                    snapshot.header_bytes_sent = snapshot
                        .header_bytes_sent
                        .saturating_add(stats.header_bytes_sent);
                }
            }
            StatsReportType::RemoteInboundRTP(stats) => {
                if stats.kind == "video" {
                    if let Some(round_trip_time) = stats.round_trip_time {
                        if round_trip_time.is_finite() && round_trip_time >= 0.0 {
                            snapshot.round_trip_time_ms += round_trip_time * 1000.0;
                            snapshot.round_trip_time_samples =
                                snapshot.round_trip_time_samples.saturating_add(1);
                        }
                    }
                }
            }
            _ => {}
        }
    }

    if snapshot.round_trip_time_samples > 0 {
        snapshot.round_trip_time_ms /= f64::from(snapshot.round_trip_time_samples);
    } else {
        snapshot.round_trip_time_ms = -1.0;
    }
    Ok(snapshot)
}

fn map_ice_servers(servers: &[NativeSenderIceServer]) -> Vec<RTCIceServer> {
    let mut mapped = Vec::with_capacity(servers.len());
    for server in servers {
        let urls: Vec<String> = server
            .urls
            .iter()
            .map(|url| url.trim().to_string())
            .filter(|url| !url.is_empty())
            .collect();
        if urls.is_empty() {
            continue;
        }
        mapped.push(RTCIceServer {
            urls,
            username: server.username.trim().to_string(),
            credential: server.credential.trim().to_string(),
            credential_type: RTCIceCredentialType::Password,
        });
    }
    if mapped.is_empty() {
        mapped.push(RTCIceServer {
            urls: vec!["stun:stun.l.google.com:19302".to_string()],
            ..Default::default()
        });
    }
    mapped
}

fn enqueue_outbound_signal(
    session_id: &str,
    signal_type: &str,
    sdp: &str,
    candidate: &str,
    sdp_mid: &str,
    sdp_mline_index: i32,
) -> Option<String> {
    let mut runtime = match native_sender_webrtc_runtime().lock() {
        Ok(value) => value,
        Err(_) => return None,
    };
    if runtime.session_id != session_id {
        return None;
    }
    runtime.outbound_signal_seq = runtime.outbound_signal_seq.saturating_add(1);
    let trace_id = format!("ns-{}-{}", now_ms(), runtime.outbound_signal_seq);
    runtime.outbound_signals.push_back(NativeSenderOutgoingSignal {
        session_id: session_id.to_string(),
        signal_type: signal_type.to_string(),
        signal_direction: "outbound".to_string(),
        trace_id: trace_id.clone(),
        sdp: sdp.to_string(),
        candidate: candidate.to_string(),
        sdp_mid: sdp_mid.to_string(),
        sdp_mline_index,
    });
    while runtime.outbound_signals.len() > 1024 {
        runtime.outbound_signals.pop_front();
    }
    trace_native_sender(
        "webrtc.outbound_signal.queued",
        format!(
            "session={} type={} trace={} queue_len={}",
            session_id,
            signal_type,
            trace_id,
            runtime.outbound_signals.len()
        ),
    );
    drop(runtime);
    if let Ok(mut state) = native_sender_state().lock() {
        state.signal_count = state.signal_count.saturating_add(1);
        state.outbound_signal_count = state.outbound_signal_count.saturating_add(1);
        state.last_signal_type = signal_type.to_string();
        state.last_signal_direction = "outbound".to_string();
        state.last_signal_trace_id = trace_id.clone();
        state.last_signal_payload_bytes = (sdp.len() + candidate.len() + sdp_mid.len()) as u64;
        match signal_type {
            "webrtc.offer" => {
                state.local_offer_count = state.local_offer_count.saturating_add(1);
                state.signaling_state = "awaiting_remote_answer".to_string();
            }
            "webrtc.answer" => {
                state.local_answer_count = state.local_answer_count.saturating_add(1);
                state.signaling_state = "local_answer_sent".to_string();
            }
            "webrtc.ice_candidate" => {
                state.local_candidate_count = state.local_candidate_count.saturating_add(1);
            }
            "webrtc.restart_ice" => {
                state.restart_ice_count = state.restart_ice_count.saturating_add(1);
                state.local_restart_ice_count = state.local_restart_ice_count.saturating_add(1);
                state.signaling_state = "restart_ice_requested_local".to_string();
            }
            _ => {}
        }
        state.updated_at_ms = now_ms();
    }
    Some(trace_id)
}

fn create_shadow_peer_connection(
    session_id: &str,
    ice_servers: Vec<RTCIceServer>,
) -> Result<(Arc<RTCPeerConnection>, Arc<TrackLocalStaticSample>), String> {
    let mut media_engine = MediaEngine::default();
    media_engine
        .register_default_codecs()
        .map_err(|error| format!("native sender register_default_codecs failed: {error}"))?;
    let api = APIBuilder::new().with_media_engine(media_engine).build();
    let config = RTCConfiguration {
        ice_servers,
        ..Default::default()
    };
    let peer_connection = tauri::async_runtime::block_on(api.new_peer_connection(config))
        .map_err(|error| format!("native sender create_peer_connection failed: {error}"))?;
    let peer_connection = Arc::new(peer_connection);
    let session_for_callbacks = session_id.to_string();
    peer_connection.on_ice_candidate(Box::new(move |candidate| {
        let session_id = session_for_callbacks.clone();
        Box::pin(async move {
            let Some(candidate) = candidate else {
                trace_native_sender(
                    "webrtc.local_ice.gathering_complete",
                    format!("session={}", session_id),
                );
                return;
            };
            match candidate.to_json() {
                Ok(init) => {
                    let sdp_mid = init
                        .sdp_mid
                        .filter(|value| !value.trim().is_empty())
                        .unwrap_or_else(|| "0".to_string());
                    let sdp_mline_index = init
                        .sdp_mline_index
                        .map(i32::from)
                        .filter(|value| *value >= 0)
                        .unwrap_or(0);
                    let candidate_type = extract_candidate_type(init.candidate.as_str());
                    let candidate_protocol = extract_candidate_protocol(init.candidate.as_str());
                    if let Ok(mut state) = native_sender_state().lock() {
                        if state.session_id == session_id {
                            state.last_local_candidate_type = candidate_type.clone();
                            state.last_local_candidate_protocol = candidate_protocol.clone();
                            refresh_candidate_route(&mut state);
                            state.updated_at_ms = now_ms();
                        }
                    }
                    enqueue_outbound_signal(
                        session_id.as_str(),
                        "webrtc.ice_candidate",
                        "",
                        init.candidate.as_str(),
                        sdp_mid.as_str(),
                        sdp_mline_index,
                    );
                    trace_native_sender(
                        "webrtc.local_ice.queued",
                        format!(
                            "session={} candidate_type={} protocol={} mid={} mline={}",
                            session_id, candidate_type, candidate_protocol, sdp_mid, sdp_mline_index
                        ),
                    );
                }
                Err(error) => {
                    trace_native_sender(
                        "webrtc.local_ice.to_json_failed",
                        format!("session={} detail={}", session_id, error),
                    );
                }
            }
        })
    }));
    let video_track = Arc::new(TrackLocalStaticSample::new(
        RTCRtpCodecCapability {
            mime_type: MIME_TYPE_H264.to_string(),
            clock_rate: 90_000,
            channels: 0,
            sdp_fmtp_line:
                "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"
                    .to_string(),
            ..Default::default()
        },
        "rd-native-video".to_string(),
        "rd-native-sender".to_string(),
    ));
    tauri::async_runtime::block_on(peer_connection.add_track(video_track.clone()))
        .map_err(|error| format!("native sender add_track(video/H264) failed: {error}"))?;
    Ok((peer_connection, video_track))
}

fn ensure_shadow_peer_connection(
    session_id: &str,
    ice_servers: &[NativeSenderIceServer],
) -> Result<(), String> {
    let mut runtime = native_sender_webrtc_runtime()
        .lock()
        .map_err(|_| "native sender webrtc runtime state poisoned".to_string())?;

    if runtime.session_id == session_id && runtime.peer_connection.is_some() {
        return Ok(());
    }

    if let Some(existing) = runtime.peer_connection.take() {
        if let Err(error) = tauri::async_runtime::block_on(existing.close()) {
            trace_native_sender(
                "webrtc.close_failed",
                format!("session={} detail={}", runtime.session_id, error),
            );
        }
    }

    let (peer_connection, video_track) =
        create_shadow_peer_connection(session_id, map_ice_servers(ice_servers))?;
    runtime.session_id = session_id.to_string();
    runtime.initialized_at_ms = now_ms();
    runtime.video_track = Some(video_track);
    runtime.peer_connection = Some(peer_connection);
    runtime.outbound_signals.clear();
    runtime.outbound_signal_seq = 0;
    if let Ok(mut state) = native_sender_state().lock() {
        state.shadow_runtime_ready = true;
        state.shadow_track_bound = true;
        state.updated_at_ms = now_ms();
    }
    trace_native_sender(
        "webrtc.runtime_ready",
        format!(
            "session={} initialized_at={} track=video/H264",
            runtime.session_id, runtime.initialized_at_ms
        ),
    );
    Ok(())
}

fn close_shadow_peer_connection(reason: &str) {
    let (session_id, peer_connection) = {
        let Ok(mut runtime) = native_sender_webrtc_runtime().lock() else {
            return;
        };
        let session_id = runtime.session_id.clone();
        let peer_connection = runtime.peer_connection.take();
        runtime.video_track.take();
        runtime.outbound_signals.clear();
        runtime.outbound_signal_seq = 0;
        runtime.session_id.clear();
        runtime.initialized_at_ms = 0;
        (session_id, peer_connection)
    };
    if let Ok(mut state) = native_sender_state().lock() {
        state.shadow_runtime_ready = false;
        state.shadow_track_bound = false;
        state.shadow_last_apply_action.clear();
        state.updated_at_ms = now_ms();
    }

    if let Some(existing) = peer_connection {
        if let Err(error) = tauri::async_runtime::block_on(existing.close()) {
            trace_native_sender(
                "webrtc.close_failed",
                format!("session={} reason={} detail={}", session_id, reason, error),
            );
        } else {
            trace_native_sender(
                "webrtc.closed",
                format!("session={} reason={}", session_id, reason),
            );
        }
    }
}

fn apply_description_to_peer_connection(
    peer_connection: &Arc<RTCPeerConnection>,
    signal_type: &str,
    signal_direction: &str,
    sdp: &str,
) -> Result<String, String> {
    let trimmed = sdp.trim();
    if trimmed.is_empty() {
        return Ok("skipped.empty_sdp".to_string());
    }

    let description = match signal_type {
        "webrtc.offer" => RTCSessionDescription::offer(trimmed.to_string()),
        "webrtc.answer" => RTCSessionDescription::answer(trimmed.to_string()),
        _ => return Ok("skipped.unsupported_signal_type".to_string()),
    }
    .map_err(|error| format!("native sender create_sdp failed: {error}"))?;

    match signal_direction {
        "outbound" => {
            tauri::async_runtime::block_on(peer_connection.set_local_description(description))
                .map_err(|error| format!("native sender set_local_description failed: {error}"))?;
            Ok("set_local_description".to_string())
        }
        _ => {
            tauri::async_runtime::block_on(peer_connection.set_remote_description(description))
                .map_err(|error| format!("native sender set_remote_description failed: {error}"))?;
            Ok("set_remote_description".to_string())
        }
    }
}

fn apply_candidate_to_peer_connection(
    peer_connection: &Arc<RTCPeerConnection>,
    candidate: &str,
    sdp_mid: &str,
    sdp_mline_index: i32,
) -> Result<String, String> {
    let trimmed = candidate.trim();
    if trimmed.is_empty() {
        return Ok("skipped.empty_candidate".to_string());
    }
    let init = RTCIceCandidateInit {
        candidate: trimmed.to_string(),
        sdp_mid: if sdp_mid.trim().is_empty() {
            None
        } else {
            Some(sdp_mid.trim().to_string())
        },
        sdp_mline_index: if sdp_mline_index >= 0 {
            Some(sdp_mline_index as u16)
        } else {
            None
        },
        username_fragment: None,
    };
    tauri::async_runtime::block_on(peer_connection.add_ice_candidate(init))
        .map_err(|error| format!("native sender add_ice_candidate failed: {error}"))?;
    Ok("add_ice_candidate".to_string())
}

fn apply_signal_to_shadow_peer_connection(
    session_id: &str,
    signal_type: &str,
    signal_direction: &str,
    sdp: &str,
    candidate: &str,
    sdp_mid: &str,
    sdp_mline_index: i32,
) -> Result<String, String> {
    ensure_shadow_peer_connection(session_id, &[])?;
    let peer_connection = {
        let runtime = native_sender_webrtc_runtime()
            .lock()
            .map_err(|_| "native sender webrtc runtime state poisoned".to_string())?;
        runtime
            .peer_connection
            .clone()
            .ok_or_else(|| "native sender peer connection runtime unavailable".to_string())?
    };

    let action = match signal_type {
        "webrtc.offer" | "webrtc.answer" => apply_description_to_peer_connection(
            &peer_connection,
            signal_type,
            signal_direction,
            sdp,
        )?,
        "webrtc.ice_candidate" => apply_candidate_to_peer_connection(
            &peer_connection,
            candidate,
            sdp_mid,
            sdp_mline_index,
        )?,
        "webrtc.restart_ice" => "observed.restart_ice".to_string(),
        _ => "skipped.unsupported_signal".to_string(),
    };
    let signaling_state = peer_connection.signaling_state();
    trace_native_sender(
        "webrtc.signal_applied",
        format!(
            "session={} type={} direction={} action={} pc_signaling={}",
            session_id, signal_type, signal_direction, action, signaling_state
        ),
    );
    Ok(action)
}

fn create_and_queue_local_offer(session_id: &str, ice_restart: bool) -> Result<String, String> {
    let peer_connection = {
        let runtime = native_sender_webrtc_runtime()
            .lock()
            .map_err(|_| "native sender webrtc runtime state poisoned".to_string())?;
        if runtime.session_id != session_id {
            return Err(format!(
                "native sender session mismatch while creating offer: runtime={} requested={}",
                runtime.session_id, session_id
            ));
        }
        runtime
            .peer_connection
            .clone()
            .ok_or_else(|| "native sender peer connection runtime unavailable".to_string())?
    };
    let offer = tauri::async_runtime::block_on(peer_connection.create_offer(Some(
        RTCOfferOptions {
            ice_restart,
            voice_activity_detection: false,
        },
    )))
    .map_err(|error| format!("native sender create_offer failed: {error}"))?;
    let sdp = offer.sdp.clone();
    tauri::async_runtime::block_on(peer_connection.set_local_description(offer))
        .map_err(|error| format!("native sender set_local_description(offer) failed: {error}"))?;
    let trace_id = enqueue_outbound_signal(session_id, "webrtc.offer", sdp.as_str(), "", "", -1)
        .unwrap_or_else(|| "-".to_string());
    trace_native_sender(
        "webrtc.local_offer.queued",
        format!(
            "session={} trace={} ice_restart={} sdp_len={}",
            session_id,
            trace_id,
            if ice_restart { 1 } else { 0 },
            sdp.len()
        ),
    );
    Ok("queued.local_offer".to_string())
}

fn create_and_queue_local_answer(session_id: &str) -> Result<String, String> {
    let peer_connection = {
        let runtime = native_sender_webrtc_runtime()
            .lock()
            .map_err(|_| "native sender webrtc runtime state poisoned".to_string())?;
        if runtime.session_id != session_id {
            return Err(format!(
                "native sender session mismatch while creating answer: runtime={} requested={}",
                runtime.session_id, session_id
            ));
        }
        runtime
            .peer_connection
            .clone()
            .ok_or_else(|| "native sender peer connection runtime unavailable".to_string())?
    };
    let answer = tauri::async_runtime::block_on(peer_connection.create_answer(None))
        .map_err(|error| format!("native sender create_answer failed: {error}"))?;
    let sdp = answer.sdp.clone();
    tauri::async_runtime::block_on(peer_connection.set_local_description(answer))
        .map_err(|error| format!("native sender set_local_description(answer) failed: {error}"))?;
    let trace_id = enqueue_outbound_signal(session_id, "webrtc.answer", sdp.as_str(), "", "", -1)
        .unwrap_or_else(|| "-".to_string());
    trace_native_sender(
        "webrtc.local_answer.queued",
        format!("session={} trace={} sdp_len={}", session_id, trace_id, sdp.len()),
    );
    Ok("queued.local_answer".to_string())
}

fn stop_native_sender_worker() {
    let (stop_tx, handle) = {
        let Ok(mut worker) = native_sender_worker_state().lock() else {
            return;
        };
        (worker.stop_tx.take(), worker.handle.take())
    };
    if let Some(tx) = stop_tx {
        let _ = tx.send(());
    }
    if let Some(handle) = handle {
        let _ = handle.join();
    }
}

fn start_native_sender_worker(session_id: String) -> Result<(), String> {
    stop_native_sender_worker();
    let (stop_tx, stop_rx) = mpsc::channel::<()>();
    let session_for_thread = session_id;
    let handle = thread::Builder::new()
        .name("rd-native-sender-probe".to_string())
        .spawn(move || {
            let mut sample_frames = 0_u64;
            let mut sample_bytes = 0_u64;
            let mut sample_captured_frames = 0_u64;
            let mut sample_captured_bytes = 0_u64;
            let mut sample_started_at = now_ms();
            let mut first_frame_logged = false;
            let mut h264_encoder: Option<Encoder> = None;
            let mut published_frames = 0_u64;
            let mut last_publish_error_ts = 0_u64;
            let mut force_intra_counter = 0_u64;
            let mut loop_wait_ms = 0_u64;
            let mut loop_target_interval_ms = NATIVE_SENDER_PROBE_DEFAULT_INTERVAL_MS;
            let mut last_webrtc_bytes_sent = 0_u64;
            let mut last_webrtc_packets_sent = 0_u64;
            let mut last_published_frames = 0_u64;
            let mut webrtc_sample_ready = false;

            loop {
                match stop_rx.recv_timeout(std::time::Duration::from_millis(loop_wait_ms)) {
                    Ok(_) => break,
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                }
                let loop_started_at = now_ms();

                let now = now_ms();
                let active_session = native_sender_state()
                    .lock()
                    .ok()
                    .map(|state| state.session_id.clone())
                    .unwrap_or_default();
                if active_session != session_for_thread {
                    break;
                }

                match crate::capture::capture_take_frame_bytes() {
                    Ok(frame) => {
                        let frame_bytes = frame.encoded_bytes.len() as u64;
                        sample_captured_frames = sample_captured_frames.saturating_add(1);
                        sample_captured_bytes = sample_captured_bytes.saturating_add(frame_bytes);

                        if let Ok(mut state) = native_sender_state().lock() {
                            state.media_probe_running = true;
                            state.media_probe_last_frame_ts_ms = frame.capture_ts;
                            state.media_probe_last_width = frame.frame_width;
                            state.media_probe_last_height = frame.frame_height;
                            state.updated_at_ms = now;
                            if state.last_error_code == "native_sender.probe.capture_failed" {
                                state.last_error_code.clear();
                                state.last_error_detail.clear();
                            }
                        }

                        if !first_frame_logged {
                            first_frame_logged = true;
                            trace_native_sender(
                                "probe.first_frame",
                                format!(
                                    "session={} size={}x{} ts={}",
                                    session_for_thread,
                                    frame.frame_width,
                                    frame.frame_height,
                                    frame.capture_ts
                                ),
                            );
                        }

                        let maybe_track = shadow_video_track_for_session(session_for_thread.as_str());
                        if let Some(track) = maybe_track {
                            let capture_fps = crate::capture::capture_status()
                                .ok()
                                .map(|status| status.config.max_fps.max(1))
                                .unwrap_or(24);
                            loop_target_interval_ms =
                                target_loop_interval_ms(u32::from(capture_fps));
                            let frame_duration_ms = loop_target_interval_ms.max(1);
                            if h264_encoder.is_none() {
                                match build_h264_encoder(capture_fps) {
                                    Ok(encoder) => {
                                        h264_encoder = Some(encoder);
                                        trace_native_sender(
                                            "encoder.ready",
                                            format!(
                                                "session={} mime=video/H264 capture_fps={}",
                                                session_for_thread, capture_fps
                                            ),
                                        );
                                    }
                                    Err(detail) => {
                                        if let Ok(mut state) = native_sender_state().lock() {
                                            state.last_error_code = "native_sender.encoder.init_failed".to_string();
                                            state.last_error_detail = detail.clone();
                                            state.updated_at_ms = now;
                                        }
                                    }
                                }
                            }

                            if let Some(encoder) = h264_encoder.as_mut() {
                                if force_intra_counter % NATIVE_SENDER_FORCE_INTRA_INTERVAL_FRAMES
                                    == 0
                                {
                                    encoder.force_intra_frame();
                                }
                                force_intra_counter = force_intra_counter.saturating_add(1);
                                let encoded = encode_h264_sample_from_capture_frame(encoder, &frame);
                                match encoded {
                                    Ok((bitstream, frame_type)) => {
                                        let mut sample = webrtc::media::Sample::default();
                                        let sample_len_bytes = bitstream.len() as u64;
                                        sample.data = bitstream.into();
                                        sample.duration = Duration::from_millis(frame_duration_ms);
                                        let write_result = tauri::async_runtime::block_on(
                                            track.write_sample(&sample),
                                        );
                                        match write_result {
                                            Ok(()) => {
                                                published_frames = published_frames.saturating_add(1);
                                                sample_frames = sample_frames.saturating_add(1);
                                                sample_bytes = sample_bytes.saturating_add(sample_len_bytes);
                                                if let Ok(mut state) = native_sender_state().lock() {
                                                    state.media_probe_frame_count =
                                                        state.media_probe_frame_count.saturating_add(1);
                                                    state.media_probe_total_bytes =
                                                        state.media_probe_total_bytes.saturating_add(sample_len_bytes);
                                                    if state.last_error_code.starts_with("native_sender.encoder.") {
                                                        state.last_error_code.clear();
                                                        state.last_error_detail.clear();
                                                    }
                                                    state.updated_at_ms = now;
                                                }
                                                if published_frames == 1 || published_frames % 60 == 0 {
                                                    trace_native_sender(
                                                        "encoder.sample_published",
                                                        format!(
                                                            "session={} count={} frame={}x{} duration_ms={} mime={} frame_type={:?}",
                                                            session_for_thread,
                                                            published_frames,
                                                            frame.frame_width,
                                                            frame.frame_height,
                                                            sample.duration.as_millis(),
                                                            frame.mime_type,
                                                            frame_type
                                                        ),
                                                    );
                                                }
                                            }
                                            Err(error) => {
                                                if let Ok(mut state) = native_sender_state().lock() {
                                                    state.last_error_code =
                                                        "native_sender.encoder.write_sample_failed".to_string();
                                                    state.last_error_detail = error.to_string();
                                                    state.updated_at_ms = now;
                                                }
                                            }
                                        }
                                    }
                                    Err(detail) => {
                                        if now.saturating_sub(last_publish_error_ts) >= 1200 {
                                            last_publish_error_ts = now;
                                            trace_native_sender(
                                                "encoder.frame_rejected",
                                                format!(
                                                    "session={} mime={} detail={}",
                                                    session_for_thread, frame.mime_type, detail
                                                ),
                                            );
                                        }
                                        if let Ok(mut state) = native_sender_state().lock() {
                                            state.last_error_code = "native_sender.encoder.encode_failed".to_string();
                                            state.last_error_detail = detail;
                                            state.updated_at_ms = now;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Err(detail) => {
                        if let Ok(mut state) = native_sender_state().lock() {
                            state.media_probe_running = true;
                            state.last_error_code =
                                "native_sender.probe.capture_failed".to_string();
                            state.last_error_detail = detail.clone();
                            state.updated_at_ms = now;
                        }
                    }
                }

                let elapsed = now.saturating_sub(sample_started_at);
                if elapsed >= NATIVE_SENDER_PROBE_SAMPLE_WINDOW_MS {
                    let fps = if elapsed > 0 {
                        sample_frames as f64 * 1000.0 / elapsed as f64
                    } else {
                        0.0
                    };
                    let kbps = if elapsed > 0 {
                        sample_bytes as f64 * 8.0 / elapsed as f64
                    } else {
                        0.0
                    };
                    if let Ok(mut state) = native_sender_state().lock() {
                        state.media_probe_fps = fps;
                        state.media_probe_kbps = kbps;
                        state.updated_at_ms = now;
                    }

                    let outbound_sample_log = match collect_webrtc_outbound_rtp_snapshot(
                        session_for_thread.as_str(),
                    ) {
                        Ok(webrtc_snapshot) => {
                            let has_outbound = webrtc_snapshot.report_count > 0;
                            let delta_bytes = if webrtc_sample_ready && has_outbound {
                                webrtc_snapshot
                                    .bytes_sent
                                    .saturating_sub(last_webrtc_bytes_sent)
                            } else {
                                0
                            };
                            let delta_packets = if webrtc_sample_ready && has_outbound {
                                webrtc_snapshot
                                    .packets_sent
                                    .saturating_sub(last_webrtc_packets_sent)
                            } else {
                                0
                            };
                            let delta_frames = if webrtc_sample_ready && has_outbound {
                                published_frames.saturating_sub(last_published_frames)
                            } else {
                                0
                            };
                            let outbound_kbps = if webrtc_sample_ready && has_outbound && elapsed > 0 {
                                delta_bytes as f64 * 8.0 / elapsed as f64
                            } else {
                                0.0
                            };
                            let outbound_fps = if webrtc_sample_ready && has_outbound && elapsed > 0 {
                                delta_frames as f64 * 1000.0 / elapsed as f64
                            } else {
                                0.0
                            };
                            if let Ok(mut state) = native_sender_state().lock() {
                                state.webrtc_outbound_stats_available = has_outbound;
                                state.webrtc_outbound_reports = webrtc_snapshot.report_count;
                                state.webrtc_outbound_bytes_sent = webrtc_snapshot.bytes_sent;
                                state.webrtc_outbound_packets_sent = webrtc_snapshot.packets_sent;
                                state.webrtc_outbound_header_bytes_sent =
                                    webrtc_snapshot.header_bytes_sent;
                                state.webrtc_outbound_kbps = outbound_kbps;
                                state.webrtc_outbound_fps = outbound_fps;
                                state.webrtc_outbound_rtt_ms = webrtc_snapshot.round_trip_time_ms;
                                state.webrtc_outbound_updated_at_ms = now;
                                state.updated_at_ms = now;
                            }
                            if has_outbound {
                                webrtc_sample_ready = true;
                                last_webrtc_bytes_sent = webrtc_snapshot.bytes_sent;
                                last_webrtc_packets_sent = webrtc_snapshot.packets_sent;
                                last_published_frames = published_frames;
                            } else {
                                webrtc_sample_ready = false;
                                last_webrtc_bytes_sent = 0;
                                last_webrtc_packets_sent = 0;
                                last_published_frames = 0;
                            }
                            format!(
                                "webrtc_stats=ok reports={} bytes_total={} packets_total={} delta_bytes={} delta_packets={} delta_frames={} outbound_kbps={:.1} outbound_fps={:.2} rtt_ms={:.1}",
                                webrtc_snapshot.report_count,
                                webrtc_snapshot.bytes_sent,
                                webrtc_snapshot.packets_sent,
                                delta_bytes,
                                delta_packets,
                                delta_frames,
                                outbound_kbps,
                                outbound_fps,
                                webrtc_snapshot.round_trip_time_ms
                            )
                        }
                        Err(detail) => {
                            webrtc_sample_ready = false;
                            if let Ok(mut state) = native_sender_state().lock() {
                                reset_webrtc_outbound_fields(&mut state);
                                state.updated_at_ms = now;
                            }
                            format!("webrtc_stats=error detail={}", detail.replace('\n', " "))
                        }
                    };
                    trace_native_sender(
                        "probe.sample",
                        format!(
                            "session={} fps={:.2} kbps={:.1} frames={} bytes={} captured_frames={} captured_bytes={} {}",
                            session_for_thread,
                            fps,
                            kbps,
                            sample_frames,
                            sample_bytes,
                            sample_captured_frames,
                            sample_captured_bytes,
                            outbound_sample_log
                        ),
                    );
                    sample_frames = 0;
                    sample_bytes = 0;
                    sample_captured_frames = 0;
                    sample_captured_bytes = 0;
                    sample_started_at = now;
                }
                let loop_elapsed_ms = now_ms().saturating_sub(loop_started_at);
                loop_wait_ms = loop_target_interval_ms.saturating_sub(loop_elapsed_ms);
            }

            if let Ok(mut state) = native_sender_state().lock() {
                state.media_probe_running = false;
                reset_webrtc_outbound_fields(&mut state);
                state.updated_at_ms = now_ms();
            }
            trace_native_sender("probe.stopped", format!("session={session_for_thread}"));
        })
        .map_err(|error| format!("failed to spawn native sender probe worker: {error}"))?;

    let mut worker = native_sender_worker_state()
        .lock()
        .map_err(|_| "native sender worker state poisoned".to_string())?;
    worker.stop_tx = Some(stop_tx);
    worker.handle = Some(handle);
    Ok(())
}

pub fn native_sender_get_capabilities() -> NativeSenderCapabilities {
    current_capabilities()
}

pub fn native_sender_status() -> NativeSenderStatus {
    let guard = native_sender_state()
        .lock()
        .expect("native sender state should not be poisoned");
    snapshot_status(&guard)
}

pub fn native_sender_start(
    request: NativeSenderStartRequest,
) -> Result<NativeSenderStatus, String> {
    let session_id = request.session_id.trim();
    if session_id.is_empty() {
        return Err("native sender requires a non-empty session_id".to_string());
    }

    stop_native_sender_worker();
    let capabilities = current_capabilities();
    let mut guard = native_sender_state()
        .lock()
        .map_err(|_| "native sender state poisoned".to_string())?;

    guard.session_id = session_id.to_string();
    guard.dry_run = request.dry_run;
    guard.signaling_state = "awaiting_remote_answer".to_string();
    guard.started_at_ms = now_ms();
    guard.updated_at_ms = guard.started_at_ms;
    guard.signal_count = 0;
    guard.last_signal_type.clear();
    guard.last_signal_direction.clear();
    guard.last_signal_trace_id.clear();
    guard.last_signal_payload_bytes = 0;
    guard.inbound_signal_count = 0;
    guard.outbound_signal_count = 0;
    guard.local_offer_count = 0;
    guard.local_answer_count = 0;
    guard.local_candidate_count = 0;
    guard.remote_offer_count = 0;
    guard.remote_answer_count = 0;
    guard.remote_candidate_count = 0;
    guard.restart_ice_count = 0;
    guard.local_restart_ice_count = 0;
    guard.remote_restart_ice_count = 0;
    guard.remote_answer_sdp_len = 0;
    guard.remote_offer_sdp_len = 0;
    guard.last_local_candidate_type = "-".to_string();
    guard.last_local_candidate_protocol = "-".to_string();
    guard.last_remote_candidate_type = "-".to_string();
    guard.last_remote_candidate_protocol = "-".to_string();
    guard.candidate_path = "-".to_string();
    guard.candidate_tier = "-".to_string();
    guard.shadow_runtime_ready = false;
    guard.shadow_track_bound = false;
    guard.shadow_last_apply_action.clear();
    reset_media_probe_fields(&mut guard);
    reset_webrtc_outbound_fields(&mut guard);

    if capabilities.supported || request.dry_run {
        guard.lifecycle = "running".to_string();
        guard.last_error_code.clear();
        guard.last_error_detail.clear();
        trace_native_sender(
            "start.ok",
            format!("session={} dry_run={}", guard.session_id, request.dry_run),
        );
        let session = guard.session_id.clone();
        drop(guard);
        if let Err(detail) = ensure_shadow_peer_connection(session.as_str(), &request.ice_servers) {
            if let Ok(mut state) = native_sender_state().lock() {
                state.last_error_code =
                    "native_sender.peer_connection_runtime_init_failed".to_string();
                state.last_error_detail = detail.clone();
                state.updated_at_ms = now_ms();
            }
            trace_native_sender(
                "webrtc.runtime_init_failed",
                format!("session={} detail={}", session, detail),
            );
            return Err(format!("native sender runtime init failed: {detail}"));
        }
        if let Err(detail) = start_native_sender_worker(session.clone()) {
            if let Ok(mut state) = native_sender_state().lock() {
                state.last_error_code = "native_sender.probe.start_failed".to_string();
                state.last_error_detail = detail.clone();
                state.updated_at_ms = now_ms();
            }
            trace_native_sender(
                "probe.start_failed",
                format!("session={session} detail={detail}"),
            );
        } else {
            trace_native_sender("probe.start_ok", format!("session={session}"));
        }
        if !request.dry_run {
            if let Err(detail) = create_and_queue_local_offer(session.as_str(), false) {
                if let Ok(mut state) = native_sender_state().lock() {
                    state.last_error_code = "native_sender.local_offer_create_failed".to_string();
                    state.last_error_detail = detail.clone();
                    state.updated_at_ms = now_ms();
                }
                trace_native_sender(
                    "webrtc.local_offer.create_failed",
                    format!("session={} detail={}", session, detail),
                );
                return Err(format!("native sender initial offer failed: {detail}"));
            }
        }
        let guard = native_sender_state()
            .lock()
            .map_err(|_| "native sender state poisoned".to_string())?;
        return Ok(snapshot_status(&guard));
    }

    guard.lifecycle = "blocked".to_string();
    guard.last_error_code = capabilities.blocker_code.clone();
    guard.last_error_detail = capabilities.support_detail.clone();
    trace_native_sender(
        "start.blocked",
        format!(
            "session={} blocker={} detail={}",
            guard.session_id, guard.last_error_code, guard.last_error_detail
        ),
    );
    Err(format!(
        "native sender is blocked: {} ({})",
        guard.last_error_code, guard.last_error_detail
    ))
}

pub fn native_sender_stop(reason: Option<String>) -> Result<NativeSenderStatus, String> {
    stop_native_sender_worker();
    let stop_reason = reason.unwrap_or_else(|| "manual".to_string());
    close_shadow_peer_connection(stop_reason.as_str());
    let mut guard = native_sender_state()
        .lock()
        .map_err(|_| "native sender state poisoned".to_string())?;

    guard.lifecycle = "idle".to_string();
    guard.signaling_state = "idle".to_string();
    guard.session_id.clear();
    guard.stopped_at_ms = now_ms();
    guard.updated_at_ms = guard.stopped_at_ms;
    guard.signal_count = 0;
    guard.last_signal_type.clear();
    guard.last_signal_direction.clear();
    guard.last_signal_trace_id.clear();
    guard.last_signal_payload_bytes = 0;
    guard.inbound_signal_count = 0;
    guard.outbound_signal_count = 0;
    guard.local_offer_count = 0;
    guard.local_answer_count = 0;
    guard.local_candidate_count = 0;
    guard.remote_offer_count = 0;
    guard.remote_answer_count = 0;
    guard.remote_candidate_count = 0;
    guard.restart_ice_count = 0;
    guard.local_restart_ice_count = 0;
    guard.remote_restart_ice_count = 0;
    guard.remote_answer_sdp_len = 0;
    guard.remote_offer_sdp_len = 0;
    guard.last_local_candidate_type = "-".to_string();
    guard.last_local_candidate_protocol = "-".to_string();
    guard.last_remote_candidate_type = "-".to_string();
    guard.last_remote_candidate_protocol = "-".to_string();
    guard.candidate_path = "-".to_string();
    guard.candidate_tier = "-".to_string();
    guard.shadow_runtime_ready = false;
    guard.shadow_track_bound = false;
    guard.shadow_last_apply_action.clear();
    guard.dry_run = false;
    reset_media_probe_fields(&mut guard);
    reset_webrtc_outbound_fields(&mut guard);
    guard.last_error_code.clear();
    guard.last_error_detail.clear();

    trace_native_sender("stop.ok", format!("reason={}", stop_reason));
    Ok(snapshot_status(&guard))
}

pub fn native_sender_push_signal(
    signal: NativeSenderSignalEnvelope,
) -> Result<NativeSenderStatus, String> {
    let session_id = signal.session_id.trim();
    let signal_type = signal.signal_type.trim();
    if session_id.is_empty() || signal_type.is_empty() {
        return Err(
            "native sender signal requires non-empty session_id and signal_type".to_string(),
        );
    }

    let mut guard = native_sender_state()
        .lock()
        .map_err(|_| "native sender state poisoned".to_string())?;

    if !guard.session_id.is_empty() && guard.session_id != session_id {
        return Err(format!(
            "native sender session mismatch: running={} incoming={}",
            guard.session_id, session_id
        ));
    }
    if guard.session_id.is_empty() {
        guard.session_id = session_id.to_string();
    }
    let sdp = signal.sdp.trim();
    let candidate = signal.candidate.trim();
    let sdp_mid = signal.sdp_mid.trim();
    let payload_bytes = sdp.len() as u64 + candidate.len() as u64 + sdp_mid.len() as u64;
    let signal_direction = normalize_signal_direction(signal.signal_direction.as_str());
    guard.last_signal_type = signal_type.to_string();
    guard.last_signal_direction = signal_direction.to_string();
    guard.last_signal_trace_id = signal.trace_id.clone();
    guard.last_signal_payload_bytes = payload_bytes;
    guard.signal_count = guard.signal_count.saturating_add(1);
    if signal_direction == "outbound" {
        guard.outbound_signal_count = guard.outbound_signal_count.saturating_add(1);
    } else {
        guard.inbound_signal_count = guard.inbound_signal_count.saturating_add(1);
    }
    match signal_type {
        "webrtc.answer" => {
            if signal_direction == "outbound" {
                guard.local_answer_count = guard.local_answer_count.saturating_add(1);
                guard.signaling_state = "local_answer_sent".to_string();
            } else {
                guard.remote_answer_count = guard.remote_answer_count.saturating_add(1);
                guard.remote_answer_sdp_len = sdp.len();
                if guard.signaling_state == "awaiting_remote_answer" {
                    guard.signaling_state = "remote_answer_received".to_string();
                } else {
                    guard.signaling_state = "remote_answer_out_of_order".to_string();
                }
            }
        }
        "webrtc.ice_candidate" => {
            if signal_direction == "outbound" {
                guard.local_candidate_count = guard.local_candidate_count.saturating_add(1);
            } else {
                guard.remote_candidate_count = guard.remote_candidate_count.saturating_add(1);
                guard.last_remote_candidate_type = extract_candidate_type(candidate);
                guard.last_remote_candidate_protocol = extract_candidate_protocol(candidate);
                refresh_candidate_route(&mut guard);
                if guard.signaling_state == "awaiting_remote_answer" {
                    guard.signaling_state = "awaiting_remote_answer".to_string();
                } else if guard.signaling_state == "remote_answer_received" {
                    guard.signaling_state = "ice_syncing".to_string();
                }
            }
        }
        "webrtc.offer" => {
            if signal_direction == "outbound" {
                guard.local_offer_count = guard.local_offer_count.saturating_add(1);
                guard.signaling_state = "awaiting_remote_answer".to_string();
            } else {
                guard.remote_offer_count = guard.remote_offer_count.saturating_add(1);
                guard.remote_offer_sdp_len = sdp.len();
                guard.signaling_state = "remote_offer_received".to_string();
            }
        }
        "webrtc.restart_ice" => {
            guard.restart_ice_count = guard.restart_ice_count.saturating_add(1);
            if signal_direction == "outbound" {
                guard.local_restart_ice_count = guard.local_restart_ice_count.saturating_add(1);
                guard.signaling_state = "restart_ice_requested_local".to_string();
            } else {
                guard.remote_restart_ice_count = guard.remote_restart_ice_count.saturating_add(1);
                guard.signaling_state = "restart_ice_requested_remote".to_string();
            }
        }
        _ => {}
    }
    let candidate_type = if signal_type == "webrtc.ice_candidate" && signal_direction == "inbound" {
        guard.last_remote_candidate_type.clone()
    } else {
        "-".to_string()
    };
    guard.updated_at_ms = now_ms();
    let session_for_apply = guard.session_id.clone();
    let signal_type_for_apply = signal_type.to_string();
    let signal_direction_for_apply = signal_direction.to_string();
    let sdp_for_apply = sdp.to_string();
    let candidate_for_apply = candidate.to_string();
    let sdp_mid_for_apply = sdp_mid.to_string();
    let sdp_mline_index_for_apply = signal.sdp_mline_index;
    drop(guard);

    let apply_result = apply_signal_to_shadow_peer_connection(
        session_for_apply.as_str(),
        signal_type_for_apply.as_str(),
        signal_direction_for_apply.as_str(),
        sdp_for_apply.as_str(),
        candidate_for_apply.as_str(),
        sdp_mid_for_apply.as_str(),
        sdp_mline_index_for_apply,
    );

    let mut guard = native_sender_state()
        .lock()
        .map_err(|_| "native sender state poisoned".to_string())?;
    let (apply_action, apply_ok) = match apply_result {
        Ok(action) => {
            if guard.last_error_code == "native_sender.shadow_signal_apply_failed" {
                guard.last_error_code.clear();
                guard.last_error_detail.clear();
            }
            guard.shadow_runtime_ready = true;
            guard.shadow_track_bound = true;
            guard.shadow_last_apply_action = action.clone();
            (action, true)
        }
        Err(detail) => {
            guard.last_error_code = "native_sender.shadow_signal_apply_failed".to_string();
            guard.last_error_detail = detail;
            guard.shadow_last_apply_action = "failed.shadow_apply".to_string();
            ("failed.shadow_apply".to_string(), false)
        }
    };
    guard.updated_at_ms = now_ms();
    let should_auto_answer = guard.lifecycle == "running"
        && !guard.dry_run
        && signal_type == "webrtc.offer"
        && signal_direction == "inbound"
        && apply_ok;
    let session_for_auto_answer = guard.session_id.clone();
    trace_native_sender(
        "signal.accepted",
        format!(
            "session={} type={} direction={} count={} in={} out={} trace={} payload_bytes={} sdp_len={} candidate_type={} mid={} mline={} apply_action={} error={}",
            guard.session_id,
            signal_type,
            signal_direction,
            guard.signal_count,
            guard.inbound_signal_count,
            guard.outbound_signal_count,
            signal.trace_id,
            payload_bytes,
            sdp.len(),
            candidate_type,
            sdp_mid,
            signal.sdp_mline_index,
            apply_action,
            if guard.last_error_code.is_empty() {
                "-"
            } else {
                guard.last_error_code.as_str()
            }
        ),
    );
    drop(guard);
    if should_auto_answer {
        if let Err(detail) = create_and_queue_local_answer(session_for_auto_answer.as_str()) {
            if let Ok(mut state) = native_sender_state().lock() {
                state.last_error_code = "native_sender.local_answer_create_failed".to_string();
                state.last_error_detail = detail.clone();
                state.updated_at_ms = now_ms();
            }
            trace_native_sender(
                "webrtc.local_answer.create_failed",
                format!("session={} detail={}", session_for_auto_answer, detail),
            );
        }
    }
    let guard = native_sender_state()
        .lock()
        .map_err(|_| "native sender state poisoned".to_string())?;
    Ok(snapshot_status(&guard))
}

pub fn native_sender_create_offer(
    request: NativeSenderCreateOfferRequest,
) -> Result<NativeSenderStatus, String> {
    let session_id = request.session_id.trim();
    if session_id.is_empty() {
        return Err("native sender create_offer requires non-empty session_id".to_string());
    }
    create_and_queue_local_offer(session_id, request.ice_restart)?;
    let mut state = native_sender_state()
        .lock()
        .map_err(|_| "native sender state poisoned".to_string())?;
    state.signaling_state = "awaiting_remote_answer".to_string();
    state.updated_at_ms = now_ms();
    Ok(snapshot_status(&state))
}

pub fn native_sender_drain_outbound_signals(
    request: NativeSenderDrainSignalsRequest,
) -> Result<Vec<NativeSenderOutgoingSignal>, String> {
    let session_id = request.session_id.trim();
    if session_id.is_empty() {
        return Ok(Vec::new());
    }
    let limit = if request.limit == 0 {
        64
    } else {
        request.limit.min(512)
    };
    let mut runtime = native_sender_webrtc_runtime()
        .lock()
        .map_err(|_| "native sender webrtc runtime state poisoned".to_string())?;
    if runtime.session_id != session_id {
        return Ok(Vec::new());
    }
    let mut drained = Vec::new();
    while drained.len() < limit {
        let Some(item) = runtime.outbound_signals.pop_front() else {
            break;
        };
        drained.push(item);
    }
    Ok(drained)
}
