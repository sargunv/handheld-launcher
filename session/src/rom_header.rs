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

/// One of a header's fields, as the header stores it, for the app to put in
/// words.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HeaderField {
    pub key: HeaderKey,
    pub value: HeaderValue,
}

/// Which field a [`HeaderField`] is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HeaderKey {
    Title,
    GameCode,
    GameId,
    ProductCode,
    ProductNumber,
    Serial,
    DiscId,
    TitleId,
    Executable,
    Maker,
    Manufacturer,
    Licensee,
    Copyright,
    Region,
    Regions,
    Areas,
    Version,
    Released,
    Disc,
    /// An NES ROM's header format, iNES or NES 2.0.
    HeaderFormat,
    Mapper,
    Submapper,
    PrgRom,
    ChrRom,
    RomSize,
    Timing,
    Battery,
    Map,
    ByteOrder,
    System,
    Console,
    Cartridge,
    /// The image's container format.
    Format,
    Compressed,
    New3dsOnly,
    Uses32x,
    HeaderChecksum,
    Video,
    Category,
    SystemSoftware,
}

/// A header field's value.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HeaderValue {
    /// Text from the header, or the format's name for a code it stores.
    Text {
        text: String,
    },
    /// The format's names for several codes, such as the regions a disc plays
    /// in.
    Names {
        names: Vec<String>,
    },
    Number {
        number: u64,
    },
    Size {
        bytes: u64,
    },
    /// Whether a flag is set, or whether a checksum matches.
    Flag {
        set: bool,
    },
    /// A number the format writes in hex, with `digits` digits.
    Hex {
        number: u64,
        digits: u8,
    },
    Date {
        year: u16,
        month: u8,
        day: u8,
    },
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

fn text(value: impl ToString) -> HeaderValue {
    HeaderValue::Text {
        text: value.to_string(),
    }
}

fn number(value: impl Into<u64>) -> HeaderValue {
    HeaderValue::Number {
        number: value.into(),
    }
}

fn size(bytes: u64) -> HeaderValue {
    HeaderValue::Size { bytes }
}

fn flag(set: bool) -> HeaderValue {
    HeaderValue::Flag { set }
}

fn hex(number: impl Into<u64>, digits: u8) -> HeaderValue {
    HeaderValue::Hex {
        number: number.into(),
        digits,
    }
}

fn names<S: ToString>(names: impl IntoIterator<Item = S>) -> HeaderValue {
    HeaderValue::Names {
        names: names.into_iter().map(|n| n.to_string()).collect(),
    }
}

/// A `YYYYMMDD` date, or the text as it is when it isn't one.
fn date(d: &str) -> HeaderValue {
    let part = |range: std::ops::Range<usize>| d.get(range)?.parse().ok();
    match (d.len(), part(0..4), part(4..6), part(6..8)) {
        (8, Some(year), Some(month), Some(day)) => HeaderValue::Date {
            year,
            month: month as u8,
            day: day as u8,
        },
        _ => text(d),
    }
}

/// A header's fields, leaving out the empty ones.
#[derive(Default)]
struct Fields(Vec<HeaderField>);

impl Fields {
    fn add(&mut self, key: HeaderKey, value: HeaderValue) -> &mut Self {
        let value = match value {
            HeaderValue::Text { text } => HeaderValue::Text {
                text: text.split_whitespace().collect::<Vec<_>>().join(" "),
            },
            value => value,
        };
        let empty = match &value {
            HeaderValue::Text { text } => text.is_empty(),
            HeaderValue::Names { names } => names.is_empty(),
            _ => false,
        };
        if !empty {
            self.0.push(HeaderField { key, value });
        }
        self
    }

    fn some(&mut self, key: HeaderKey, value: Option<HeaderValue>) -> &mut Self {
        if let Some(value) = value {
            self.add(key, value);
        }
        self
    }

    /// Adds a flag only when it's set.
    fn when(&mut self, key: HeaderKey, set: bool) -> &mut Self {
        self.some(key, set.then(|| flag(true)))
    }
}

/// A header's fields that say which copy of the game this is.
fn describe(header: &Header) -> Vec<HeaderField> {
    use HeaderKey::*;
    let mut f = Fields::default();
    match header {
        Header::Nes(h) => {
            f.add(HeaderFormat, text(h.format))
                .add(Mapper, number(h.mapper))
                .some(Submapper, h.submapper.filter(|&s| s != 0).map(number))
                .add(PrgRom, size(h.prg_rom_size))
                .some(ChrRom, (h.chr_rom_size > 0).then(|| size(h.chr_rom_size)))
                .add(Timing, text(h.timing))
                .when(Battery, h.battery);
        }
        Header::Snes(h) => {
            f.add(Title, text(&h.title))
                .some(GameCode, h.extended.as_ref().map(|e| text(&e.game_code)))
                .add(Maker, text(h.maker_code()))
                .add(Region, text(h.destination))
                .add(Version, number(h.version))
                .add(Map, text(h.map_mode));
        }
        Header::N64(h) => {
            f.add(Title, text(&h.title))
                .add(GameCode, text(&h.game_code))
                .add(Region, text(h.destination))
                .add(Version, number(h.version))
                .add(ByteOrder, text(h.byte_order));
        }
        Header::GameBoy(h) => {
            f.add(Title, text(&h.title))
                .some(Manufacturer, h.manufacturer_code.as_ref().map(text))
                .add(Licensee, text(&h.licensee))
                .add(Region, text(h.destination))
                .add(Version, number(h.version))
                .add(System, text(h.cgb))
                .add(Cartridge, text(h.cartridge_type))
                .add(HeaderChecksum, flag(h.header_checksum_valid));
        }
        Header::GameBoyAdvance(h) => {
            f.add(Title, text(&h.title))
                .add(GameCode, text(&h.game_code))
                .add(Maker, text(&h.maker_code))
                .add(Region, text(h.destination))
                .add(Version, number(h.version))
                .add(HeaderChecksum, flag(h.complement_check_valid));
        }
        Header::NintendoDs(h) => {
            f.add(Title, text(&h.title))
                .add(GameCode, text(&h.game_code))
                .add(Maker, text(&h.maker_code))
                .add(Region, text(h.destination))
                .add(Version, number(h.version))
                .add(System, text(h.unit_code))
                .add(HeaderChecksum, flag(h.header_crc_valid));
        }
        Header::Nintendo3ds(h) => {
            f.add(ProductCode, text(&h.product_code))
                .add(TitleId, hex(h.program_id, 16))
                .add(Maker, text(&h.maker_code))
                .some(Region, h.region.map(text))
                .when(New3dsOnly, h.new_3ds_only)
                .add(Format, text(h.container))
                .when(Compressed, h.compressed);
        }
        Header::GameCube(h) => {
            f.add(Title, text(&h.title))
                .add(GameId, text(&h.game_id))
                .add(Region, text(h.region_code))
                .add(Version, number(h.version))
                .add(Disc, number(h.disc_number + 1))
                .add(Console, text(h.console))
                .add(Format, text(h.container));
        }
        Header::MasterSystem(h) => {
            f.add(ProductCode, text(&h.product_code))
                .add(Region, text(h.region))
                .add(Version, number(h.version))
                .some(RomSize, h.rom_size.map(size));
        }
        Header::MegaDrive(c) => {
            sega_header(&mut f, &c.header).when(Uses32x, c.is_32x).some(
                Format,
                (c.format == rom_headers::mega_drive::Format::Smd).then(|| text(c.format)),
            );
        }
        Header::MegaCd(d) => {
            sega_header(&mut f, &d.header);
        }
        Header::Saturn(h) => {
            f.add(Title, text(&h.title))
                .add(ProductNumber, text(&h.product_number))
                .add(Version, text(&h.version))
                .add(Released, date(&h.release_date))
                .add(Areas, area_names(&h.areas, rom_headers::saturn::area_name))
                .add(Disc, text(&h.device_info));
        }
        Header::Dreamcast(h) => {
            f.add(Title, text(&h.title))
                .add(ProductNumber, text(&h.product_number))
                .add(Version, text(&h.version))
                .add(Released, date(&h.release_date))
                .add(
                    Areas,
                    area_names(&h.areas, rom_headers::dreamcast::area_name),
                )
                .add(Disc, text(&h.device_info));
        }
        Header::SystemCnf(cnf) => {
            match cnf.serial() {
                Some(serial) => f.add(Serial, text(serial)),
                None => f.add(Executable, text(&cnf.executable)),
            }
            .some(Version, cnf.version.as_ref().map(text))
            .some(Video, cnf.video_mode.as_ref().map(text));
        }
        Header::ParamSfo(sfo) => {
            f.some(Title, sfo.title().map(text))
                .some(DiscId, sfo.disc_id().map(text))
                .some(Version, sfo.disc_version().or(sfo.app_version()).map(text))
                .some(Category, sfo.category().map(text))
                .some(SystemSoftware, sfo.system_version().map(text));
        }
        Header::Xbox(c) => {
            f.add(Title, text(&c.title))
                .add(TitleId, hex(c.title_id, 8))
                .add(ProductCode, text(c.product_code()))
                .add(Regions, names(c.region_names()))
                .add(Version, number(c.version))
                .add(Disc, number(c.disc_number + 1));
        }
        Header::Switch(h) => {
            f.add(TitleId, hex(h.title_id, 16));
        }
    }
    f.0
}

/// The fields Mega Drive cartridges and Mega CD discs share.
fn sega_header<'f>(f: &'f mut Fields, h: &rom_headers::mega_drive::Header) -> &'f mut Fields {
    let title = if h.overseas_title.is_empty() {
        &h.domestic_title
    } else {
        &h.overseas_title
    };
    f.add(HeaderKey::Title, text(title))
        .add(HeaderKey::Serial, text(&h.serial_number))
        .add(HeaderKey::Regions, names(h.regions.names()))
        .add(HeaderKey::Copyright, text(&h.copyright))
}

/// The names of a disc's area symbols, or the symbols as they are when one
/// has no name.
fn area_names(areas: &str, name: fn(char) -> Option<&'static str>) -> HeaderValue {
    names(
        areas
            .chars()
            .map(|c| name(c).map_or_else(|| c.to_string(), str::to_owned)),
    )
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

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
            (HeaderKey::Title, text("POKEMON EMER")),
            (HeaderKey::GameCode, text("BPEE")),
            (HeaderKey::Maker, text("01")),
            (HeaderKey::Region, text("USA")),
            (HeaderKey::Version, number(0u8)),
            (HeaderKey::HeaderChecksum, flag(true)),
        ]
        .map(|(key, value)| HeaderField { key, value });
        let read = |path: &Path| {
            rom_header_fields(Platform::GameBoyAdvance, path.to_str().unwrap().to_owned())
        };
        assert_eq!(read(&rom), want);

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
        assert_eq!(read(&zipped), want);

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
}
