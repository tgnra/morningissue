//! A desktop stand-in for the Kobo: the screen is a grey buffer that can be saved as PNG, input
//! is a script, and time only moves when the script says so. Refreshes are recorded so tests
//! can check the flash policy.

use crate::canvas::{Canvas, Rect};
use crate::device::{self, Clock, Display, Input, Key, Phase, RawEvent, Refresh};
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::io;
use std::path::Path;
use std::rc::Rc;
use std::time::Duration;

/// Shared between the display, the input script and the clock.
#[derive(Default)]
pub struct Shared {
    pub mono_ms: Cell<u64>,
    pub wall_ms: Cell<i64>,
    /// The rotation of the last frame shown, so scripted taps can be given in canvas
    /// coordinates.
    pub rotation: Cell<u8>,
    pub refreshes: RefCell<Vec<(Rect, Refresh)>>,
    /// What can be touched on the last frame, as (region, `{:?}` of the action), in the
    /// order the reader looks at them: the last one that contains a point wins.
    pub hits: RefCell<Vec<(Rect, String)>>,
    /// Every step of going to sleep and waking: "wifi-off", "prepare", "suspend", "resume",
    /// with "refused" after a step the simulated kernel turned down.
    pub power: RefCell<Vec<String>>,
    /// How many of the next `prepare` steps the simulated kernel refuses.
    pub refuse_prepare: Cell<u32>,
    /// How many of the next `suspend` steps the simulated kernel refuses (EBUSY).
    pub refuse_suspend: Cell<u32>,
    /// Whether Wi-Fi is connected now. Turning it off for sleep or from the reader clears it.
    pub wifi: Cell<bool>,
    /// Turning Wi-Fi on powers up but joins no network.
    pub wifi_no_network: Cell<bool>,
    /// Every Wi-Fi job the reader ran: "on", "off", "on stopped".
    pub radio: RefCell<Vec<String>>,
}

/// Sleep, simulated: each step is recorded in [`Shared::power`], and the kernel refuses as
/// many attempts as the test asks. A suspend returns at once, as if a key had woken the
/// device; the script then says what woke it.
pub struct DesktopPower(pub Rc<Shared>);

impl DesktopPower {
    fn step(&self, name: &str, refuse: &Cell<u32>) -> io::Result<()> {
        if refuse.get() > 0 {
            refuse.set(refuse.get() - 1);
            self.0.power.borrow_mut().push(format!("{name} refused"));
            return Err(io::Error::from_raw_os_error(libc::EBUSY));
        }
        self.0.power.borrow_mut().push(name.to_string());
        Ok(())
    }
}

impl device::Power for DesktopPower {
    fn wifi_off(&mut self) {
        self.0.power.borrow_mut().push("wifi-off".into());
        self.0.wifi.set(false);
    }
    fn prepare(&mut self) -> io::Result<()> {
        self.step("prepare", &self.0.refuse_prepare)
    }
    fn suspend(&mut self) -> io::Result<()> {
        self.step("suspend", &self.0.refuse_suspend)
    }
    fn resume(&mut self) {
        self.0.power.borrow_mut().push("resume".into());
    }
}

/// How long, on the script's clock, the simulated Wi-Fi takes to come up and to go down.
pub const RADIO_ON_MS: u64 = 3000;
pub const RADIO_OFF_MS: u64 = 1000;

/// Wi-Fi switched on and off, simulated on the script's clock: a job ends once enough
/// scripted time has passed, and then sets [`Shared::wifi`].
pub struct DesktopRadio {
    pub shared: Rc<Shared>,
    job: Option<(bool, u64)>,
}

impl DesktopRadio {
    pub fn new(shared: Rc<Shared>) -> DesktopRadio {
        DesktopRadio { shared, job: None }
    }
}

impl device::Radio for DesktopRadio {
    fn start(&mut self, on: bool) -> io::Result<()> {
        self.stop();
        self.job = Some((on, self.shared.mono_ms.get()));
        Ok(())
    }
    fn poll(&mut self) -> Option<bool> {
        let (on, at) = self.job?;
        let wait = if on { RADIO_ON_MS } else { RADIO_OFF_MS };
        if self.shared.mono_ms.get() < at + wait {
            return None;
        }
        self.job = None;
        self.shared.radio.borrow_mut().push(if on { "on" } else { "off" }.into());
        let up = on && !self.shared.wifi_no_network.get();
        self.shared.wifi.set(up);
        Some(!on || up)
    }
    fn stop(&mut self) {
        match self.job.take() {
            Some((true, _)) => self.shared.radio.borrow_mut().push("on stopped".into()),
            Some((false, _)) => {
                self.shared.radio.borrow_mut().push("off".into());
                self.shared.wifi.set(false);
            }
            None => {}
        }
    }
}

pub struct DesktopDisplay {
    pub panel: Rc<RefCell<Canvas>>,
    pub dpi: f32,
    shared: Rc<Shared>,
}

impl DesktopDisplay {
    pub fn new(w: i32, h: i32, dpi: f32, shared: Rc<Shared>) -> DesktopDisplay {
        DesktopDisplay { panel: Rc::new(RefCell::new(Canvas::new(w, h))), dpi, shared }
    }

    /// The panel as the reader would see it, turned to the current rotation.
    pub fn view(&self) -> Canvas {
        view(&self.panel.borrow(), self.shared.rotation.get())
    }

    pub fn save_png(&self, path: &Path) -> io::Result<()> {
        save_png(&self.view(), path)
    }
}

/// `panel` turned to `r`, as the reader sees it.
pub fn view(panel: &Canvas, r: u8) -> Canvas {
    {
        let base = (panel.w, panel.h);
        let (w, h) = device::canvas_size(base, r);
        let mut out = Canvas::new(w, h);
        for y in 0..h {
            for x in 0..w {
                let (bx, by) = device::canvas_to_base(base, r, x, y);
                out.px[(y * w + x) as usize] = panel.get(bx, by);
            }
        }
        out
    }
}

pub fn save_png(v: &Canvas, path: &Path) -> io::Result<()> {
    {
        let v = v.clone();
        let img = image::GrayImage::from_raw(v.w as u32, v.h as u32, v.px).ok_or_else(|| io::Error::other("bad canvas"))?;
        if let Some(d) = path.parent() {
            std::fs::create_dir_all(d)?;
        }
        img.save(path).map_err(io::Error::other)
    }
}

impl Display for DesktopDisplay {
    fn base_size(&self) -> (i32, i32) {
        let p = self.panel.borrow();
        (p.w, p.h)
    }
    fn dpi(&self) -> f32 {
        self.dpi
    }
    fn present(&mut self, canvas: &Canvas, rotation: u8, region: Rect, mode: Refresh) -> io::Result<()> {
        let base = self.base_size();
        let mut guard = self.panel.borrow_mut();
        let panel = &mut *guard;
        device::for_each_base_pixel(canvas, base, rotation, region, |x, y, row| {
            let start = (y * base.0 + x) as usize;
            for (i, v) in row.enumerate() {
                // The panel shows 16 greys.
                panel.px[start + i] = ((v as u32 + 8) / 17 * 17).min(255) as u8;
            }
        });
        self.shared.rotation.set(rotation);
        self.shared.refreshes.borrow_mut().push((region, mode));
        Ok(())
    }
}

/// One step of an input script, in canvas coordinates of the current rotation.
#[derive(Clone, Debug)]
pub enum Step {
    Tap(i32, i32),
    /// From (x0, y0) to (x1, y1).
    Swipe(i32, i32, i32, i32),
    Key(Key),
    Orientation(u8),
    /// Let time pass with nothing happening.
    Wait(u64),
    /// Let real time pass too, for work on other threads (a sync); the scripted clocks move
    /// on by the same amount.
    Sleep(u64),
    /// Tap the middle of what does the action whose `{:?}` starts with this; panics if
    /// nothing on screen does.
    TapOn(String),
    /// Called with the display when reached, e.g. to save a screenshot.
    Mark(String),
}

pub struct ScriptInput {
    steps: VecDeque<Step>,
    pending: VecDeque<RawEvent>,
    shared: Rc<Shared>,
    base: (i32, i32),
    /// Marks reached, in order; the harness saves a picture for each.
    pub marks: Rc<RefCell<Vec<String>>>,
    /// Called at each mark with its name.
    pub on_mark: Option<OnMark>,
}

/// Called at a mark with its name.
pub type OnMark = Box<dyn FnMut(&str)>;

impl ScriptInput {
    pub fn new(steps: Vec<Step>, base: (i32, i32), shared: Rc<Shared>) -> ScriptInput {
        ScriptInput { steps: steps.into(), pending: VecDeque::new(), shared, base, marks: Rc::new(RefCell::new(Vec::new())), on_mark: None }
    }

    fn to_base(&self, x: i32, y: i32) -> (i32, i32) {
        device::canvas_to_base(self.base, self.shared.rotation.get(), x, y)
    }

    fn advance(&self, ms: u64) {
        self.shared.mono_ms.set(self.shared.mono_ms.get() + ms);
        self.shared.wall_ms.set(self.shared.wall_ms.get() + ms as i64);
    }
}

impl Input for ScriptInput {
    fn next(&mut self, timeout: Duration) -> Option<RawEvent> {
        loop {
            if let Some(e) = self.pending.pop_front() {
                self.advance(40);
                return Some(e);
            }
            match self.steps.pop_front() {
                None => {
                    // The script is over: the reader leaves.
                    return Some(RawEvent::Quit);
                }
                Some(Step::Tap(x, y)) => {
                    let (bx, by) = self.to_base(x, y);
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Down, x: bx, y: by });
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Up, x: bx, y: by });
                }
                Some(Step::Swipe(x0, y0, x1, y1)) => {
                    let (ax, ay) = self.to_base(x0, y0);
                    let (bx, by) = self.to_base(x1, y1);
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Down, x: ax, y: ay });
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Move, x: (ax + bx) / 2, y: (ay + by) / 2 });
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Up, x: bx, y: by });
                }
                Some(Step::Key(k)) => self.pending.push_back(RawEvent::Key(k)),
                Some(Step::Orientation(r)) => self.pending.push_back(RawEvent::Orientation(r)),
                Some(Step::TapOn(label)) => {
                    let hits = self.shared.hits.borrow();
                    let Some((r, _)) = hits.iter().rev().find(|(_, a)| a.starts_with(&label)) else {
                        panic!("nothing on screen does {label}; there is {:?}", hits.iter().map(|h| &h.1).collect::<Vec<_>>());
                    };
                    let (x, y) = (r.x + r.w / 2, r.y + r.h / 2);
                    drop(hits);
                    let (bx, by) = self.to_base(x, y);
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Down, x: bx, y: by });
                    self.pending.push_back(RawEvent::Touch { phase: Phase::Up, x: bx, y: by });
                }
                Some(Step::Sleep(ms)) => {
                    let step = (timeout.as_millis() as u64).max(1).min(ms);
                    std::thread::sleep(Duration::from_millis(step));
                    self.advance(step);
                    if ms > step {
                        self.steps.push_front(Step::Sleep(ms - step));
                    }
                    return None;
                }
                Some(Step::Wait(ms)) => {
                    // Time passes in steps no longer than the reader's timeout, so idle checks run.
                    let step = (timeout.as_millis() as u64).max(1).min(ms);
                    self.advance(step);
                    if ms > step {
                        self.steps.push_front(Step::Wait(ms - step));
                    }
                    return None;
                }
                Some(Step::Mark(name)) => {
                    self.marks.borrow_mut().push(name.clone());
                    if let Some(f) = self.on_mark.as_mut() {
                        f(&name);
                    }
                }
            }
        }
    }
}

pub struct ScriptClock(pub Rc<Shared>);

impl Clock for ScriptClock {
    fn mono_ms(&self) -> u64 {
        self.0.mono_ms.get()
    }
    fn wall_ms(&self) -> i64 {
        self.0.wall_ms.get()
    }
}
