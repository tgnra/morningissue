//! Whether the Kobo is on a network, from what the kernel says: a default route through an
//! interface that has a carrier. The reader never turns Wi-Fi on; it uses the connection
//! Nickel left up, and when there is none it shows "Offline" and never tries to sync.

use std::path::Path;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NetStatus {
    Online {
        iface: String,
        /// The Kobo's own address, when it can be told.
        ip: Option<String>,
        /// The network's name, from wpa_supplicant, when it answers.
        ssid: Option<String>,
    },
    Offline,
}

impl NetStatus {
    pub fn online(&self) -> bool {
        matches!(self, NetStatus::Online { .. })
    }

    /// For the Settings screen: "Connected to Home (192.168.1.31)" or "Not connected".
    pub fn describe(&self) -> String {
        match self {
            NetStatus::Online { ssid: Some(s), ip: Some(ip), .. } => format!("Connected to {s} ({ip})"),
            NetStatus::Online { ssid: Some(s), .. } => format!("Connected to {s}"),
            NetStatus::Online { ip: Some(ip), iface, .. } => format!("Connected on {iface} ({ip})"),
            NetStatus::Online { iface, .. } => format!("Connected on {iface}"),
            NetStatus::Offline => "Not connected".into(),
        }
    }
}

/// The interface of the default route in `/proc/net/route`, if any (not the loopback).
pub fn default_route_iface(route_table: &str) -> Option<String> {
    route_table.lines().skip(1).find_map(|l| {
        let mut f = l.split_whitespace();
        let iface = f.next()?;
        let dest = f.next()?;
        (dest == "00000000" && iface != "lo").then(|| iface.to_string())
    })
}

/// Reads the kernel's view: `/proc/net/route` and `/sys/class/net/<iface>/carrier`.
pub fn probe() -> NetStatus {
    let routes = std::fs::read_to_string("/proc/net/route").unwrap_or_default();
    let Some(iface) = default_route_iface(&routes) else { return NetStatus::Offline };
    let carrier = std::fs::read_to_string(Path::new("/sys/class/net").join(&iface).join("carrier")).map(|s| s.trim() == "1").unwrap_or(true);
    if !carrier {
        return NetStatus::Offline;
    }
    NetStatus::Online { ip: local_ip(), ssid: ssid(&iface), iface }
}

/// The address the kernel would send from: a UDP socket "connected" to a LAN address sends
/// nothing but picks its source.
fn local_ip() -> Option<String> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.0.2.1:9").ok()?;
    let ip = s.local_addr().ok()?.ip();
    (!ip.is_unspecified()).then(|| ip.to_string())
}

/// The SSID from `wpa_cli status`, which talks to the wpa_supplicant Nickel started.
fn ssid(iface: &str) -> Option<String> {
    let out = std::process::Command::new("wpa_cli").args(["-i", iface, "status"]).stdin(std::process::Stdio::null()).stderr(std::process::Stdio::null()).output().ok()?;
    String::from_utf8_lossy(&out.stdout).lines().find_map(|l| l.strip_prefix("ssid=").map(str::to_owned)).filter(|s| !s.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_default_route() {
        let table = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\n\
                     wlan0\t0000A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\n\
                     wlan0\t00000000\t0100A8C0\t0003\t0\t0\t0\t00000000\n";
        assert_eq!(default_route_iface(table), Some("wlan0".into()));
        assert_eq!(default_route_iface("Iface\tDestination\nwlan0\t0000A8C0\n"), None);
        assert_eq!(default_route_iface("Iface\tDestination\nlo\t00000000\n"), None);
    }
}
