//! Runs `launch/launch.sh` against a fake Kobo: a directory standing in for the root file
//! system (`MORNINGISSUE_ROOT`), busybox-like stubs on the PATH that log their arguments, a
//! fake `morningissue` binary whose `read` exits, panics, is killed or hangs as asked, and a
//! fake Nickel that records where its output goes.
//!
//! The shell is `sh` from the PATH, or `MORNINGISSUE_TEST_SHELL`. To run it under busybox's
//! ash, which is what the Kobo runs:
//! `nix shell nixpkgs#rustup nixpkgs#busybox -c cargo test --test launch`.

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn shell() -> String {
    std::env::var("MORNINGISSUE_TEST_SHELL").unwrap_or_else(|_| "sh".into())
}

fn script(path: &Path, body: &str) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, format!("#!/bin/sh\n{body}\n")).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

struct Kobo {
    root: PathBuf,
    dir: PathBuf,
    bin: PathBuf,
}

impl Kobo {
    fn new(name: &str) -> Kobo {
        let base = std::env::temp_dir().join(format!("mi-launch-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&base);
        let root = base.join("root");
        let dir = root.join("mnt/onboard/.adds/morningissue");
        let bin = base.join("bin");
        for d in ["tmp", "etc/init.d", "proc/net", "sys/class/net/wlan0", "usr/local/Kobo"] {
            fs::create_dir_all(root.join(d)).unwrap();
        }
        fs::create_dir_all(dir.join("data")).unwrap();
        fs::create_dir_all(&bin).unwrap();
        fs::write(root.join("etc/resolv.conf"), "nameserver 192.168.1.1\n").unwrap();
        // Connected: a default route through wlan0, which has a carrier.
        fs::write(root.join("proc/net/route"), "Iface\tDestination\tGateway\nwlan0\t00000000\t0101A8C0\nwlan0\t0001A8C0\t00000000\n").unwrap();
        fs::write(root.join("sys/class/net/wlan0/carrier"), "1\n").unwrap();
        fs::write(root.join("proc/net/dev"), "Inter-|   Receive\n face |bytes\n  wlan0: 0 0\n").unwrap();
        fs::write(root.join("proc/modules"), "moal 1 0 - Live 0x0\nmlan 1 1 moal, Live 0x0\n").unwrap();
        fs::write(root.join("proc/mounts"), "/dev/mmcblk0p3 /mnt/onboard vfat rw 0 0\n").unwrap();
        let calls = root.join("calls.log");
        let c = calls.display();
        // Busybox-like stubs: they log what they were asked and succeed.
        for name in ["killall", "usleep", "sync", "umount", "udevadm", "dhcpcd", "wpa_cli", "ifconfig", "rmmod", "wlarm_le"] {
            script(&bin.join(name), &format!("echo \"{name} $*\" >>'{c}'"));
        }
        // Nickel is never found running, so the launcher does not wait for it; dhcpcd is.
        script(&bin.join("pidof"), "case \"$*\" in *dhcpcd*) exit 0 ;; esac\nexit 1");
        let sh = shell();
        if sh != "sh" {
            // wifi-off.sh is run as `sh`; make that the shell under test too.
            std::os::unix::fs::symlink(&sh, bin.join("sh")).unwrap();
        }
        // Nickel and hindenburg write down where their output goes, then stay up a while,
        // as the real ones do for the whole session.
        for name in ["nickel", "hindenburg"] {
            script(
                &root.join("usr/local/Kobo").join(name),
                &format!("echo \"{name} $*\" >>'{c}'\nreadlink /proc/$$/fd/1 >'{r}/{name}.out'\nreadlink /proc/$$/fd/2 >>'{r}/{name}.out'\nsleep 3", r = root.display()),
            );
        }
        script(&root.join("etc/init.d/on-animator.sh"), "echo 'sh: ON: unknown operand'");
        for f in ["launch.sh", "wifi-off.sh"] {
            fs::copy(manifest_dir().join("launch").join(f), dir.join(f)).unwrap();
        }
        script(
            &dir.join("morningissue"),
            &format!(
                r#"echo "bin $*" >>'{c}'
case "$1" in
    check) echo "Kobo Libra 2 (product 388)"; exit 0 ;;
    wifi-off) exit 0 ;;
    read)
        echo "read: started"
        case "$FAKE_READ" in
            panic)
                echo "read: panicked at src/app.rs:10:5: boom" >&2
                echo "panicked at src/app.rs:10:5: boom" >"$MORNINGISSUE_DIR/data/panic.txt"
                exit 70 ;;
            signal) kill -SEGV $$ ;;
            slow) sleep 2 ;;
            *) exit 0 ;;
        esac ;;
esac"#
            ),
        );
        Kobo { root, dir, bin }
    }

    fn command(&self, read: &str) -> Command {
        let mut cmd = Command::new(shell());
        cmd.arg(self.dir.join("launch.sh"))
            .env("MORNINGISSUE_ROOT", &self.root)
            .env("MORNINGISSUE_DIR", &self.dir)
            .env("PATH", format!("{}:{}", self.bin.display(), std::env::var("PATH").unwrap()))
            .env("WIFI_MODULE", "moal")
            .env("INTERFACE", "wlan0")
            .env("PLATFORM", "mx6sll-ntx")
            .env("FAKE_READ", read)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        cmd
    }

    fn launch(&self, read: &str) {
        let status = self.command(read).status().unwrap();
        assert!(status.success(), "launch.sh failed: {status}\n{}", self.log());
    }

    fn spawn(&self, read: &str) -> Child {
        self.command(read).spawn().unwrap()
    }

    fn log(&self) -> String {
        fs::read_to_string(self.dir.join("data/launch.log")).unwrap_or_default()
    }

    fn calls(&self) -> String {
        fs::read_to_string(self.root.join("calls.log")).unwrap_or_default()
    }

    fn lock(&self) -> PathBuf {
        self.root.join("tmp/morningissue.lock")
    }

    /// Waits for the fake Nickel to say where its output goes.
    fn nickel_out(&self) -> String {
        let path = self.root.join("nickel.out");
        let until = Instant::now() + Duration::from_secs(5);
        loop {
            if let Ok(s) = fs::read_to_string(&path) {
                if s.lines().count() >= 2 {
                    return s;
                }
            }
            assert!(Instant::now() < until, "nickel never started\n{}", self.log());
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    /// The processes that have `path` open.
    fn holders(path: &Path) -> Vec<String> {
        let mut out = Vec::new();
        for p in fs::read_dir("/proc").unwrap().flatten() {
            let Ok(fds) = fs::read_dir(p.path().join("fd")) else { continue };
            for fd in fds.flatten() {
                if fs::read_link(fd.path()).is_ok_and(|t| t == path) {
                    let cmd = fs::read_to_string(p.path().join("cmdline")).unwrap_or_default().replace('\0', " ");
                    out.push(cmd);
                }
            }
        }
        out
    }
}

/// A PID that belonged to a process that has exited.
fn dead_pid() -> u32 {
    let mut c = Command::new("true").spawn().unwrap();
    let pid = c.id();
    c.wait().unwrap();
    pid
}

#[test]
fn exit_brings_nickel_back_and_frees_the_lock() {
    let k = Kobo::new("exit");
    k.launch("exit");
    let log = k.log();
    assert!(log.contains("wi-fi connected on wlan0; keeping it"), "{log}");
    assert!(log.contains("reader exited"), "{log}");
    assert!(log.contains("nickel restarted"), "{log}");
    assert!(!k.lock().exists(), "the lock is released");
    // Nickel and hindenburg write to /dev/null, never to the launcher's log.
    assert_eq!(k.nickel_out(), "/dev/null\n/dev/null\n");
    let calls = k.calls();
    assert!(calls.contains("nickel -platform kobo -skipFontLoad"), "{calls}");
    // Wi-Fi went down the way a boot leaves it, modules and all.
    for c in ["dhcpcd -d -k wlan0", "wpa_cli -i wlan0 terminate", "ifconfig wlan0 down", "rmmod moal", "rmmod mlan", "bin wifi-off"] {
        assert!(calls.contains(c), "{c} in {calls}");
    }
    // Nothing that outlives the launcher holds the log open (Nickel is still up here).
    let log_path = k.dir.join("data/launch.log");
    assert_eq!(Kobo::holders(&log_path), Vec::<String>::new());
    assert!(!log.contains("unknown operand"), "the boot animation's noise is thrown away");

    // And it opens again at once.
    k.launch("exit");
    assert_eq!(k.log().matches("nickel restarted").count(), 2, "{}", k.log());
    assert!(!k.log().contains("already running"));
}

#[test]
fn a_stale_lock_never_blocks_a_launch() {
    // A lock left by a launcher that was killed.
    let k = Kobo::new("stale");
    fs::create_dir_all(k.lock()).unwrap();
    fs::write(k.lock().join("pid"), format!("{}\n", dead_pid())).unwrap();
    k.launch("exit");
    let log = k.log();
    assert!(log.contains("removing a stale lock"), "{log}");
    assert!(log.contains("nickel restarted"), "{log}");
    assert!(!k.lock().exists());

    // The lock 0.2.3 left behind: an empty directory, no PID.
    let k = Kobo::new("stale-empty");
    fs::create_dir_all(k.lock()).unwrap();
    k.launch("exit");
    assert!(k.log().contains("removing a stale lock"), "{}", k.log());
    assert!(k.log().contains("nickel restarted"));

    // A PID reused by a process that is not the launcher.
    let k = Kobo::new("stale-reused");
    let mut other = Command::new("sleep").arg("5").spawn().unwrap();
    fs::create_dir_all(k.lock()).unwrap();
    fs::write(k.lock().join("pid"), format!("{}\n", other.id())).unwrap();
    k.launch("exit");
    assert!(k.log().contains("removing a stale lock"), "{}", k.log());
    other.kill().unwrap();
    other.wait().unwrap();
}

#[test]
fn a_running_launcher_keeps_the_lock() {
    let k = Kobo::new("running");
    let mut first = k.spawn("slow");
    // Wait until the first one is reading.
    let until = Instant::now() + Duration::from_secs(5);
    while !k.log().contains("read: started") {
        assert!(Instant::now() < until, "{}", k.log());
        std::thread::sleep(Duration::from_millis(20));
    }
    k.launch("exit");
    let log = k.log();
    assert!(log.contains("already running"), "{log}");
    assert!(first.wait().unwrap().success());
    let log = k.log();
    assert_eq!(log.matches("nickel restarted").count(), 1, "{log}");
    assert!(!k.lock().exists());
}

#[test]
fn a_crash_is_logged_and_nickel_comes_back() {
    let k = Kobo::new("panic");
    k.launch("panic");
    let log = k.log();
    assert!(log.contains("read: panicked at src/app.rs:10:5: boom"), "{log}");
    assert!(log.contains("reader crashed (exit code 70; panicked at src/app.rs:10:5: boom); bringing Nickel back"), "{log}");
    assert!(log.contains("nickel restarted"), "{log}");
    assert!(!k.lock().exists());
    // Never restarted automatically.
    assert_eq!(k.calls().matches("bin read").count(), 1);

    let k = Kobo::new("signal");
    k.launch("signal");
    let log = k.log();
    assert!(log.contains("reader crashed (killed by signal 11)"), "{log}");
    assert!(log.contains("nickel restarted"), "{log}");
    assert!(!k.lock().exists());
    assert_eq!(k.calls().matches("bin read").count(), 1);
    // The next tap works straight away.
    k.launch("exit");
    assert_eq!(k.log().matches("nickel restarted").count(), 2);
}

#[test]
fn a_killed_launcher_still_brings_nickel_back() {
    let k = Kobo::new("killed");
    let mut launcher = k.spawn("slow");
    let until = Instant::now() + Duration::from_secs(5);
    while !k.log().contains("read: started") {
        assert!(Instant::now() < until, "{}", k.log());
        std::thread::sleep(Duration::from_millis(20));
    }
    unsafe { libc::kill(launcher.id() as i32, libc::SIGTERM) };
    launcher.wait().unwrap();
    let log = k.log();
    assert!(log.contains("launcher interrupted; bringing Nickel back"), "{log}");
    assert!(log.contains("nickel restarted"), "{log}");
    assert!(!k.lock().exists());
}

#[test]
fn an_unsupported_model_leaves_nickel_alone() {
    let k = Kobo::new("unsupported");
    script(&k.dir.join("morningissue"), "echo 'Kobo Elipsa: not supported'; exit 3");
    k.launch("exit");
    let log = k.log();
    assert!(log.contains("unsupported device; not stopping Nickel"), "{log}");
    assert!(!k.calls().contains("killall"), "{}", k.calls());
    assert!(!log.contains("nickel restarted"));
    assert!(!k.lock().exists());
}
