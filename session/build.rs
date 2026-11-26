//! Builds the vendored rcheevos library, which the session's achievements
//! use, from `vendor/rcheevos`.

use std::path::Path;

fn main() {
    let root = Path::new("vendor/rcheevos");
    let src = root.join("src");
    let sources = [
        "rc_client.c",
        "rc_compat.c",
        "rc_libretro.c",
        "rc_util.c",
        "rc_version.c",
        "rapi/rc_api_common.c",
        "rapi/rc_api_info.c",
        "rapi/rc_api_runtime.c",
        "rapi/rc_api_user.c",
        "rcheevos/alloc.c",
        "rcheevos/condition.c",
        "rcheevos/condset.c",
        "rcheevos/consoleinfo.c",
        "rcheevos/format.c",
        "rcheevos/lboard.c",
        "rcheevos/memref.c",
        "rcheevos/operand.c",
        "rcheevos/richpresence.c",
        "rcheevos/runtime.c",
        "rcheevos/runtime_progress.c",
        "rcheevos/trigger.c",
        "rcheevos/value.c",
        "rhash/aes.c",
        "rhash/cdreader.c",
        "rhash/hash.c",
        "rhash/hash_disc.c",
        "rhash/hash_encrypted.c",
        "rhash/hash_rom.c",
        "rhash/hash_zip.c",
        "rhash/md5.c",
    ];
    let mut build = cc::Build::new();
    build
        .include(root.join("include"))
        .include(&src)
        // rc_libretro.h includes libretro.h.
        .include("vendor/libretro")
        .define("RC_CLIENT_SUPPORTS_HASH", None)
        // The vendored code is as upstream ships it.
        .warnings(false);
    for file in sources {
        build.file(src.join(file));
    }
    build.compile("rcheevos");
    println!("cargo::rerun-if-changed=vendor/rcheevos");
    println!("cargo::rerun-if-changed=vendor/libretro/libretro.h");
}
