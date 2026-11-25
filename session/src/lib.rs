//! The game session: hosts libretro cores and owns their video, audio and
//! input. The Kotlin app drives it through UniFFI.

mod ffi;

uniffi::setup_scaffolding!();

/// The version of the session library, for the app to check it loaded the
/// build it expects.
#[uniffi::export]
pub fn session_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_matches_package() {
        assert_eq!(super::session_version(), env!("CARGO_PKG_VERSION"));
    }
}
