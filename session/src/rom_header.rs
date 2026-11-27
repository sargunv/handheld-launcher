//! What a game's own header says about the copy on the device, read with
//! `rom-headers`: [`rom_header_fields`] gives its fields as labels and values,
//! for the app to show, and [`rom_game_id`] the id its saves are filed under.

use std::fs::File;
use std::io::{self, BufReader, Read, Seek, SeekFrom};
use std::path::Path;

use rom_headers::{Header, Platform, switch};

use crate::content::{extension, largest_entry};

/// A platform whose games carry a header the session reads.
#[uniffi::remote(Enum)]
pub enum Platform {
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
    Switch,
}

/// One of a header's fields, in words.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HeaderField {
    pub label: String,
    pub value: String,
}

/// The fields of the header of the game for `platform` at `path`: a ROM or
/// disc image, or a zip holding one. Empty when there's no header to read. It
/// reads only what the header needs, so it suits a background thread, not the
/// main one.
#[uniffi::export]
pub fn rom_header_fields(platform: Platform, path: String) -> Vec<HeaderField> {
    read(platform, Path::new(&path), None)
        .map(|header| describe(&header))
        .unwrap_or_default()
}

/// The id in the header of the game for `platform` at `path` that emulators
/// file its saves under, or null when it has none. `keys` is the path of a
/// Switch emulator's `prod.keys`, which opens a Switch game that carries no
/// ticket.
#[uniffi::export]
pub fn rom_game_id(platform: Platform, path: String, keys: Option<String>) -> Option<String> {
    let switch_key = keys
        .filter(|_| platform == Platform::Switch)
        .and_then(|keys| switch::header_key(&std::fs::read_to_string(keys).ok()?));
    match read(platform, Path::new(&path), switch_key.as_ref())? {
        Header::SystemCnf(cnf) => cnf.serial(),
        Header::ParamSfo(sfo) => sfo.disc_id().map(str::to_owned),
        Header::Nintendo3ds(h) => Some(format!("{:016X}", h.program_id)),
        Header::GameCube(h) => Some(h.game_id.chars().take(4).collect()),
        Header::Switch(h) => Some(format!("{:016X}", h.title_id)),
        _ => None,
    }
}

/// The header of the game at `path`, a Switch game's read with the console's
/// `switch_key`.
fn read(platform: Platform, path: &Path, switch_key: Option<&[u8; 32]>) -> Option<Header> {
    let file = BufReader::new(File::open(path).ok()?);
    if extension(path).as_deref() == Some("zip") {
        return identify(platform, ZipEntry::open(file, platform).ok()?, switch_key);
    }
    identify(platform, file, switch_key)
}

fn identify(
    platform: Platform,
    reader: impl Read + Seek,
    switch_key: Option<&[u8; 32]>,
) -> Option<Header> {
    match platform {
        Platform::Switch => switch::read(reader, switch_key).map(Header::Switch),
        _ => rom_headers::identify(platform, reader),
    }
    .ok()
}

/// The largest file in a zip that can be a game of the platform (see
/// [`is_game`]), read and seeked as far as 64 MiB in, which it decompresses as
/// reads reach it. Its length is the whole file's.
struct ZipEntry<R> {
    zip: zip::ZipArchive<R>,
    index: usize,
    size: u64,
    data: Vec<u8>,
    position: u64,
}

/// Whether a file with the lowercased `extension` can be a game of
/// `platform`: what a zip's game is chosen among, so a readme, manual or
/// picture packed beside it never is.
fn is_game(platform: Platform, extension: &str) -> bool {
    let extensions: &[&str] = match platform {
        Platform::Nes => &["nes", "unf", "unif"],
        Platform::Snes => &["sfc", "smc", "swc", "fig", "bs", "st"],
        Platform::N64 => &["z64", "n64", "v64"],
        Platform::GameBoy => &["gb", "gbc", "sgb", "cgb", "dmg"],
        Platform::GameBoyAdvance => &["gba", "agb", "mb"],
        Platform::NintendoDs => &["nds", "dsi", "ids", "srl"],
        Platform::Nintendo3ds => &["3ds", "cci", "zcci", "cxi", "z3ds"],
        Platform::GameCube => &["iso", "gcm", "rvz", "wia", "wbfs", "ciso", "gcz"],
        Platform::MasterSystem => &["sms", "gg", "sg"],
        Platform::MegaDrive => &["md", "gen", "smd", "bin", "32x", "68k", "sgd"],
        Platform::MegaCd | Platform::Saturn => &["iso", "bin", "img", "chd"],
        Platform::Dreamcast => &["iso", "bin", "img", "chd", "cdi"],
        Platform::PlayStation => &["iso", "bin", "img", "chd", "pbp"],
        Platform::PlayStation2 => &["iso", "bin", "img", "chd", "cso", "zso"],
        Platform::Psp => &["iso", "cso", "chd", "pbp"],
        Platform::Xbox => &["iso", "xiso", "xbe", "chd"],
        Platform::Switch => &["nsp", "nsz", "xci", "xcz"],
    };
    extensions.contains(&extension)
}

/// How much of a zipped file [`ZipEntry`] decompresses at most.
const ZIP_LIMIT: u64 = 64 << 20;

impl<R: Read + Seek> ZipEntry<R> {
    fn open(reader: R, platform: Platform) -> zip::result::ZipResult<Self> {
        let mut zip = zip::ZipArchive::new(reader)?;
        let (index, _) = largest_entry(&mut zip, |e| is_game(platform, e))?
            .ok_or(zip::result::ZipError::FileNotFound)?;
        let size = zip.by_index_raw(index)?.size();
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
        Header::Switch(h) => {
            f.add("Title ID", format!("{:016X}", h.title_id));
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
            rom_header_fields(Platform::GameBoyAdvance, path.to_str().unwrap().to_owned())
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
            rom_header_fields(Platform::Nes, rom.to_str().unwrap().to_owned()).is_empty(),
            "Not an NES ROM"
        );
    }

    #[test]
    fn reads_the_ids_saves_are_filed_under() {
        let dir = tempfile::tempdir().unwrap();
        let id = |platform, name: &str, bytes: &[u8]| {
            let path = dir.path().join(name);
            std::fs::write(&path, bytes).unwrap();
            rom_game_id(platform, path.to_str().unwrap().to_owned(), None)
        };
        let mut ncch = vec![0u8; 0x200];
        ncch[0x100..0x104].copy_from_slice(b"NCCH");
        ncch[0x118..0x120].copy_from_slice(&0x0004_0000_0003_3500u64.to_le_bytes());
        assert_eq!(
            id(Platform::Nintendo3ds, "game.cxi", &ncch).as_deref(),
            Some("0004000000033500")
        );
        let mut disc = vec![0u8; 0x60];
        disc[..6].copy_from_slice(b"GZLE01");
        disc[0x1C..0x20].copy_from_slice(&0xC233_9F3Du32.to_be_bytes());
        assert_eq!(
            id(Platform::GameCube, "game.iso", &disc).as_deref(),
            Some("GZLE")
        );
        assert_eq!(id(Platform::GameBoyAdvance, "game.gba", &gba()), None);
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
    }
}
