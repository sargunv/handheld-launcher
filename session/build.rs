//! Builds the vendored rcheevos library, which the session's achievements
//! use, from `vendor/rcheevos`.

use std::path::Path;

fn main() {
    let root = Path::new("vendor/rcheevos");
    let src = root.join("src");
    let mut build = cc::Build::new();
    build
        .include(root.join("include"))
        .include(&src)
        // rc_libretro.h includes libretro.h.
        .include("vendor/libretro")
        .define("RC_CLIENT_SUPPORTS_HASH", None)
        // The vendored code is as upstream ships it.
        .warnings(false);
    // glob yields paths in alphabetical order.
    for file in glob::glob("vendor/rcheevos/src/**/*.c").expect("a valid pattern") {
        build.file(file.expect("a readable source"));
    }
    build.compile("rcheevos");
    println!("cargo::rerun-if-changed=vendor/rcheevos");
    println!("cargo::rerun-if-changed=vendor/libretro/libretro.h");
}
