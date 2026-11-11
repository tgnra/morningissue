//! UTC instants as the protocol writes them (`2026-11-11T06:30:00Z`), without a date crate.

use std::time::{SystemTime, UNIX_EPOCH};

/// Milliseconds since the Unix epoch by the system clock.
pub fn system_millis() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_millis() as i64,
        Err(e) => -(e.duration().as_millis() as i64),
    }
}

/// Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
pub fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let m = m as i64;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// The date for a count of days since 1970-01-01.
pub fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// Formats milliseconds since the epoch as an ISO-8601 UTC instant to the second.
pub fn format_iso(millis: i64) -> String {
    let secs = millis.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

/// Parses `YYYY-MM-DDTHH:MM:SS[.fff](Z|±HH:MM)` to milliseconds since the epoch.
pub fn parse_iso(s: &str) -> Option<i64> {
    let b = s.trim().as_bytes();
    if b.len() < 19 || b[4] != b'-' || b[7] != b'-' || (b[10] != b'T' && b[10] != b't' && b[10] != b' ') {
        return None;
    }
    let num = |r: std::ops::Range<usize>| -> Option<i64> { std::str::from_utf8(b.get(r)?).ok()?.parse().ok() };
    let (y, mo, d) = (num(0..4)?, num(5..7)?, num(8..10)?);
    let (h, mi, se) = (num(11..13)?, num(14..16)?, num(17..19)?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    let mut i = 19;
    let mut frac_ms = 0i64;
    if b.get(i) == Some(&b'.') {
        i += 1;
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        let digits = std::str::from_utf8(&b[start..i]).ok()?;
        let mut ms = String::from(digits);
        ms.truncate(3);
        while ms.len() < 3 {
            ms.push('0');
        }
        frac_ms = ms.parse().ok()?;
    }
    let offset_secs = match b.get(i) {
        Some(b'Z') | Some(b'z') | None => 0,
        Some(&sign) if sign == b'+' || sign == b'-' => {
            let oh = num(i + 1..i + 3)?;
            let om = if b.get(i + 3) == Some(&b':') { num(i + 4..i + 6)? } else { num(i + 3..i + 5).unwrap_or(0) };
            let o = oh * 3600 + om * 60;
            if sign == b'+' { o } else { -o }
        }
        _ => return None,
    };
    let days = days_from_civil(y, mo as u32, d as u32);
    Some(((days * 86_400 + h * 3600 + mi * 60 + se) - offset_secs) * 1000 + frac_ms)
}

/// Parses an HTTP `Date` header (`Tue, 11 Nov 2026 06:30:00 GMT`) to milliseconds since the epoch.
pub fn parse_http_date(s: &str) -> Option<i64> {
    let parts: Vec<&str> = s.split_whitespace().collect();
    if parts.len() < 5 {
        return None;
    }
    let d: u32 = parts[1].parse().ok()?;
    let m = MONTHS.iter().position(|n| n.eq_ignore_ascii_case(parts[2]))? as u32 + 1;
    let y: i64 = parts[3].parse().ok()?;
    let t: Vec<i64> = parts[4].split(':').map(|p| p.parse().ok()).collect::<Option<_>>()?;
    if t.len() != 3 {
        return None;
    }
    Some((days_from_civil(y, m, d) * 86_400 + t[0] * 3600 + t[1] * 60 + t[2]) * 1000)
}

pub const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
pub const MONTHS_LONG: [&str; 12] = [
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
];
pub const WEEKDAYS_LONG: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

/// A calendar date from the bundle (`2026-11-11`), for display.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Date {
    pub y: i64,
    pub m: u32,
    pub d: u32,
}

impl Date {
    pub fn parse(s: &str) -> Option<Date> {
        let b = s.trim();
        let mut it = b.splitn(3, '-');
        let y = it.next()?.parse().ok()?;
        let m = it.next()?.parse().ok()?;
        let d = it.next()?.get(0..2)?.parse().ok()?;
        if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
            return None;
        }
        Some(Date { y, m, d })
    }

    pub fn weekday(&self) -> usize {
        // 1970-01-01 was a Thursday (index 3 with Monday = 0).
        (days_from_civil(self.y, self.m, self.d) + 3).rem_euclid(7) as usize
    }

    /// "Wednesday 11 November 2026"
    pub fn long(&self) -> String {
        format!("{} {} {} {}", WEEKDAYS_LONG[self.weekday()], self.d, MONTHS_LONG[self.m as usize - 1], self.y)
    }

    /// "11 November"
    pub fn day_month(&self) -> String {
        format!("{} {}", self.d, MONTHS_LONG[self.m as usize - 1])
    }

    /// "11 Nov 2026"
    pub fn short(&self) -> String {
        format!("{} {} {}", self.d, MONTHS[self.m as usize - 1], self.y)
    }
}

/// "just now", "12 minutes ago", "3 hours ago", "yesterday", "4 days ago". Both instants come
/// from the same clock, so a wrong time zone on the device does not matter.
pub fn relative(then_ms: i64, now_ms: i64) -> String {
    let mins = (now_ms - then_ms).max(0) / 60_000;
    match mins {
        0 => "just now".into(),
        1 => "a minute ago".into(),
        2..=59 => format!("{mins} minutes ago"),
        60..=119 => "an hour ago".into(),
        120..=1439 => format!("{} hours ago", mins / 60),
        1440..=2879 => "yesterday".into(),
        _ => format!("{} days ago", mins / 1440),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iso_round_trip() {
        let ms = parse_iso("2026-11-11T06:30:00Z").unwrap();
        assert_eq!(format_iso(ms), "2026-11-11T06:30:00Z");
        assert_eq!(parse_iso("2026-11-11T07:30:00+01:00"), Some(ms));
        assert_eq!(parse_iso("2026-11-11T06:30:00.250Z"), Some(ms + 250));
        assert_eq!(parse_iso("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(parse_iso("garbage"), None);
    }

    #[test]
    fn http_date() {
        assert_eq!(parse_http_date("Wed, 11 Nov 2026 06:30:00 GMT"), parse_iso("2026-11-11T06:30:00Z"));
    }

    #[test]
    fn dates() {
        let d = Date::parse("2026-11-11").unwrap();
        assert_eq!(d.long(), "Wednesday 11 November 2026");
        assert_eq!(relative(0, 90 * 60_000), "an hour ago");
    }
}
