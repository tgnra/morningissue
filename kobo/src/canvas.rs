//! An 8-bit grey picture of the screen as the reader sees it (0 black, 255 white), in the
//! current orientation. Backends turn it into whatever the panel wants.

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl Rect {
    pub const fn new(x: i32, y: i32, w: i32, h: i32) -> Rect {
        Rect { x, y, w, h }
    }
    pub fn contains(&self, px: i32, py: i32) -> bool {
        px >= self.x && py >= self.y && px < self.x + self.w && py < self.y + self.h
    }
    pub fn right(&self) -> i32 {
        self.x + self.w
    }
    pub fn bottom(&self) -> i32 {
        self.y + self.h
    }
    pub fn is_empty(&self) -> bool {
        self.w <= 0 || self.h <= 0
    }
    pub fn intersect(&self, o: &Rect) -> Rect {
        let x = self.x.max(o.x);
        let y = self.y.max(o.y);
        let r = self.right().min(o.right());
        let b = self.bottom().min(o.bottom());
        Rect::new(x, y, (r - x).max(0), (b - y).max(0))
    }
    pub fn union(&self, o: &Rect) -> Rect {
        if self.is_empty() {
            return *o;
        }
        if o.is_empty() {
            return *self;
        }
        let x = self.x.min(o.x);
        let y = self.y.min(o.y);
        Rect::new(x, y, self.right().max(o.right()) - x, self.bottom().max(o.bottom()) - y)
    }
    /// Grown by `d` on every side.
    pub fn inflate(&self, d: i32) -> Rect {
        Rect::new(self.x - d, self.y - d, self.w + 2 * d, self.h + 2 * d)
    }
}

pub const BLACK: u8 = 0;
pub const WHITE: u8 = 255;

#[derive(Clone)]
pub struct Canvas {
    pub w: i32,
    pub h: i32,
    pub px: Vec<u8>,
    /// Drawing outside this is ignored.
    pub clip: Rect,
}

impl Canvas {
    pub fn new(w: i32, h: i32) -> Canvas {
        Canvas { w, h, px: vec![WHITE; (w.max(0) * h.max(0)) as usize], clip: Rect::new(0, 0, w, h) }
    }

    pub fn bounds(&self) -> Rect {
        Rect::new(0, 0, self.w, self.h)
    }

    pub fn clear(&mut self, v: u8) {
        self.px.fill(v);
    }

    #[inline]
    pub fn get(&self, x: i32, y: i32) -> u8 {
        self.px[(y * self.w + x) as usize]
    }

    #[inline]
    pub fn set(&mut self, x: i32, y: i32, v: u8) {
        if self.clip.contains(x, y) {
            self.px[(y * self.w + x) as usize] = v;
        }
    }

    /// Blends `v` over the pixel with coverage `a` (0..255).
    #[inline]
    pub fn blend(&mut self, x: i32, y: i32, v: u8, a: u8) {
        if a == 0 || !self.clip.contains(x, y) {
            return;
        }
        let i = (y * self.w + x) as usize;
        let d = self.px[i] as u32;
        let a = a as u32;
        self.px[i] = ((v as u32 * a + d * (255 - a) + 127) / 255) as u8;
    }

    pub fn fill(&mut self, r: Rect, v: u8) {
        let r = r.intersect(&self.clip);
        for y in r.y..r.bottom() {
            let row = (y * self.w) as usize;
            self.px[row + r.x as usize..row + r.right() as usize].fill(v);
        }
    }

    /// A rectangle's outline, `t` pixels thick, inside `r`.
    pub fn frame(&mut self, r: Rect, t: i32, v: u8) {
        self.fill(Rect::new(r.x, r.y, r.w, t), v);
        self.fill(Rect::new(r.x, r.bottom() - t, r.w, t), v);
        self.fill(Rect::new(r.x, r.y, t, r.h), v);
        self.fill(Rect::new(r.right() - t, r.y, t, r.h), v);
    }

    pub fn hline(&mut self, x: i32, y: i32, w: i32, t: i32, v: u8) {
        self.fill(Rect::new(x, y, w, t.max(1)), v);
    }

    pub fn vline(&mut self, x: i32, y: i32, h: i32, t: i32, v: u8) {
        self.fill(Rect::new(x, y, t.max(1), h), v);
    }

    /// A dotted rule, as used between briefs.
    pub fn dotted(&mut self, x: i32, y: i32, w: i32, gap: i32, t: i32, v: u8) {
        let mut cx = x;
        while cx < x + w {
            self.fill(Rect::new(cx, y, t, t), v);
            cx += gap.max(t + 1);
        }
    }

    /// An anti-aliased line of width `t` from (x0, y0) to (x1, y1), for ticks and arrows.
    pub fn stroke(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, t: f32, v: u8) {
        let minx = (x0.min(x1) - t).floor() as i32;
        let maxx = (x0.max(x1) + t).ceil() as i32;
        let miny = (y0.min(y1) - t).floor() as i32;
        let maxy = (y0.max(y1) + t).ceil() as i32;
        let (dx, dy) = (x1 - x0, y1 - y0);
        let len2 = (dx * dx + dy * dy).max(1e-6);
        let half = t / 2.0;
        for y in miny..=maxy {
            for x in minx..=maxx {
                let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
                let u = (((px - x0) * dx + (py - y0) * dy) / len2).clamp(0.0, 1.0);
                let (cx, cy) = (x0 + u * dx, y0 + u * dy);
                let d = ((px - cx).powi(2) + (py - cy).powi(2)).sqrt();
                let cov = (half + 0.5 - d).clamp(0.0, 1.0);
                if cov > 0.0 {
                    self.blend(x, y, v, (cov * 255.0) as u8);
                }
            }
        }
    }

    /// A filled circle, anti-aliased.
    pub fn disc(&mut self, cx: f32, cy: f32, r: f32, v: u8) {
        for y in (cy - r - 1.0) as i32..=(cy + r + 1.0) as i32 {
            for x in (cx - r - 1.0) as i32..=(cx + r + 1.0) as i32 {
                let d = ((x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2)).sqrt();
                let cov = (r + 0.5 - d).clamp(0.0, 1.0);
                if cov > 0.0 {
                    self.blend(x, y, v, (cov * 255.0) as u8);
                }
            }
        }
    }

    /// A check mark fitting a `size` square with its top-left at (x, y).
    pub fn tick(&mut self, x: f32, y: f32, size: f32, v: u8) {
        let t = (size / 6.0).max(1.5);
        self.stroke(x + size * 0.12, y + size * 0.55, x + size * 0.4, y + size * 0.82, t, v);
        self.stroke(x + size * 0.4, y + size * 0.82, x + size * 0.9, y + size * 0.18, t, v);
    }

    /// A chevron pointing left (`dir` < 0) or right, centred at (cx, cy).
    pub fn chevron(&mut self, cx: f32, cy: f32, size: f32, dir: f32, v: u8) {
        let t = (size / 5.0).max(1.5);
        // The tip is on the side `dir` points to.
        let w = size * 0.3 * dir.signum();
        self.stroke(cx - w, cy - size * 0.5, cx + w, cy, t, v);
        self.stroke(cx + w, cy, cx - w, cy + size * 0.5, t, v);
    }

    /// Copies `src` with its top-left at (x, y).
    pub fn blit(&mut self, src: &Canvas, x: i32, y: i32) {
        for sy in 0..src.h {
            for sx in 0..src.w {
                self.set(x + sx, y + sy, src.get(sx, sy));
            }
        }
    }

    pub fn region(&self, r: Rect) -> Canvas {
        let r = r.intersect(&self.bounds());
        let mut out = Canvas::new(r.w, r.h);
        for y in 0..r.h {
            let src = ((r.y + y) * self.w + r.x) as usize;
            let dst = (y * r.w) as usize;
            out.px[dst..dst + r.w as usize].copy_from_slice(&self.px[src..src + r.w as usize]);
        }
        out
    }

    /// The smallest rectangle covering every pixel that differs from `other`, or None.
    pub fn diff(&self, other: &Canvas) -> Option<Rect> {
        if self.w != other.w || self.h != other.h {
            return Some(self.bounds());
        }
        let (mut x0, mut y0, mut x1, mut y1) = (i32::MAX, i32::MAX, -1, -1);
        for y in 0..self.h {
            let row = (y * self.w) as usize;
            let a = &self.px[row..row + self.w as usize];
            let b = &other.px[row..row + self.w as usize];
            if a == b {
                continue;
            }
            let first = a.iter().zip(b).position(|(p, q)| p != q).unwrap() as i32;
            let last = a.iter().zip(b).rposition(|(p, q)| p != q).unwrap() as i32;
            x0 = x0.min(first);
            x1 = x1.max(last);
            y0 = y0.min(y);
            y1 = y1.max(y);
        }
        (x1 >= 0).then(|| Rect::new(x0, y0, x1 - x0 + 1, y1 - y0 + 1))
    }
}
