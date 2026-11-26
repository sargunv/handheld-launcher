//! What a game's own header says about the copy on the device, for the app to
//! show: [`rom_header_fields`] reads it with `rom-headers` and gives its
//! fields as labels and values, so the app's screens stay generic.

use std::fs::File;
use std::io::{self, BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use rom_headers::{Header, Platform};

/// A platform whose games carry a header the session reads.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HeaderPlatform {
    Nes,
    Snes,
    N64,
    /// The Game Boy and Game Boy Color.
    GameBoy,
    GameBoyAdvance,
    NintendoDs,
    Nintendo3ds,
    /// The GameCube and Wii.
    GameCube,
    /// The Master System and Game Gear.
    MasterSystem,
    /// The Mega Drive and 32X.
    MegaDrive,
    MegaCd,
    Saturn,
    Dreamcast,
    PlayStation,
    PlayStation2,
    Psp,
    Xbox,
}

impl From<HeaderPlatform> for Platform {
    fn from(p: HeaderPlatform) -> Self {
        match p {
            HeaderPlatform::Nes => Self::Nes,
            HeaderPlatform::Snes => Self::Snes,
            HeaderPlatform::N64 => Self::N64,
            HeaderPlatform::GameBoy => Self::GameBoy,
            HeaderPlatform::GameBoyAdvance => Self::GameBoyAdvance,
            HeaderPlatform::NintendoDs => Self::NintendoDs,
            HeaderPlatform::Nintendo3ds => Self::Nintendo3ds,
            HeaderPlatform::GameCube => Self::GameCube,
            HeaderPlatform::MasterSystem => Self::MasterSystem,
            HeaderPlatform::MegaDrive => Self::MegaDrive,
            HeaderPlatform::MegaCd => Self::MegaCd,
            HeaderPlatform::Saturn => Self::Saturn,
            HeaderPlatform::Dreamcast => Self::Dreamcast,
            HeaderPlatform::PlayStation => Self::PlayStation,
            HeaderPlatform::PlayStation2 => Self::PlayStation2,
            HeaderPlatform::Psp => Self::Psp,
            HeaderPlatform::Xbox => Self::Xbox,
        }
    }
}

/// One of a header's fields, in words.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HeaderField {
    pub label: String,
    pub value: String,
}

/// The fields of the header of the game for `platform` at `path`: a ROM or
/// disc image, a zip holding one, or a playlist, cue sheet or GD-ROM sheet
/// naming one. Empty when there's no header to read. It reads only what the
/// header needs, so it suits a background thread, not the main one.
#[uniffi::export]
pub fn rom_header_fields(platform: HeaderPlatform, path: String) -> Vec<HeaderField> {
    read(platform, Path::new(&path))
        .map(|header| describe(&header))
        .unwrap_or_default()
}

fn read(platform: HeaderPlatform, path: &Path) -> Option<Header> {
    let path = data_file(path, 0)?;
    let file = BufReader::new(File::open(&path).ok()?);
    if extension(&path) == "zip" {
        let entry = ZipEntry::open(file).ok()?;
        return rom_headers::identify(platform.into(), entry).ok();
    }
    rom_headers::identify(platform.into(), file).ok()
}

fn extension(path: &Path) -> String {
    path.extension()
        .and_then(|e| e.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
}

/// The file that holds a game's data: the file itself, or the one a playlist
/// names first, a cue sheet's first data track's, or a GD-ROM sheet's
/// high-density data track's. It follows at most three, so a sheet that names
/// itself ends.
fn data_file(path: &Path, depth: u8) -> Option<PathBuf> {
    let sheet = || std::fs::read_to_string(path).ok();
    let folder = path.parent().unwrap_or(Path::new(""));
    let named = match extension(path).as_str() {
        "m3u" | "cue" | "gdi" if depth >= 3 => return None,
        "m3u" => sheet()?
            .lines()
            .map(str::trim)
            .find(|l| !l.is_empty() && !l.starts_with('#'))?
            .to_owned(),
        "cue" => cue_data_file(&sheet()?)?,
        "gdi" => gdi_data_file(&sheet()?)?,
        _ => return Some(path.to_owned()),
    };
    data_file(&folder.join(named), depth + 1)
}

/// The file of a cue sheet's first data track.
fn cue_data_file(cue: &str) -> Option<String> {
    let mut file = None;
    for line in cue.lines().map(str::trim) {
        if let Some(rest) = line.strip_prefix("FILE ") {
            file = Some(unquote(
                rest.rsplit_once(' ').map_or(rest, |(name, _)| name),
            ));
        } else if let Some(rest) = line.strip_prefix("TRACK ")
            && !rest.ends_with("AUDIO")
        {
            return file;
        }
    }
    None
}

/// The file of a GD-ROM sheet's first data track in the high-density area,
/// which starts at track 3, else its first data track. Each line after the
/// count is a track: its number, start, type (4 for data), sector size, file
/// and offset.
fn gdi_data_file(gdi: &str) -> Option<String> {
    let tracks: Vec<(u32, String)> = gdi
        .lines()
        .skip(1)
        .filter_map(|line| {
            let line = line.trim();
            let (number, rest) = line.split_once(char::is_whitespace)?;
            let fields: Vec<&str> = rest.split_whitespace().collect();
            if fields.get(1) != Some(&"4") {
                return None;
            }
            // The file name may be quoted, with spaces in it.
            let name = match rest.split_once('"') {
                Some((_, quoted)) => quoted.split_once('"')?.0.to_owned(),
                None => (*fields.get(3)?).to_owned(),
            };
            Some((number.parse().ok()?, name))
        })
        .collect();
    tracks
        .iter()
        .find(|(number, _)| *number >= 3)
        .or(tracks.first())
        .map(|(_, name)| name.clone())
}

fn unquote(s: &str) -> String {
    s.trim().trim_matches('"').to_owned()
}

/// The largest file in a zip that isn't a known extra, such as a readme,
/// manual or picture, read and seeked as far as 64 MiB in, which it
/// decompresses as reads reach it. Its length is the whole file's.
struct ZipEntry<R> {
    zip: zip::ZipArchive<R>,
    index: usize,
    size: u64,
    data: Vec<u8>,
    position: u64,
}

/// Whether a file in a zip is something packed beside the game, not the game.
/// Not `.md`, which is a Mega Drive ROM as often as a document.
fn is_extra(name: &str) -> bool {
    let extension = name
        .rsplit_once('.')
        .map_or("", |(_, e)| e)
        .to_ascii_lowercase();
    matches!(
        extension.as_str(),
        "txt"
            | "nfo"
            | "diz"
            | "pdf"
            | "htm"
            | "html"
            | "doc"
            | "rtf"
            | "jpg"
            | "jpeg"
            | "png"
            | "gif"
            | "bmp"
            | "webp"
    )
}

/// How much of a zipped file [`ZipEntry`] decompresses at most.
const ZIP_LIMIT: u64 = 64 << 20;

impl<R: Read + Seek> ZipEntry<R> {
    fn open(reader: R) -> zip::result::ZipResult<Self> {
        let mut zip = zip::ZipArchive::new(reader)?;
        let mut largest = None;
        for i in 0..zip.len() {
            let file = zip.by_index_raw(i)?;
            if file.is_file()
                && !file.name().is_ok_and(|name| is_extra(&name))
                && largest.is_none_or(|(_, size)| file.size() > size)
            {
                largest = Some((i, file.size()));
            }
        }
        let (index, size) = largest.ok_or(zip::result::ZipError::FileNotFound)?;
        Ok(Self {
            zip,
            index,
            size,
            data: Vec::new(),
            position: 0,
        })
    }

    /// Decompresses at least as far as `end`, from the start, since a zip's
    /// stream can't resume: twice as far as before, to read it few times.
    fn fill(&mut self, end: u64) -> io::Result<()> {
        let end = end.min(self.size);
        if end <= self.data.len() as u64 {
            return Ok(());
        }
        if end > ZIP_LIMIT {
            return Err(io::Error::other("past what a zipped game decompresses"));
        }
        let want = end
            .max(self.data.len() as u64 * 2)
            .min(self.size.min(ZIP_LIMIT));
        let mut data = Vec::with_capacity(want as usize);
        self.zip
            .by_index(self.index)?
            .take(want)
            .read_to_end(&mut data)?;
        self.data = data;
        Ok(())
    }
}

impl<R: Read + Seek> Read for ZipEntry<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.fill(self.position + buf.len() as u64)?;
        let start = (self.position as usize).min(self.data.len());
        let n = buf.len().min(self.data.len() - start);
        buf[..n].copy_from_slice(&self.data[start..start + n]);
        self.position += n as u64;
        Ok(n)
    }
}

impl<R: Read + Seek> Seek for ZipEntry<R> {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        self.position = match to {
            SeekFrom::Start(at) => Some(at),
            SeekFrom::End(by) => self.size.checked_add_signed(by),
            SeekFrom::Current(by) => self.position.checked_add_signed(by),
        }
        .ok_or_else(|| io::Error::from(io::ErrorKind::InvalidInput))?;
        Ok(self.position)
    }
}

/// Builds a header's fields, leaving out the empty ones.
#[derive(Default)]
struct Fields(Vec<HeaderField>);

impl Fields {
    fn add(&mut self, label: &str, value: impl ToString) -> &mut Self {
        let value = value.to_string();
        let value = value.split_whitespace().collect::<Vec<_>>().join(" ");
        if !value.is_empty() {
            self.0.push(HeaderField {
                label: label.to_owned(),
                value,
            });
        }
        self
    }

    fn some(&mut self, label: &str, value: Option<impl ToString>) -> &mut Self {
        if let Some(value) = value {
            self.add(label, value);
        }
        self
    }
}

/// A size in bytes, in KiB or MiB.
fn size(bytes: u64) -> String {
    if bytes >= 1 << 20 && bytes.is_multiple_of(1 << 20) {
        format!("{} MiB", bytes >> 20)
    } else if bytes.is_multiple_of(1024) {
        format!("{} KiB", bytes >> 10)
    } else {
        format!("{bytes} bytes")
    }
}

fn valid(ok: bool) -> &'static str {
    if ok { "Valid" } else { "Doesn't match" }
}

/// A header's fields that say which copy of the game this is, in words.
fn describe(header: &Header) -> Vec<HeaderField> {
    let mut f = Fields::default();
    match header {
        Header::Nes(h) => {
            f.add("Header", h.format)
                .add(
                    "Mapper",
                    match h.submapper {
                        Some(sub) if sub != 0 => format!("{}, submapper {sub}", h.mapper),
                        _ => h.mapper.to_string(),
                    },
                )
                .add("PRG ROM", size(h.prg_rom_size));
            if h.chr_rom_size > 0 {
                f.add("CHR ROM", size(h.chr_rom_size));
            }
            f.add("Timing", h.timing);
            if h.battery {
                f.add("Battery", "Yes");
            }
        }
        Header::Snes(h) => {
            f.add("Title", &h.title);
            if let Some(ext) = &h.extended {
                f.add("Game code", &ext.game_code);
            }
            f.add("Maker", h.maker_code())
                .add("Region", h.destination)
                .add("Version", h.version)
                .add("Map", h.map_mode);
        }
        Header::N64(h) => {
            f.add("Title", &h.title)
                .add("Game code", &h.game_code)
                .add("Region", h.destination)
                .add("Version", h.version)
                .add("Byte order", h.byte_order);
        }
        Header::GameBoy(h) => {
            f.add("Title", &h.title)
                .some("Manufacturer", h.manufacturer_code.as_ref())
                .add("Licensee", &h.licensee)
                .add("Region", h.destination)
                .add("Version", h.version)
                .add("System", h.cgb)
                .add("Cartridge", h.cartridge_type)
                .add("Header checksum", valid(h.header_checksum_valid));
        }
        Header::GameBoyAdvance(h) => {
            f.add("Title", &h.title)
                .add("Game code", &h.game_code)
                .add("Maker", &h.maker_code)
                .add("Region", h.destination)
                .add("Version", h.version)
                .add("Header checksum", valid(h.complement_check_valid));
        }
        Header::NintendoDs(h) => {
            f.add("Title", &h.title)
                .add("Game code", &h.game_code)
                .add("Maker", &h.maker_code)
                .add("Region", h.destination)
                .add("Version", h.version)
                .add("System", h.unit_code)
                .add("Header checksum", valid(h.header_crc_valid));
        }
        Header::Nintendo3ds(h) => {
            f.add("Product code", &h.product_code)
                .add("Title ID", format!("{:016X}", h.program_id))
                .add("Maker", &h.maker_code)
                .some("Region", h.region);
            if h.new_3ds_only {
                f.add("System", "New 3DS only");
            }
            let compressed = if h.compressed { ", compressed" } else { "" };
            f.add("Format", format!("{}{compressed}", h.container));
        }
        Header::GameCube(h) => {
            f.add("Title", &h.title)
                .add("Game ID", &h.game_id)
                .add("Region", h.region_code)
                .add("Version", h.version)
                .add("Disc", h.disc_number + 1)
                .add("Console", h.console)
                .add("Format", h.container);
        }
        Header::MasterSystem(h) => {
            f.add("Product code", &h.product_code)
                .add("Region", h.region)
                .add("Version", h.version)
                .some("ROM", h.rom_size.map(size));
        }
        Header::MegaDrive(c) => {
            sega_header(&mut f, &c.header);
            if c.is_32x {
                f.add("System", "32X");
            }
            if c.format == rom_headers::mega_drive::Format::Smd {
                f.add("Format", c.format);
            }
        }
        Header::MegaCd(d) => {
            sega_header(&mut f, &d.header);
        }
        Header::Saturn(h) => {
            let areas = area_names(&h.areas, rom_headers::saturn::area_name);
            f.add("Title", &h.title)
                .add("Product number", &h.product_number)
                .add("Version", &h.version)
                .add("Released", date(&h.release_date))
                .add("Areas", areas)
                .add("Disc", &h.device_info);
        }
        Header::Dreamcast(h) => {
            let areas = area_names(&h.areas, rom_headers::dreamcast::area_name);
            f.add("Title", &h.title)
                .add("Product number", &h.product_number)
                .add("Version", &h.version)
                .add("Released", date(&h.release_date))
                .add("Areas", areas)
                .add("Disc", &h.device_info);
        }
        Header::SystemCnf(cnf) => {
            match cnf.serial() {
                Some(serial) => f.add("Serial", serial),
                None => f.add("Executable", &cnf.executable),
            }
            .some("Version", cnf.version.as_ref())
            .some("Video", cnf.video_mode.as_ref());
        }
        Header::ParamSfo(sfo) => {
            f.some("Title", sfo.title())
                .some("Disc ID", sfo.disc_id())
                .some("Version", sfo.disc_version().or(sfo.app_version()))
                .some("Category", sfo.category())
                .some("System software", sfo.system_version());
        }
        Header::Xbox(c) => {
            f.add("Title", &c.title)
                .add("Title ID", format!("{:08X}", c.title_id))
                .add("Product code", c.product_code())
                .add("Regions", c.region_names().join(", "))
                .add("Version", c.version)
                .add("Disc", c.disc_number + 1);
        }
    }
    f.0
}

/// The fields Mega Drive cartridges and Mega CD discs share.
fn sega_header(f: &mut Fields, h: &rom_headers::mega_drive::Header) {
    let title = if h.overseas_title.is_empty() {
        &h.domestic_title
    } else {
        &h.overseas_title
    };
    f.add("Title", title)
        .add("Serial", &h.serial_number)
        .add("Regions", h.regions)
        .add("Copyright", &h.copyright);
}

/// The names of a disc's area symbols, or the symbols as they are when one
/// has no name.
fn area_names(areas: &str, name: fn(char) -> Option<&'static str>) -> String {
    areas
        .chars()
        .map(|c| name(c).map_or_else(|| c.to_string(), str::to_owned))
        .collect::<Vec<_>>()
        .join(", ")
}

/// A `YYYYMMDD` date as `YYYY-MM-DD`, or as it is when it isn't one.
fn date(d: &str) -> String {
    if d.len() == 8 && d.bytes().all(|c| c.is_ascii_digit()) {
        format!("{}-{}-{}", &d[..4], &d[4..6], &d[6..])
    } else {
        d.to_owned()
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    fn fields(fields: &[HeaderField]) -> Vec<(&str, &str)> {
        fields
            .iter()
            .map(|f| (f.label.as_str(), f.value.as_str()))
            .collect()
    }

    fn gba() -> Vec<u8> {
        let mut rom = vec![0u8; 0x200];
        rom[0xA0..0xAC].copy_from_slice(b"POKEMON EMER");
        rom[0xAC..0xB2].copy_from_slice(b"BPEE01");
        rom[0xB2] = 0x96;
        rom[0xBD] = rom[0xA0..0xBD]
            .iter()
            .fold(0u8, |x, &c| x.wrapping_sub(c))
            .wrapping_sub(0x19);
        rom
    }

    #[test]
    fn describes_a_rom_and_a_zipped_one() {
        let dir = tempfile::tempdir().unwrap();
        let rom = dir.path().join("game.gba");
        std::fs::write(&rom, gba()).unwrap();
        let want = [
            ("Title", "POKEMON EMER"),
            ("Game code", "BPEE"),
            ("Maker", "01"),
            ("Region", "USA"),
            ("Version", "0"),
            ("Header checksum", "Valid"),
        ];
        let read = |path: &Path| {
            rom_header_fields(
                HeaderPlatform::GameBoyAdvance,
                path.to_str().unwrap().to_owned(),
            )
        };
        assert_eq!(fields(&read(&rom)), want);

        let zipped = dir.path().join("game.zip");
        let mut zip = zip::ZipWriter::new(File::create(&zipped).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("readme.txt", options).unwrap();
        zip.write_all(b"hi").unwrap();
        // A picture larger than the game isn't taken for it.
        zip.start_file("cover.png", options).unwrap();
        zip.write_all(&vec![0x89; gba().len() * 2]).unwrap();
        zip.start_file("game.gba", options).unwrap();
        zip.write_all(&gba()).unwrap();
        zip.finish().unwrap();
        assert_eq!(fields(&read(&zipped)), want);

        assert!(read(&dir.path().join("missing.gba")).is_empty());
        assert!(
            rom_header_fields(HeaderPlatform::Nes, rom.to_str().unwrap().to_owned()).is_empty(),
            "Not an NES ROM"
        );
    }

    #[test]
    fn keeps_mega_drive_roms_among_a_zips_files() {
        assert!(!is_extra("Sonic.md"));
        assert!(is_extra("manual.PDF"));
    }

    #[test]
    fn follows_playlists_and_sheets() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path().join("game.m3u"),
            "# discs\n\nGame (Disc 1).cue\nGame (Disc 2).cue\n",
        )
        .unwrap();
        std::fs::write(
            dir.path().join("Game (Disc 1).cue"),
            "FILE \"Game (Disc 1) (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n",
        )
        .unwrap();
        assert_eq!(
            data_file(&dir.path().join("game.m3u"), 0).unwrap(),
            dir.path().join("Game (Disc 1) (Track 1).bin")
        );
        assert_eq!(
            cue_data_file(
                "FILE \"a.bin\" BINARY\n TRACK 01 AUDIO\nFILE \"b.bin\" BINARY\n TRACK 02 MODE1/2352\n"
            )
            .as_deref(),
            Some("b.bin")
        );
        let gdi = "3\n1 0 4 2352 track01.bin 0\n2 450 0 2352 track02.raw 0\n3 45000 4 2352 \"track 03.bin\" 0\n";
        assert_eq!(gdi_data_file(gdi).as_deref(), Some("track 03.bin"));

        let looping = dir.path().join("loop.cue");
        std::fs::write(
            &looping,
            "FILE \"loop.cue\" BINARY\n  TRACK 01 MODE1/2352\n",
        )
        .unwrap();
        assert_eq!(data_file(&looping, 0), None);
    }

    #[test]
    fn describes_a_playstation_2_disc() {
        let cnf = rom_headers::playstation::parse(
            b"BOOT2 = cdrom0:\\SCUS_971.24;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n",
        )
        .unwrap();
        assert_eq!(
            fields(&describe(&Header::SystemCnf(cnf))),
            [
                ("Serial", "SCUS-97124"),
                ("Version", "1.00"),
                ("Video", "NTSC")
            ]
        );
        assert_eq!(date("19951110"), "1995-11-10");
        assert_eq!(size(32 * 1024), "32 KiB");
        assert_eq!(size(2 << 20), "2 MiB");
    }
}
