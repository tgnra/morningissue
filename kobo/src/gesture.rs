//! Taps and swipes from finger down/up, with distances in physical units so they feel the same
//! on every screen density. Nothing follows the finger: e-ink acts when it lifts.

use crate::device::Phase;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dir {
    Left,
    Right,
    Up,
    Down,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Gesture {
    Tap { x: i32, y: i32 },
    /// The direction the finger moved, from where it went down.
    Swipe { dir: Dir, x: i32, y: i32 },
}

pub struct Recognizer {
    dpi: f32,
    down: Option<(i32, i32)>,
    last: (i32, i32),
}

impl Recognizer {
    pub fn new(dpi: f32) -> Recognizer {
        Recognizer { dpi, down: None, last: (0, 0) }
    }

    /// Feeds one touch in canvas coordinates; returns a gesture when the finger lifts.
    pub fn feed(&mut self, phase: Phase, x: i32, y: i32) -> Option<Gesture> {
        match phase {
            Phase::Down => {
                self.down = Some((x, y));
                self.last = (x, y);
                None
            }
            Phase::Move => {
                if self.down.is_none() {
                    self.down = Some((x, y));
                }
                self.last = (x, y);
                None
            }
            Phase::Up => {
                let (sx, sy) = self.down.take()?;
                // Some panels report the lift at (0, 0); keep the last real position.
                let (ex, ey) = if x == 0 && y == 0 { self.last } else { (x, y) };
                let (dx, dy) = ((ex - sx) as f32, (ey - sy) as f32);
                let dist = (dx * dx + dy * dy).sqrt() / self.dpi;
                if dist < 0.15 {
                    Some(Gesture::Tap { x: sx, y: sy })
                } else if dist >= 0.3 {
                    let dir = if dx.abs() >= dy.abs() {
                        if dx < 0.0 { Dir::Left } else { Dir::Right }
                    } else if dy < 0.0 {
                        Dir::Up
                    } else {
                        Dir::Down
                    };
                    Some(Gesture::Swipe { dir, x: sx, y: sy })
                } else {
                    None
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn taps_and_swipes() {
        let mut r = Recognizer::new(300.0);
        r.feed(Phase::Down, 100, 100);
        r.feed(Phase::Move, 110, 102);
        assert_eq!(r.feed(Phase::Up, 110, 102), Some(Gesture::Tap { x: 100, y: 100 }));
        r.feed(Phase::Down, 900, 800);
        r.feed(Phase::Move, 500, 820);
        assert_eq!(r.feed(Phase::Up, 0, 0), Some(Gesture::Swipe { dir: Dir::Left, x: 900, y: 800 }));
        assert_eq!(r.feed(Phase::Up, 5, 5), None, "an up without a down");
    }
}
