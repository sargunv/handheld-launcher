//! Reads the public-domain and LGPL test ROMs in `tests/data` (see its
//! README).

use std::fs::File;
use std::path::Path;

use rom_headers::{Header, Platform, identify};

fn read(platform: Platform, name: &str) -> Header {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/data")
        .join(name);
    identify(platform, File::open(path).unwrap()).unwrap()
}

#[test]
fn reads_nes_roms() {
    let Header::Nes(h) = read(Platform::Nes, "BombSweeper.nes") else {
        unreachable!()
    };
    assert_eq!(h.format, rom_headers::nes::Format::INes);
    assert_eq!((h.prg_rom_size, h.chr_rom_size), (16 * 1024, 8 * 1024));
    assert_eq!(h.mapper, 0);
    assert_eq!(h.mirroring, rom_headers::nes::Mirroring::Horizontal);
    assert_eq!(h.timing, rom_headers::nes::Timing::NTSC);
    assert!(!h.battery);

    let Header::Nes(h) = read(Platform::Nes, "serom.nes") else {
        unreachable!()
    };
    assert_eq!(h.format, rom_headers::nes::Format::Nes20);
    assert_eq!((h.prg_rom_size, h.chr_rom_size), (32 * 1024, 8 * 1024));
    assert_eq!((h.mapper, h.submapper), (1, Some(5)));
}

#[test]
fn reads_a_super_nes_rom() {
    let Header::Snes(h) = read(Platform::Snes, "col15.sfc") else {
        unreachable!()
    };
    assert_eq!(h.title, "32,768 color demo");
    assert_eq!(h.layout, rom_headers::snes::Layout::LoRom);
    assert!(h.copier_header);
    assert_eq!(h.checksum.wrapping_add(h.checksum_complement), 0xFFFF);
}

#[test]
fn reads_an_n64_rom() {
    let Header::N64(h) = read(Platform::N64, "flames.z64") else {
        unreachable!()
    };
    assert_eq!(h.title, "Flame Demo 12/25/01");
    assert_eq!(h.byte_order, rom_headers::n64::ByteOrder::BigEndian);
    assert_ne!(h.check_code, 0);
}

#[test]
fn reads_game_boy_roms() {
    let Header::GameBoy(h) = read(Platform::GameBoy, "gbtictac.gb") else {
        unreachable!()
    };
    assert_eq!(h.title, "TIC-TAC-TOE");
    assert_eq!(h.licensee.to_string(), "00");
    assert_eq!(h.manufacturer_code, None);
    assert_eq!(h.cgb, rom_headers::gb::CgbFlag::NONE);

    let Header::GameBoy(h) = read(Platform::GameBoy, "JUMPMAN86.GBC") else {
        unreachable!()
    };
    assert_eq!(h.title, "JUMPMAN 86");
    assert_eq!(h.licensee, rom_headers::gb::Licensee::New([0xB1, 0xB0]));
    assert_eq!(h.licensee.to_string(), "B1B0");
}

#[test]
fn reads_a_game_boy_advance_rom() {
    let Header::GameBoyAdvance(h) = read(Platform::GameBoyAdvance, "AGB_Rogue.gba") else {
        unreachable!()
    };
    assert_eq!(h.title, "ROGUE");
    assert_eq!(h.game_code, "AAAA");
    assert_eq!(h.maker_code, "AA");
    assert!(h.complement_check_valid);
}

#[test]
fn reads_a_nintendo_ds_rom() {
    let Header::NintendoDs(h) = read(Platform::NintendoDs, "MixedCubes.nds") else {
        unreachable!()
    };
    assert_eq!(h.game_code, "AXXE");
    assert_eq!(h.destination, rom_headers::nds::Destination::USA);
    assert_eq!(h.capacity, Some(128 * 1024));
    assert!(h.header_crc_valid);
}

#[test]
fn reads_mega_drive_roms_plain_and_interleaved() {
    let Header::MegaDrive(plain) = read(Platform::MegaDrive, "Censor_Intro.md") else {
        unreachable!()
    };
    let Header::MegaDrive(smd) = read(Platform::MegaDrive, "Censor_Intro.smd") else {
        unreachable!()
    };
    assert_eq!(plain.format, rom_headers::mega_drive::Format::Plain);
    assert_eq!(smd.format, rom_headers::mega_drive::Format::Smd);
    assert!(!plain.is_32x);
    assert!(plain.header.system_type.contains("SEGA"));
    assert_eq!(plain.header, smd.header);
}

#[test]
fn reads_an_xbe() {
    let Header::Xbox(c) = read(Platform::Xbox, "default.xbe") else {
        unreachable!()
    };
    assert_eq!(c.title, "Xromwell");
    assert_eq!(c.container, rom_headers::xbox::Container::Xbe);
}
