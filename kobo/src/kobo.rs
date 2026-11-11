//! The Kobo backend: the Linux framebuffer and the EPDC refresh ioctls, and evdev input.
//!
//! Everything about the screen is read at runtime: resolution, bit depth, stride, panning
//! offset and rotation. The framebuffer is left exactly as Nickel set it up (no mode change,
//! so nothing has to be restored); the reader rotates its picture in software instead.
//! Interface facts (ioctl numbers, struct layouts, key codes) come from the Linux headers and
//! the sources listed in README.md.

use crate::canvas::{Canvas, Rect};
use crate::config::Config;
use crate::device::{self, Display, Key, Phase, RawEvent, Refresh};
use crate::profile::{Panel, Profile, TouchQuirk};
use std::collections::VecDeque;
use std::ffi::CString;
use std::io;
use std::time::{Duration, Instant};

fn cvt(r: libc::c_int) -> io::Result<libc::c_int> {
    if r < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(r)
    }
}

fn open(path: &str, flags: libc::c_int) -> io::Result<libc::c_int> {
    let c = CString::new(path).map_err(io::Error::other)?;
    cvt(unsafe { libc::open(c.as_ptr(), flags | libc::O_CLOEXEC) })
}

unsafe fn ioctl_ptr(fd: libc::c_int, req: u32, arg: *mut libc::c_void) -> io::Result<()> {
    cvt(libc::ioctl(fd, req as _, arg)).map(|_| ())
}

// --- linux/fb.h ---
const FBIOGET_VSCREENINFO: u32 = 0x4600;
const FBIOGET_FSCREENINFO: u32 = 0x4602;

#[derive(Clone, Copy, Debug)]
pub struct FbInfo {
    pub id: [u8; 16],
    pub xres: u32,
    pub yres: u32,
    pub xoffset: u32,
    pub yoffset: u32,
    pub bpp: u32,
    pub red_offset: u32,
    pub rotate: u32,
    pub line_length: u32,
    pub smem_len: u32,
    pub width_mm: u32,
}

fn u32_at(b: &[u8], off: usize) -> u32 {
    u32::from_ne_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

fn read_fb_info(fd: libc::c_int) -> io::Result<FbInfo> {
    // fb_var_screeninfo is 160 bytes of u32 on every ABI.
    let mut var = [0u8; 160];
    unsafe { ioctl_ptr(fd, FBIOGET_VSCREENINFO, var.as_mut_ptr().cast())? };
    // fb_fix_screeninfo has `unsigned long` fields, so its layout depends on pointer width.
    let mut fix = [0u8; 128];
    unsafe { ioctl_ptr(fd, FBIOGET_FSCREENINFO, fix.as_mut_ptr().cast())? };
    let ul = std::mem::size_of::<libc::c_ulong>();
    // id[16], smem_start (ulong), smem_len, type, type_aux, visual, 3×u16, line_length.
    let smem_len_off = 16 + ul;
    let after_u16s = smem_len_off + 4 * 4 + 6;
    let line_length_off = (after_u16s + 3) & !3;
    let mut id = [0u8; 16];
    id.copy_from_slice(&fix[..16]);
    Ok(FbInfo {
        id,
        xres: u32_at(&var, 0),
        yres: u32_at(&var, 4),
        xoffset: u32_at(&var, 16),
        yoffset: u32_at(&var, 20),
        bpp: u32_at(&var, 24),
        red_offset: u32_at(&var, 32),
        width_mm: u32_at(&var, 92),
        rotate: u32_at(&var, 136),
        smem_len: u32_at(&fix, smem_len_off),
        line_length: u32_at(&fix, line_length_off),
    })
}

impl FbInfo {
    pub fn id_str(&self) -> String {
        String::from_utf8_lossy(&self.id).trim_end_matches('\0').to_string()
    }
}

// --- EPDC ioctls (see README "Refresh") ---
const MXCFB_SEND_UPDATE_V1_NTX: u32 = 0x4044_462E; // 68-byte struct, Mark 3-6
const MXCFB_SEND_UPDATE_V2: u32 = 0x4048_462E; // 72-byte struct, Mark 7+
const MXCFB_WAIT_FOR_UPDATE_COMPLETE_V1: u32 = 0x4004_462F; // u32 marker
const MXCFB_WAIT_FOR_UPDATE_COMPLETE_V3: u32 = 0xC008_462F; // {marker, collision_test}
const HWTCON_SEND_UPDATE: u32 = 0x4024_462E; // 36-byte struct
const HWTCON_WAIT_FOR_UPDATE_SUBMISSION: u32 = 0x4004_4637;

const UPDATE_MODE_PARTIAL: u32 = 0;
const UPDATE_MODE_FULL: u32 = 1;
const TEMP_USE_AMBIENT: i32 = 0x1000;
const MXC_WF_DU: u32 = 1;
const MXC_WF_GC16: u32 = 2;
const MXC_WF_GLR16: u32 = 6;
const MXC_WF_AUTO: u32 = 257;
const MTK_WF_DU: u32 = 1;
const MTK_WF_GC16: u32 = 2;
const MTK_WF_GLR16: u32 = 4;
const HWTCON_FLAG_CFA_SKIP: u32 = 0x8000;

#[repr(C)]
#[derive(Default)]
struct MxcRect {
    top: u32,
    left: u32,
    width: u32,
    height: u32,
}

/// `mxcfb_update_data` as on Mark 7 (72 bytes).
#[repr(C)]
#[derive(Default)]
struct MxcUpdateV2 {
    region: MxcRect,
    waveform_mode: u32,
    update_mode: u32,
    update_marker: u32,
    temp: i32,
    flags: u32,
    dither_mode: i32,
    quant_bit: i32,
    // mxcfb_alt_buffer_data: phys_addr, width, height, alt_update_region
    alt: [u32; 7],
}

/// `mxcfb_update_data_v1_ntx` (68 bytes on 32-bit ARM: the alt buffer starts with a pointer).
#[repr(C)]
#[derive(Default)]
struct MxcUpdateV1Ntx {
    region: MxcRect,
    waveform_mode: u32,
    update_mode: u32,
    update_marker: u32,
    temp: i32,
    flags: u32,
    // alt_buffer_data_ntx: virt_addr (void*), phys_addr, width, height, alt_update_region
    alt: [u32; 8],
}

/// `hwtcon_update_data` (36 bytes).
#[repr(C)]
#[derive(Default)]
struct HwtconUpdate {
    region: MxcRect,
    waveform_mode: u32,
    update_mode: u32,
    update_marker: u32,
    flags: u32,
    dither_mode: i32,
}

#[repr(C)]
struct MarkerData {
    marker: u32,
    collision_test: u32,
}

pub struct Framebuffer {
    fd: libc::c_int,
    map: *mut u8,
    map_len: usize,
    pub info: FbInfo,
    pub profile: Profile,
    panel: Panel,
    /// Canonical rotation of the framebuffer as found (0 = portrait upright).
    pub canonical: u8,
    /// Panel size in canonical portrait.
    base: (i32, i32),
    dpi: f32,
    marker: u32,
    waveforms: (u32, u32, u32),
    power_state: Option<std::path::PathBuf>,
}

impl Drop for Framebuffer {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.map.cast(), self.map_len);
            libc::close(self.fd);
        }
    }
}

/// Why the reader cannot draw on this device.
pub fn unsupported_reason(profile: &Profile) -> Option<String> {
    (profile.panel == Panel::Sunxi).then(|| format!("{} draws through the Allwinner display engine, which this version does not support.", profile.name))
}

impl Framebuffer {
    pub fn open(profile: Profile, config: &Config) -> io::Result<Framebuffer> {
        if let Some(why) = unsupported_reason(&profile) {
            return Err(io::Error::new(io::ErrorKind::Unsupported, why));
        }
        let fd = open("/dev/fb0", libc::O_RDWR)?;
        let info = read_fb_info(fd)?;
        if ![8, 16, 32].contains(&info.bpp) || info.line_length == 0 || info.xres == 0 || info.yres == 0 {
            unsafe { libc::close(fd) };
            return Err(io::Error::new(io::ErrorKind::Unsupported, format!("framebuffer mode {}x{} at {} bpp", info.xres, info.yres, info.bpp)));
        }
        let map_len = info.smem_len.max(info.line_length * (info.yres + info.yoffset)) as usize;
        let map = unsafe { libc::mmap(std::ptr::null_mut(), map_len, libc::PROT_READ | libc::PROT_WRITE, libc::MAP_SHARED, fd, 0) };
        if map == libc::MAP_FAILED {
            let e = io::Error::last_os_error();
            unsafe { libc::close(fd) };
            return Err(e);
        }
        let id = info.id_str();
        let panel = match profile.panel {
            Panel::Probe if id.contains("mxc") => Panel::Mxc7, // falls back to MxcOld on ENOTTY
            Panel::Probe => Panel::Mtk,
            p => p,
        };
        let canonical = match config.display.quarter_turns {
            Some(q) => (q % 4) as u8,
            None => profile.rotation_map[(info.rotate & 3) as usize],
        };
        let (xr, yr) = (info.xres as i32, info.yres as i32);
        let base = if canonical % 2 == 1 { (yr, xr) } else { (xr, yr) };
        let dpi = if profile.dpi > 0.0 {
            profile.dpi
        } else if info.width_mm > 20 && info.width_mm < 400 {
            info.xres as f32 / (info.width_mm as f32 / 25.4)
        } else {
            crate::profile::guess_dpi(xr, yr)
        };
        let d = &config.display;
        let waveforms = match panel {
            Panel::Mtk => (d.waveform_full.unwrap_or(MTK_WF_GC16), d.waveform_partial.unwrap_or(MTK_WF_GLR16), d.waveform_fast.unwrap_or(MTK_WF_DU)),
            Panel::MxcOld => (d.waveform_full.unwrap_or(MXC_WF_GC16), d.waveform_partial.unwrap_or(MXC_WF_AUTO), d.waveform_fast.unwrap_or(MXC_WF_DU)),
            _ => (d.waveform_full.unwrap_or(MXC_WF_GC16), d.waveform_partial.unwrap_or(MXC_WF_GLR16), d.waveform_fast.unwrap_or(MXC_WF_DU)),
        };
        let ps = std::path::PathBuf::from("/sys/class/graphics/fb0/power_state");
        let power_state = (panel == Panel::Mxc7 && ps.exists()).then_some(ps);
        eprintln!(
            "fb: {id} {}x{} virtual offset {},{} {} bpp stride {} rotate {} (canonical {canonical}) panel {panel:?} dpi {dpi}",
            info.xres, info.yres, info.xoffset, info.yoffset, info.bpp, info.line_length, info.rotate
        );
        Ok(Framebuffer { fd, map: map.cast(), map_len, info, profile, panel, canonical, base, dpi, marker: 0, waveforms, power_state })
    }

    /// Writes base-orientation pixels for a canvas region into the framebuffer, and returns
    /// the region in framebuffer coordinates.
    fn write(&mut self, canvas: &Canvas, rotation: u8, region: Rect) -> Rect {
        let base = self.base;
        let c = self.canonical;
        // Canvas rect → base rect → framebuffer (a canvas at the canonical rotation).
        let b = device::rect_to_base(base, rotation, region.intersect(&canvas.bounds()));
        let f = rect_from_base(base, c, b).intersect(&Rect::new(0, 0, self.info.xres as i32, self.info.yres as i32));
        let bpp = self.info.bpp as usize / 8;
        let stride = self.info.line_length as usize;
        let (xo, yo) = (self.info.xoffset as usize, self.info.yoffset as usize);
        for fy in f.y..f.bottom() {
            let row = (fy as usize + yo) * stride;
            for fx in f.x..f.right() {
                let (bx, by) = device::canvas_to_base(base, c, fx, fy);
                let (cx, cy) = device::base_to_canvas(base, rotation, bx, by);
                let v = canvas.get(cx.clamp(0, canvas.w - 1), cy.clamp(0, canvas.h - 1));
                let off = row + (fx as usize + xo) * bpp;
                if off + bpp > self.map_len {
                    continue;
                }
                unsafe {
                    let p = self.map.add(off);
                    match bpp {
                        1 => *p = v,
                        2 => {
                            let g = v as u16;
                            let rgb = ((g >> 3) << 11) | ((g >> 2) << 5) | (g >> 3);
                            p.cast::<u16>().write_unaligned(rgb);
                        }
                        _ => {
                            // BGRA or RGBA: grey is the same either way; alpha opaque.
                            p.cast::<u32>().write_unaligned(0xFF00_0000 | (v as u32) << 16 | (v as u32) << 8 | v as u32);
                        }
                    }
                }
            }
        }
        f
    }

    fn refresh(&mut self, r: Rect, mode: Refresh) -> io::Result<()> {
        if r.is_empty() {
            return Ok(());
        }
        if let Some(p) = &self.power_state {
            let _ = std::fs::write(p, "1,0");
        }
        self.marker = self.marker.wrapping_add(1).max(1);
        let (wf_full, wf_partial, wf_fast) = self.waveforms;
        let (waveform, full) = match mode {
            Refresh::Full => (wf_full, true),
            Refresh::Partial => (wf_partial, false),
            Refresh::Fast => (wf_fast, false),
        };
        let region = MxcRect { top: r.y as u32, left: r.x as u32, width: r.w as u32, height: r.h as u32 };
        match self.panel {
            Panel::Mxc7 | Panel::Probe => {
                let mut u = MxcUpdateV2 { region, waveform_mode: waveform, update_mode: if full { UPDATE_MODE_FULL } else { UPDATE_MODE_PARTIAL }, update_marker: self.marker, temp: TEMP_USE_AMBIENT, ..Default::default() };
                match unsafe { ioctl_ptr(self.fd, MXCFB_SEND_UPDATE_V2, (&mut u as *mut MxcUpdateV2).cast()) } {
                    Ok(()) => {}
                    Err(e) if self.profile.panel == Panel::Probe && matches!(e.raw_os_error(), Some(libc::ENOTTY) | Some(libc::EINVAL)) => {
                        eprintln!("fb: Mark 7 update refused ({e}); using the older call");
                        self.panel = Panel::MxcOld;
                        return self.refresh(r, mode);
                    }
                    Err(e) => return Err(e),
                }
                if full {
                    self.wait_mxc(MXCFB_WAIT_FOR_UPDATE_COMPLETE_V3);
                }
            }
            Panel::MxcOld => {
                let mut u = MxcUpdateV1Ntx { region, waveform_mode: waveform, update_mode: if full { UPDATE_MODE_FULL } else { UPDATE_MODE_PARTIAL }, update_marker: self.marker, temp: TEMP_USE_AMBIENT, ..Default::default() };
                unsafe { ioctl_ptr(self.fd, MXCFB_SEND_UPDATE_V1_NTX, (&mut u as *mut MxcUpdateV1Ntx).cast())? };
                if full {
                    self.wait_mxc(MXCFB_WAIT_FOR_UPDATE_COMPLETE_V1);
                }
            }
            Panel::Mtk => {
                // The driver cuts off the last row and column of unaligned regions; align to 16.
                let x0 = r.x & !15;
                let y0 = r.y & !15;
                let x1 = ((r.right() + 15) & !15).min(self.info.xres as i32);
                let y1 = ((r.bottom() + 15) & !15).min(self.info.yres as i32);
                // REAGL is always sent as a full update on MTK; it does not flash.
                let full_mode = full || waveform == MTK_WF_GLR16;
                let mut u = HwtconUpdate {
                    region: MxcRect { top: y0 as u32, left: x0 as u32, width: (x1 - x0) as u32, height: (y1 - y0) as u32 },
                    waveform_mode: waveform,
                    update_mode: if full_mode { UPDATE_MODE_FULL } else { UPDATE_MODE_PARTIAL },
                    update_marker: self.marker,
                    flags: if self.profile.kaleido { HWTCON_FLAG_CFA_SKIP } else { 0 },
                    dither_mode: 0,
                };
                unsafe { ioctl_ptr(self.fd, HWTCON_SEND_UPDATE, (&mut u as *mut HwtconUpdate).cast())? };
                if self.profile.id != 389 {
                    let mut m = self.marker;
                    let _ = unsafe { ioctl_ptr(self.fd, HWTCON_WAIT_FOR_UPDATE_SUBMISSION, (&mut m as *mut u32).cast()) };
                }
            }
            Panel::Sunxi => return Err(io::Error::new(io::ErrorKind::Unsupported, "sunxi")),
        }
        Ok(())
    }

    fn wait_mxc(&mut self, req: u32) {
        if !self.profile.wait_reliable {
            // The wait ioctl stalls for its whole timeout on these models; give the EPDC a
            // moment instead, as KOReader does.
            std::thread::sleep(Duration::from_micros(2500));
            return;
        }
        let r = if req == MXCFB_WAIT_FOR_UPDATE_COMPLETE_V3 {
            let mut m = MarkerData { marker: self.marker, collision_test: 0 };
            unsafe { ioctl_ptr(self.fd, req, (&mut m as *mut MarkerData).cast()) }
        } else {
            let mut m = self.marker;
            unsafe { ioctl_ptr(self.fd, req, (&mut m as *mut u32).cast()) }
        };
        if let Err(e) = r {
            eprintln!("fb: wait failed: {e}");
        }
    }
}

/// A base-orientation rectangle in the coordinates of a canvas at `rotation`.
pub fn rect_from_base(base: (i32, i32), rotation: u8, r: Rect) -> Rect {
    if r.is_empty() {
        return Rect::default();
    }
    let (ax, ay) = device::base_to_canvas(base, rotation, r.x, r.y);
    let (bx, by) = device::base_to_canvas(base, rotation, r.right() - 1, r.bottom() - 1);
    Rect::new(ax.min(bx), ay.min(by), (ax - bx).abs() + 1, (ay - by).abs() + 1)
}

impl Display for Framebuffer {
    fn base_size(&self) -> (i32, i32) {
        self.base
    }
    fn dpi(&self) -> f32 {
        self.dpi
    }
    fn present(&mut self, canvas: &Canvas, rotation: u8, region: Rect, mode: Refresh) -> io::Result<()> {
        let f = self.write(canvas, rotation, region);
        self.refresh(f, mode)
    }
}

// --- Input (linux/input.h) ---
const EV_SYN: u16 = 0;
const EV_KEY: u16 = 1;
const EV_ABS: u16 = 3;
const EV_MSC: u16 = 4;
const SYN_REPORT: u16 = 0;
const BTN_TOUCH: u16 = 0x14a;
const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_PRESSURE: u16 = 0x18;
const ABS_MT_SLOT: u16 = 0x2f;
const ABS_MT_TOUCH_MAJOR: u16 = 0x30;
const ABS_MT_POSITION_X: u16 = 0x35;
const ABS_MT_POSITION_Y: u16 = 0x36;
const ABS_MT_TRACKING_ID: u16 = 0x39;
const ABS_MT_PRESSURE: u16 = 0x3a;
const MSC_RAW: u16 = 0x03;
const KEY_POWER: u16 = 116;
const KEY_F1: u16 = 59; // sleep cover
const KEY_HOME: u16 = 102;
const KEY_F23: u16 = 193; // page back
const KEY_F24: u16 = 194; // page forward

const fn ioc_read(nr: u32, len: u32) -> u32 {
    (2 << 30) | (len << 16) | ((b'E' as u32) << 8) | nr
}

fn test_bit(bits: &[u8], n: u16) -> bool {
    bits.get(n as usize / 8).is_some_and(|b| b & (1 << (n % 8)) != 0)
}

fn ev_bits(fd: libc::c_int, ev: u16) -> Vec<u8> {
    let mut bits = vec![0u8; 96];
    let req = ioc_read(0x20 + ev as u32, bits.len() as u32);
    if unsafe { ioctl_ptr(fd, req, bits.as_mut_ptr().cast()) }.is_err() {
        bits.clear();
    }
    bits
}

fn abs_max(fd: libc::c_int, code: u16) -> Option<i32> {
    let mut info = [0i32; 6]; // value, minimum, maximum, fuzz, flat, resolution
    let req = ioc_read(0x40 + code as u32, 24);
    unsafe { ioctl_ptr(fd, req, info.as_mut_ptr().cast()) }.ok()?;
    (info[2] > info[1]).then_some(info[2])
}

fn dev_name(fd: libc::c_int) -> String {
    let mut name = [0u8; 128];
    let _ = unsafe { ioctl_ptr(fd, ioc_read(0x06, 128), name.as_mut_ptr().cast()) };
    String::from_utf8_lossy(&name).trim_end_matches('\0').to_string()
}

struct TouchState {
    /// Only the first finger counts; other slots are ignored.
    slot: i32,
    raw: (i32, i32),
    down: bool,
    frame_down: Option<bool>,
    frame_pos: bool,
    max: (i32, i32),
    mt: bool,
}

pub struct EvdevInput {
    touch_fd: Option<libc::c_int>,
    key_fds: Vec<libc::c_int>,
    touch: TouchState,
    quirk: TouchQuirk,
    base: (i32, i32),
    follow_sensor: bool,
    queue: VecDeque<RawEvent>,
    last_pos: (i32, i32),
}

impl Drop for EvdevInput {
    fn drop(&mut self) {
        for fd in self.touch_fd.iter().chain(self.key_fds.iter()) {
            unsafe { libc::close(*fd) };
        }
    }
}

impl EvdevInput {
    /// Opens every input device: the one with touch axes as the touch panel, and every one
    /// with the power, page-turn, cover or home keys (they are spread over several devices on
    /// some models, and the event numbers differ between hardware revisions).
    pub fn open(profile: &Profile, config: &Config, base: (i32, i32)) -> io::Result<EvdevInput> {
        let mut touch_fd = None;
        let mut max = (base.0 - 1, base.1 - 1);
        let mut mt = false;
        let mut key_fds = Vec::new();
        let forced = config.touch.device.clone();
        // Multi-touch panels first, then single-touch ones; an accelerometer (it has ABS_Z)
        // never counts as touch.
        let mut devices = Vec::new();
        for n in 0..16 {
            let path = format!("/dev/input/event{n}");
            let Ok(fd) = open(&path, libc::O_RDONLY | libc::O_NONBLOCK) else { continue };
            let types = ev_bits(fd, 0);
            let abs = if test_bit(&types, EV_ABS) { ev_bits(fd, EV_ABS) } else { Vec::new() };
            let keys = if test_bit(&types, EV_KEY) { ev_bits(fd, EV_KEY) } else { Vec::new() };
            let rank = match &forced {
                Some(p) if *p == path => 0,
                Some(_) => 9,
                None if test_bit(&abs, ABS_MT_POSITION_X) => 1,
                None if test_bit(&abs, ABS_X) && test_bit(&abs, ABS_Y) && !test_bit(&abs, 0x02) && test_bit(&keys, BTN_TOUCH) => 2,
                None => 9,
            };
            devices.push((rank, path, fd, types, abs, keys));
        }
        devices.sort_by_key(|d| d.0);
        for (rank, path, fd, types, abs, keys) in devices {
            let is_touch = rank < 9;
            let has_keys = [KEY_POWER, KEY_F1, KEY_HOME, KEY_F23, KEY_F24].iter().any(|&k| test_bit(&keys, k)) || test_bit(&types, EV_MSC);
            eprintln!("input: {path} \"{}\" touch={is_touch} keys={has_keys}", dev_name(fd));
            if is_touch && touch_fd.is_none() {
                mt = test_bit(&abs, ABS_MT_POSITION_X);
                let (cx, cy) = if mt { (ABS_MT_POSITION_X, ABS_MT_POSITION_Y) } else { (ABS_X, ABS_Y) };
                // Raw axes: after swapping, raw X runs along the panel's height.
                let swap = config.touch.swap_xy.unwrap_or(profile.touch.swap_xy);
                let default = if swap { (base.1 - 1, base.0 - 1) } else { (base.0 - 1, base.1 - 1) };
                max = (abs_max(fd, cx).unwrap_or(default.0), abs_max(fd, cy).unwrap_or(default.1));
                touch_fd = Some(fd);
                if has_keys {
                    key_fds.push(fd);
                }
            } else if has_keys {
                key_fds.push(fd);
            } else {
                unsafe { libc::close(fd) };
            }
        }
        let quirk = TouchQuirk {
            swap_xy: config.touch.swap_xy.unwrap_or(profile.touch.swap_xy),
            mirror_x: config.touch.mirror_x.unwrap_or(profile.touch.mirror_x),
            mirror_y: config.touch.mirror_y.unwrap_or(profile.touch.mirror_y),
        };
        eprintln!("input: raw max {max:?} quirk {quirk:?} multitouch {mt}");
        Ok(EvdevInput {
            touch_fd,
            key_fds,
            touch: TouchState { slot: 0, raw: (0, 0), down: false, frame_down: None, frame_pos: false, max, mt },
            quirk,
            base,
            follow_sensor: profile.follow_sensor,
            queue: VecDeque::new(),
            last_pos: (0, 0),
        })
    }

    /// The touch transform at canonical portrait: swap, scale to pixels, mirror.
    fn to_base(&self, raw: (i32, i32)) -> (i32, i32) {
        let (mut x, mut y, mx, my) = if self.quirk.swap_xy { (raw.1, raw.0, self.touch.max.1, self.touch.max.0) } else { (raw.0, raw.1, self.touch.max.0, self.touch.max.1) };
        let (w, h) = self.base;
        x = (x as i64 * (w - 1) as i64 / mx.max(1) as i64) as i32;
        y = (y as i64 * (h - 1) as i64 / my.max(1) as i64) as i32;
        if self.quirk.mirror_x {
            x = w - 1 - x;
        }
        if self.quirk.mirror_y {
            y = h - 1 - y;
        }
        (x.clamp(0, w - 1), y.clamp(0, h - 1))
    }

    fn handle(&mut self, is_touch: bool, kind: u16, code: u16, value: i32) {
        match kind {
            EV_ABS if is_touch => {
                let t = &mut self.touch;
                match code {
                    ABS_MT_SLOT => t.slot = value,
                    _ if t.slot != 0 => {}
                    ABS_MT_TRACKING_ID => t.frame_down = Some(value >= 0),
                    ABS_MT_POSITION_X => {
                        t.raw.0 = value;
                        t.frame_pos = true;
                    }
                    ABS_MT_POSITION_Y => {
                        t.raw.1 = value;
                        t.frame_pos = true;
                    }
                    ABS_X if !t.mt => {
                        t.raw.0 = value;
                        t.frame_pos = true;
                    }
                    ABS_Y if !t.mt => {
                        t.raw.1 = value;
                        t.frame_pos = true;
                    }
                    // A lift reported as zero pressure or zero contact size (Phoenix, Elan).
                    ABS_MT_PRESSURE | ABS_MT_TOUCH_MAJOR | ABS_PRESSURE if value == 0 => t.frame_down = Some(false),
                    _ => {}
                }
            }
            EV_KEY if is_touch && code == BTN_TOUCH => self.touch.frame_down = Some(value != 0),
            EV_KEY if value == 1 || (code == KEY_F1 && value == 0) => {
                let key = match code {
                    KEY_POWER => Some(Key::Power),
                    KEY_F24 => Some(Key::Forward),
                    KEY_F23 => Some(Key::Back),
                    KEY_HOME => Some(Key::Home),
                    KEY_F1 if value == 1 => Some(Key::CoverClosed),
                    KEY_F1 => Some(Key::CoverOpened),
                    _ => None,
                };
                if let Some(k) = key {
                    self.queue.push_back(RawEvent::Key(k));
                }
            }
            EV_MSC if code == MSC_RAW && self.follow_sensor => {
                let r = match value {
                    0x18 => Some(0), // portrait up
                    0x19 => Some(1), // landscape right
                    0x17 => Some(2), // portrait down
                    0x1a => Some(3), // landscape left
                    _ => None,
                };
                if let Some(r) = r {
                    self.queue.push_back(RawEvent::Orientation(r));
                }
            }
            EV_SYN if is_touch && code == SYN_REPORT => {
                let t = &mut self.touch;
                let down = t.frame_down.unwrap_or(t.down || t.frame_pos);
                let had_pos = t.frame_pos;
                t.frame_down = None;
                t.frame_pos = false;
                let was = t.down;
                t.down = down;
                let raw = t.raw;
                if down {
                    let p = self.to_base(raw);
                    if !was {
                        self.last_pos = p;
                        self.queue.push_back(RawEvent::Touch { phase: Phase::Down, x: p.0, y: p.1 });
                    } else if had_pos {
                        self.last_pos = p;
                        self.queue.push_back(RawEvent::Touch { phase: Phase::Move, x: p.0, y: p.1 });
                    }
                } else if was {
                    // The lift frame's own coordinates are unreliable on some panels (Touch B).
                    let (x, y) = self.last_pos;
                    self.queue.push_back(RawEvent::Touch { phase: Phase::Up, x, y });
                }
            }
            _ => {}
        }
    }

    fn read_fd(&mut self, fd: libc::c_int) {
        // struct input_event: timeval (two longs), u16 type, u16 code, i32 value.
        let tv = 2 * std::mem::size_of::<libc::c_long>();
        let size = tv + 8;
        let mut buf = [0u8; 64 * 24];
        loop {
            let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len() / size * size) };
            if n <= 0 {
                return;
            }
            for ev in buf[..n as usize].chunks_exact(size) {
                let kind = u16::from_ne_bytes([ev[tv], ev[tv + 1]]);
                let code = u16::from_ne_bytes([ev[tv + 2], ev[tv + 3]]);
                let value = i32::from_ne_bytes([ev[tv + 4], ev[tv + 5], ev[tv + 6], ev[tv + 7]]);
                self.handle(Some(fd) == self.touch_fd, kind, code, value);
            }
        }
    }
}

impl device::Input for EvdevInput {
    fn next(&mut self, timeout: Duration) -> Option<RawEvent> {
        let until = Instant::now() + timeout;
        loop {
            if let Some(e) = self.queue.pop_front() {
                return Some(e);
            }
            let mut fds: Vec<libc::pollfd> = self.touch_fd.iter().chain(self.key_fds.iter()).map(|&fd| libc::pollfd { fd, events: libc::POLLIN, revents: 0 }).collect();
            fds.dedup_by_key(|p| p.fd);
            let left = until.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return None;
            }
            let r = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as _, left.as_millis().min(i32::MAX as u128) as i32) };
            if r < 0 {
                if io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return None;
            }
            for p in fds.iter().filter(|p| p.revents & libc::POLLIN != 0) {
                self.read_fd(p.fd);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn struct_sizes_match_the_ioctl_numbers() {
        // The size is bits 16..29 of each request number.
        assert_eq!((MXCFB_SEND_UPDATE_V2 >> 16) & 0x3fff, std::mem::size_of::<MxcUpdateV2>() as u32);
        assert_eq!((HWTCON_SEND_UPDATE >> 16) & 0x3fff, std::mem::size_of::<HwtconUpdate>() as u32);
        assert_eq!((MXCFB_WAIT_FOR_UPDATE_COMPLETE_V3 >> 16) & 0x3fff, std::mem::size_of::<MarkerData>() as u32);
        // 68 bytes on 32-bit ARM, where the alt buffer's virt_addr pointer is 4 bytes.
        assert_eq!((MXCFB_SEND_UPDATE_V1_NTX >> 16) & 0x3fff, std::mem::size_of::<MxcUpdateV1Ntx>() as u32);
        assert_eq!(ioc_read(0x20, 96), 0x8060_4520); // EVIOCGBIT(0, 96)
    }

    fn input(quirk: TouchQuirk, base: (i32, i32), max: (i32, i32)) -> EvdevInput {
        EvdevInput {
            touch_fd: None,
            key_fds: Vec::new(),
            touch: TouchState { slot: 0, raw: (0, 0), down: false, frame_down: None, frame_pos: false, max, mt: true },
            quirk,
            base,
            follow_sensor: true,
            queue: VecDeque::new(),
            last_pos: (0, 0),
        }
    }

    #[test]
    fn libra2_elan_trace() {
        // A real Libra 2 trace (KOReader issue, see README): raw (1155, 178) was a tap at
        // (179, 1155) in portrait; the lift is pressure 0, then tracking ID -1 with BTN_TOUCH 0.
        let mut i = input(TouchQuirk { swap_xy: true, mirror_x: false, mirror_y: false }, (1264, 1680), (1679, 1263));
        for (t, c, v) in [(EV_ABS, ABS_MT_TRACKING_ID, 7), (EV_ABS, ABS_MT_PRESSURE, 30), (EV_ABS, ABS_MT_POSITION_X, 1155), (EV_ABS, ABS_MT_POSITION_Y, 178), (EV_KEY, BTN_TOUCH, 1), (EV_SYN, SYN_REPORT, 0)] {
            i.handle(true, t, c, v);
        }
        for (t, c, v) in [(EV_ABS, ABS_MT_PRESSURE, 0), (EV_SYN, SYN_REPORT, 0), (EV_ABS, ABS_MT_TRACKING_ID, -1), (EV_KEY, BTN_TOUCH, 0), (EV_SYN, SYN_REPORT, 0)] {
            i.handle(true, t, c, v);
        }
        assert_eq!(i.queue.pop_front(), Some(RawEvent::Touch { phase: Phase::Down, x: 178, y: 1155 }));
        assert_eq!(i.queue.pop_front(), Some(RawEvent::Touch { phase: Phase::Up, x: 178, y: 1155 }));
        assert!(i.queue.is_empty());
        i.handle(false, EV_KEY, KEY_F24, 1);
        i.handle(false, EV_KEY, KEY_F24, 0);
        i.handle(false, EV_MSC, MSC_RAW, 0x19);
        assert_eq!(i.queue.pop_front(), Some(RawEvent::Key(Key::Forward)));
        assert_eq!(i.queue.pop_front(), Some(RawEvent::Orientation(1)));
    }

    #[test]
    fn snow_protocol_lifts_on_btn_touch() {
        // Snow: no slots, the tracking ID never goes to -1.
        let mut i = input(TouchQuirk { swap_xy: true, mirror_x: true, mirror_y: false }, (1072, 1448), (1447, 1071));
        for (t, c, v) in [(EV_ABS, ABS_MT_TRACKING_ID, 0), (EV_ABS, ABS_MT_POSITION_X, 100), (EV_ABS, ABS_MT_POSITION_Y, 50), (EV_KEY, BTN_TOUCH, 1), (EV_SYN, SYN_REPORT, 0), (EV_KEY, BTN_TOUCH, 0), (EV_SYN, SYN_REPORT, 0)] {
            i.handle(true, t, c, v);
        }
        // Swapped, then mirrored in x.
        assert_eq!(i.queue.pop_front(), Some(RawEvent::Touch { phase: Phase::Down, x: 1071 - 50, y: 100 }));
        assert!(matches!(i.queue.pop_front(), Some(RawEvent::Touch { phase: Phase::Up, .. })));
    }
}
