# rom-headers

Reads the metadata game platforms keep in their own ROM and disc headers: each
format's fields as that format defines them, with no guessing across platforms.
A caller that knows the platform asks `identify` for its header; one that knows
the format calls its module's `read`. Readers take anything that reads and
seeks, and read only the headers, never a whole image.

| Platform                   | Header                                    | Images                                    |
| -------------------------- | ----------------------------------------- | ----------------------------------------- |
| NES                        | iNES, NES 2.0                             | ROM                                       |
| Super NES                  | Internal header, LoROM, HiROM and ExHiROM | ROM, with or without a copier header      |
| Nintendo 64                | ROM header                                | z64, v64, n64                             |
| Game Boy, Game Boy Color   | Cartridge header                          | ROM                                       |
| Game Boy Advance           | Cartridge header                          | ROM                                       |
| Nintendo DS                | Cartridge header                          | ROM                                       |
| Nintendo 3DS               | NCCH, and NCSD                            | CCI, CXI, decrypted CIA, Z3DS             |
| GameCube, Wii              | Disc header                               | ISO, RVZ, WIA, WBFS, CISO, GCZ            |
| Master System, Game Gear   | `TMR SEGA` header                         | ROM                                       |
| Mega Drive, 32X            | Header at $100                            | ROM, SMD                                  |
| Mega CD                    | Disc identifier and header                | Disc images                               |
| Saturn                     | System ID                                 | Disc images                               |
| Dreamcast                  | IP.BIN                                    | Disc images (a GD-ROM's third track), CHD |
| PlayStation, PlayStation 2 | `SYSTEM.CNF`, or a PBP's PARAM.SFO        | Disc images, PBP                          |
| PSP                        | PARAM.SFO                                 | Disc images, PBP                          |
| Xbox                       | XBE certificate                           | XBE, XISO, full disc images               |

Disc images are cooked (`.iso`), raw (`.bin`, 2352-byte sectors), CHD and CSO.
The `chd`, `deflate` (CSO, GCZ) and `zstd` (Z3DS) features, all on by default,
add the compressed ones.

```rust
use rom_headers::{Header, Platform, identify};

let mut file = std::fs::File::open("Super Mario World.sfc")?;
if let Header::Snes(header) = identify(Platform::Snes, &mut file)? {
    println!("{} ({})", header.title, header.destination);
}
```

It's ported from the format parsers of
[rom-tools](https://github.com/sargunv/rom-tools).
