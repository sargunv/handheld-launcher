//! `SYSTEM.CNF`, the file at the root of a PlayStation or PlayStation 2 disc
//! that names the executable it boots: `BOOT` on a PlayStation disc, `BOOT2`
//! on a PlayStation 2 one, which also gives the disc's version and video
//! mode.
//!
//! - <https://psx-spx.consoledev.net/cdromfileformats/#cdrom-file-systemcnf>

/// Which console's boot line the file has.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Console {
    /// `BOOT`.
    PlayStation,
    /// `BOOT2`.
    PlayStation2,
}

impl core::fmt::Display for Console {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            Self::PlayStation => "PlayStation",
            Self::PlayStation2 => "PlayStation 2",
        })
    }
}

/// A disc's `SYSTEM.CNF`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SystemCnf {
    pub console: Console,
    /// The boot line's value, such as `cdrom0:\SLUS_201.52;1`.
    pub boot: String,
    /// The executable's file name, such as `SLUS_201.52`.
    pub executable: String,
    /// `VER`, the disc's version, such as `1.00`.
    pub version: Option<String>,
    /// `VMODE`, the video mode: `NTSC` or `PAL`.
    pub video_mode: Option<String>,
}

impl SystemCnf {
    /// The serial the executable's name holds, as Sony prints it on the
    /// disc: `SLUS_201.52` holds `SLUS-20152`. Four letters, then five digits
    /// that a dot may split. None when the name isn't one.
    pub fn serial(&self) -> Option<String> {
        let name = self.executable.as_bytes();
        let (prefix, rest) = name.split_at_checked(4)?;
        if !prefix.iter().all(u8::is_ascii_alphabetic) {
            return None;
        }
        let rest = rest.strip_prefix(b"_").or(rest.strip_prefix(b"-"))?;
        let digits: Vec<u8> = rest.iter().copied().filter(|&c| c != b'.').collect();
        let dots = rest.len() - digits.len();
        if digits.len() != 5 || dots > 1 || !digits.iter().all(u8::is_ascii_digit) {
            return None;
        }
        Some(format!(
            "{}-{}",
            String::from_utf8_lossy(prefix).to_ascii_uppercase(),
            String::from_utf8_lossy(&digits)
        ))
    }
}

/// Parses a `SYSTEM.CNF`. None when it has no boot line.
pub fn parse(cnf: &[u8]) -> Option<SystemCnf> {
    let text = String::from_utf8_lossy(cnf);
    let mut boot = None;
    let mut boot2 = None;
    let mut version = None;
    let mut video_mode = None;
    for line in text.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let value = value.trim().to_owned();
        match key.trim() {
            "BOOT" => boot = Some(value),
            "BOOT2" => boot2 = Some(value),
            "VER" => version = Some(value),
            "VMODE" => video_mode = Some(value),
            _ => {}
        }
    }
    let (console, boot) = match (boot2, boot) {
        (Some(boot2), _) => (Console::PlayStation2, boot2),
        (None, Some(boot)) => (Console::PlayStation, boot),
        (None, None) => return None,
    };
    let file = boot.rsplit([':', '\\', '/']).next().unwrap_or(&boot);
    let executable = file.split(';').next().unwrap_or(file).trim().to_owned();
    Some(SystemCnf {
        console,
        boot,
        executable,
        version,
        video_mode,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_playstation_2_disc() {
        let cnf =
            parse(b"BOOT2 = cdrom0:\\SCUS_971.24;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n").unwrap();
        assert_eq!(cnf.console, Console::PlayStation2);
        assert_eq!(cnf.boot, "cdrom0:\\SCUS_971.24;1");
        assert_eq!(cnf.executable, "SCUS_971.24");
        assert_eq!(cnf.version.as_deref(), Some("1.00"));
        assert_eq!(cnf.video_mode.as_deref(), Some("NTSC"));
        assert_eq!(cnf.serial().as_deref(), Some("SCUS-97124"));
    }

    #[test]
    fn parses_a_playstation_disc() {
        let cnf = parse(b"BOOT=cdrom:\\SLUS_005.94;1\nTCB=4\nEVENT=10\nSTACK=801FFFF0\n").unwrap();
        assert_eq!(cnf.console, Console::PlayStation);
        assert_eq!(cnf.executable, "SLUS_005.94");
        assert_eq!(cnf.version, None);
        assert_eq!(cnf.serial().as_deref(), Some("SLUS-00594"));
    }

    #[test]
    fn reads_serials_in_other_shapes() {
        let serial = |boot: &str| {
            parse(format!("BOOT2 = {boot}").as_bytes())
                .unwrap()
                .serial()
        };
        assert_eq!(
            serial("cdrom0:\\slus_202.65;1").as_deref(),
            Some("SLUS-20265")
        );
        assert_eq!(
            serial("cdrom0:\\SLUS_00.220;1").as_deref(),
            Some("SLUS-00220")
        );
        assert_eq!(
            serial("cdrom0:\\GAME\\SLES_123.45;1").as_deref(),
            Some("SLES-12345")
        );
        assert_eq!(serial("cdrom0:\\MAIN.ELF;1"), None);
        assert_eq!(parse(b"VMODE = PAL"), None);
    }
}
