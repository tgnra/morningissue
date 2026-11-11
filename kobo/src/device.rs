//! What the reader needs from the hardware, and nothing more: a screen to present a grey
//! canvas on, raw input, clocks, and a way to sleep. The Kobo backend and the desktop backend
//! implement these.
//!
//! **Orientation.** The *base* orientation is the panel as the framebuffer is set up when the
//! reader starts (portrait on every Kobo once the canonical rotation is applied). The reader
//! can turn its picture by quarter turns clockwise from there (`rotation`), and the canvas it
//! draws on swaps width and height for odd turns. Touches arrive in base coordinates and are
//! turned into canvas coordinates with [`base_to_canvas`].

use crate::canvas::{Canvas, Rect};
use std::io;
use std::time::Duration;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refresh {
    /// Flashing full-quality refresh (GC16 full): clears ghosting.
    Full,
    /// Non-flashing greyscale refresh (GL16 / REAGL): page turns.
    Partial,
    /// Fast black-and-white refresh (DU): buttons that toggle.
    Fast,
}

pub trait Display {
    /// Size of the panel in base orientation.
    fn base_size(&self) -> (i32, i32);
    /// Pixels per inch.
    fn dpi(&self) -> f32;
    /// Shows `region` of `canvas`, which is in the given rotation from base.
    fn present(&mut self, canvas: &Canvas, rotation: u8, region: Rect, mode: Refresh) -> io::Result<()>;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Down,
    Move,
    Up,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Key {
    /// The page-turn button that goes on.
    Forward,
    /// The page-turn button that goes back.
    Back,
    Power,
    /// The sleep cover closed.
    CoverClosed,
    CoverOpened,
    Home,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RawEvent {
    /// A finger, in base coordinates (pixels).
    Touch { phase: Phase, x: i32, y: i32 },
    Key(Key),
    /// The orientation sensor's reading, as quarter turns clockwise from base.
    Orientation(u8),
    /// The input has ended (the end of a desktop script); the reader leaves as on Exit.
    /// A Kobo never sends it.
    Quit,
}

pub trait Input {
    /// The next event, or None when `timeout` passes without one.
    fn next(&mut self, timeout: Duration) -> Option<RawEvent>;
}

/// Putting the device to sleep. The reader decides *when* (its sleep screen, the delays, the
/// retries and what wakes it are in `app.rs`); a backend only does each step. The order, from
/// KOReader (`frontend/device/kobo/device.lua`, `Kobo:suspend`, `Kobo:resume`) and Plato
/// (`scripts/suspend.sh`, `resume.sh`): Wi-Fi off, light off, then `prepare`, two seconds,
/// `suspend`, and `resume` after every attempt.
pub trait Power {
    /// Turns Wi-Fi off cleanly, the same teardown as on exit. Suspending with Wi-Fi on can
    /// fail or hang the kernel on several Kobos.
    fn wifi_off(&mut self);
    /// Flags the kernel's subsystems for suspend (`1` to `/sys/power/state-extended`). An
    /// error means the kernel refused and the attempt should be tried again later.
    fn prepare(&mut self) -> io::Result<()>;
    /// Syncs the file systems and suspends to RAM (`mem` to `/sys/power/state`). Returns once
    /// the device is awake again, for whatever reason; an error means the kernel refused
    /// (usually EBUSY, the EPDC or the touch panel still busy).
    fn suspend(&mut self) -> io::Result<()>;
    /// Unflags the subsystems (`0` to `state-extended`) after a wake or a refused attempt.
    fn resume(&mut self);
}

/// A device that cannot sleep (and the default in tests that do not care): every step
/// succeeds at once.
pub struct NoPower;

impl Power for NoPower {
    fn wifi_off(&mut self) {}
    fn prepare(&mut self) -> io::Result<()> {
        Ok(())
    }
    fn suspend(&mut self) -> io::Result<()> {
        Ok(())
    }
    fn resume(&mut self) {}
}

pub trait Clock {
    /// Monotonic milliseconds, for durations on screen.
    fn mono_ms(&self) -> u64;
    /// Wall-clock milliseconds since the epoch, for event times.
    fn wall_ms(&self) -> i64;
}

pub struct SystemClock {
    start: std::time::Instant,
}

impl SystemClock {
    pub fn new() -> SystemClock {
        SystemClock { start: std::time::Instant::now() }
    }
}

impl Default for SystemClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for SystemClock {
    fn mono_ms(&self) -> u64 {
        self.start.elapsed().as_millis() as u64
    }
    fn wall_ms(&self) -> i64 {
        crate::time::system_millis()
    }
}

/// Canvas size for a rotation of a base `(w, h)`.
pub fn canvas_size(base: (i32, i32), rotation: u8) -> (i32, i32) {
    if rotation % 2 == 1 {
        (base.1, base.0)
    } else {
        base
    }
}

/// Where canvas pixel (x, y) lands in base coordinates. Rotation 1 shows the canvas turned a
/// quarter clockwise, so its top edge runs down the right side of the panel.
pub fn canvas_to_base(base: (i32, i32), rotation: u8, x: i32, y: i32) -> (i32, i32) {
    let (bw, bh) = base;
    match rotation % 4 {
        0 => (x, y),
        1 => (bw - 1 - y, x),
        2 => (bw - 1 - x, bh - 1 - y),
        _ => (y, bh - 1 - x),
    }
}

/// The inverse of [`canvas_to_base`].
pub fn base_to_canvas(base: (i32, i32), rotation: u8, x: i32, y: i32) -> (i32, i32) {
    let (bw, bh) = base;
    match rotation % 4 {
        0 => (x, y),
        1 => (y, bw - 1 - x),
        2 => (bw - 1 - x, bh - 1 - y),
        _ => (bh - 1 - y, x),
    }
}

/// A canvas rectangle in base coordinates.
pub fn rect_to_base(base: (i32, i32), rotation: u8, r: Rect) -> Rect {
    let (ax, ay) = canvas_to_base(base, rotation, r.x, r.y);
    let (bx, by) = canvas_to_base(base, rotation, r.right() - 1, r.bottom() - 1);
    let x = ax.min(bx);
    let y = ay.min(by);
    Rect::new(x, y, (ax - bx).abs() + 1, (ay - by).abs() + 1)
}

/// Runs `f(base_x, base_y, value)` over `region` of a rotated canvas, row by row in base order,
/// so backends write the framebuffer sequentially.
pub fn for_each_base_pixel(canvas: &Canvas, base: (i32, i32), rotation: u8, region: Rect, mut row: impl FnMut(i32, i32, &mut dyn Iterator<Item = u8>)) {
    let b = rect_to_base(base, rotation, region.intersect(&canvas.bounds()));
    if b.is_empty() {
        return;
    }
    for by in b.y..b.bottom() {
        let mut it = (b.x..b.right()).map(|bx| {
            let (cx, cy) = base_to_canvas(base, rotation, bx, by);
            canvas.get(cx.clamp(0, canvas.w - 1), cy.clamp(0, canvas.h - 1))
        });
        row(b.x, by, &mut it);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rotations_round_trip() {
        let base = (10, 20);
        for r in 0..4u8 {
            let (cw, ch) = canvas_size(base, r);
            for (x, y) in [(0, 0), (cw - 1, 0), (3, ch - 1), (cw - 1, ch - 1)] {
                let (bx, by) = canvas_to_base(base, r, x, y);
                assert!(bx >= 0 && by >= 0 && bx < 10 && by < 20, "r{r} ({x},{y}) -> ({bx},{by})");
                assert_eq!(base_to_canvas(base, r, bx, by), (x, y));
            }
        }
        // Rotation 1: the canvas's top-left corner is at the panel's top-right.
        assert_eq!(canvas_to_base(base, 1, 0, 0), (9, 0));
    }
}
