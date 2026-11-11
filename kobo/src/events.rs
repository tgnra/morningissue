//! What the reader did, as protocol events appended durably to `data/events.jsonl`.
//!
//! - `opened` when an article is opened that the phone has not seen opened;
//! - `readFraction` on leaving an article, when it got further than before;
//! - `read` on leaving, once the app's rule is met (`ReadRule`: 70% through, or open for half
//!   the reading time) and the phone does not already have it read;
//! - `liked` / `saved` only when toggled here;
//! - `pageView` with the time a page was on screen, measured on the monotonic clock as
//!   `PageViews.kt` does, and only while the page itself was showing;
//! - `editionOpened` and `editionFinished` (the back page reached).

use crate::model::{read_minutes, Bundle};
use crate::store::{Event, Store};
use crate::time;
use serde_json::Value;
use std::io;

/// `RankingConfig.readScrollFraction` and `readDwellFactor`.
pub const READ_FRACTION: f64 = 0.7;
pub const READ_DWELL_FACTOR: f64 = 0.5;

pub fn dwell_threshold_ms(word_count: i64) -> u64 {
    (read_minutes(word_count) as f64 * 60_000.0 * READ_DWELL_FACTOR) as u64
}

pub fn is_read(fraction: f64, dwell_ms: u64, word_count: i64) -> bool {
    fraction >= READ_FRACTION || dwell_ms >= dwell_threshold_ms(word_count)
}

pub struct Recorder {
    next_seq: i64,
    clock_offset_ms: i64,
}

impl Recorder {
    pub fn new(store: &Store, state: &crate::store::State) -> io::Result<Recorder> {
        Ok(Recorder { next_seq: store.next_seq(state)?, clock_offset_ms: state.clock_offset_ms })
    }

    pub fn next_seq(&self) -> i64 {
        self.next_seq
    }

    /// Appends one event; returns it.
    pub fn record(&mut self, store: &Store, wall_ms: i64, kind: &str, fill: impl FnOnce(&mut Event)) -> io::Result<Event> {
        let mut e = Event { seq: self.next_seq, kind: kind.into(), article_id: None, edition_id: None, page: None, value: None, ms: None, at: time::format_iso(wall_ms + self.clock_offset_ms) };
        fill(&mut e);
        store.append_event(&e)?;
        self.next_seq += 1;
        Ok(e)
    }
}

/// Applies unsent events to stored bundles, so what was done on the Kobo since the last sync
/// shows (ticks, Like and Save) before the phone has merged it.
pub fn apply(bundles: &mut [Bundle], events: &[Event]) {
    for e in events {
        apply_one(bundles, e);
    }
}

pub fn apply_one(bundles: &mut [Bundle], e: &Event) {
    if let Some(id) = e.edition_id {
        if e.kind == "editionFinished" {
            for b in bundles.iter_mut().filter(|b| b.id == id) {
                b.finished_at.get_or_insert_with(|| e.at.clone());
            }
        }
    }
    let Some(aid) = e.article_id else { return };
    for b in bundles.iter_mut() {
        let Some(a) = b.article_mut(aid) else { continue };
        let g = &mut a.engagement;
        match e.kind.as_str() {
            "opened" => {
                g.opened_at.get_or_insert_with(|| e.at.clone());
            }
            "readFraction" => {
                if let Some(v) = e.value.as_ref().and_then(Value::as_f64) {
                    g.read_fraction = g.read_fraction.max(v.clamp(0.0, 1.0));
                }
            }
            "read" => {
                g.read_at.get_or_insert_with(|| e.at.clone());
            }
            "liked" => g.liked_at = (e.value.as_ref().and_then(Value::as_bool) == Some(true)).then(|| e.at.clone()),
            "saved" => g.saved_at = (e.value.as_ref().and_then(Value::as_bool) == Some(true)).then(|| e.at.clone()),
            _ => {}
        }
    }
}

/// Times the page on screen (`PageViewMeter`): told which page shows now, returns the page that
/// showed until now with its time, if any.
#[derive(Default)]
pub struct PageMeter {
    page: Option<(i64, i64)>,
    since: u64,
}

impl PageMeter {
    /// `page` is (editionId, page number), or None when no page of an edition is showing.
    pub fn show(&mut self, page: Option<(i64, i64)>, now_ms: u64) -> Option<((i64, i64), u64)> {
        if page == self.page {
            return None;
        }
        let ended = self.page.map(|p| (p, now_ms.saturating_sub(self.since)));
        self.page = page;
        self.since = now_ms;
        ended.filter(|(_, ms)| *ms > 0)
    }
}

/// One visit to an article.
pub struct ArticleVisit {
    pub edition_id: i64,
    pub article_id: i64,
    pub word_count: i64,
    /// What the phone already has.
    pub had_fraction: f64,
    pub had_read: bool,
    pub furthest: f64,
    pub dwell_ms: u64,
    /// When the article last came on screen (None while it is not showing).
    pub showing_since: Option<u64>,
}

impl ArticleVisit {
    pub fn pause(&mut self, now: u64) {
        if let Some(s) = self.showing_since.take() {
            self.dwell_ms += now.saturating_sub(s);
        }
    }
    pub fn resume(&mut self, now: u64) {
        self.showing_since.get_or_insert(now);
    }
    /// How far through: screen `pos` of `count`, as the app's scroll fraction (0 when it all
    /// fits on one screen, 1 at the last).
    pub fn reached(&mut self, pos: usize, count: usize) {
        let f = if count <= 1 { 0.0 } else { pos as f64 / (count - 1) as f64 };
        self.furthest = self.furthest.max(f.clamp(0.0, 1.0));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rules() {
        assert!(is_read(0.7, 0, 1000));
        assert!(!is_read(0.69, 0, 1000));
        // 1000 words is 5 minutes; half of that open counts.
        assert_eq!(dwell_threshold_ms(1000), 150_000);
        assert!(is_read(0.0, 150_000, 1000));
        let mut m = PageMeter::default();
        assert_eq!(m.show(Some((1, 1)), 100), None);
        assert_eq!(m.show(Some((1, 1)), 200), None);
        assert_eq!(m.show(Some((1, 2)), 1100), Some(((1, 1), 1000)));
        assert_eq!(m.show(None, 1100), None, "no time, no event");
    }
}
