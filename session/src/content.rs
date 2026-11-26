//! Getting a game file ready for the core: archives the core can't open are
//! extracted first, as RetroArch does.
//!
//! A `.zip` goes to the core as is when the core lists `zip` among its
//! extensions or asks for archives unextracted, as arcade cores such as FBNeo
//! do. Otherwise the session extracts the file in it that the core opens, the
//! largest if several do, into the content cache, and loads that. The cache
//! keeps only the last game extracted into it.

use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};

use crate::session::{SessionError, SystemInfo};

/// The archive formats the session extracts, by extension.
const ARCHIVE_EXTENSIONS: &[&str] = &["zip"];

/// What the session does with a game file before the core loads it.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Plan {
    /// The core loads the file itself.
    PassThrough,
    /// The file is an archive the core can't open, so the session extracts
    /// the game from it.
    Extract,
}

/// Whether `content` goes to the core as is or is extracted first.
pub(crate) fn plan(content: &Path, info: &SystemInfo) -> Plan {
    let Some(extension) = extension_of(content) else {
        return Plan::PassThrough;
    };
    let is_archive = ARCHIVE_EXTENSIONS.contains(&extension.as_str());
    // A core that lists no extensions says nothing of what to extract.
    if !is_archive
        || info.block_extract
        || info.valid_extensions.is_empty()
        || accepts(info, &extension)
    {
        Plan::PassThrough
    } else {
        Plan::Extract
    }
}

/// The index of the entry the core loads among an archive's `entries`, given
/// as each file's name and uncompressed size: the largest whose extension the
/// core accepts, the first of those if several are as large.
pub(crate) fn choose_entry<'a>(
    entries: impl IntoIterator<Item = (&'a str, u64)>,
    info: &SystemInfo,
) -> Option<usize> {
    let mut chosen: Option<(usize, u64)> = None;
    for (index, (name, size)) in entries.into_iter().enumerate() {
        let accepted = extension_of(Path::new(name)).is_some_and(|e| accepts(info, &e));
        if accepted && chosen.is_none_or(|(_, largest)| size > largest) {
            chosen = Some((index, size));
        }
    }
    chosen.map(|(index, _)| index)
}

/// The file the core loads for `content`: the file itself, or the game
/// extracted from it into `cache`. Without a cache, the file goes to the core
/// as is.
pub(crate) fn prepare(
    content: &Path,
    info: &SystemInfo,
    cache: Option<&Path>,
) -> Result<PathBuf, SessionError> {
    match (plan(content, info), cache) {
        (Plan::Extract, Some(cache)) => extract(content, info, cache),
        _ => Ok(content.to_owned()),
    }
}

/// Extracts the game the core loads from the zip at `archive` into `cache`,
/// emptying the cache first, and returns its path.
fn extract(archive: &Path, info: &SystemInfo, cache: &Path) -> Result<PathBuf, SessionError> {
    let file = File::open(archive).map_err(SessionError::ReadContent)?;
    let mut zip = zip::ZipArchive::new(io::BufReader::new(file))?;
    let mut entries = Vec::with_capacity(zip.len());
    for index in 0..zip.len() {
        let entry = zip.by_index_raw(index)?;
        // Directories and names that would leave the cache count as nothing.
        let name = entry
            .is_file()
            .then(|| entry.enclosed_name())
            .flatten()
            .and_then(|path| {
                path.file_name()
                    .map(|name| name.to_string_lossy().into_owned())
            })
            .unwrap_or_default();
        entries.push((name, entry.size()));
    }
    let index = choose_entry(entries.iter().map(|(n, s)| (n.as_str(), *s)), info)
        .ok_or(SessionError::NoContentInArchive)?;

    empty_dir(cache)?;
    let path = cache.join(&entries[index].0);
    let mut entry = zip.by_index(index)?;
    let mut out = File::create(&path)?;
    if let Err(e) = io::copy(&mut entry, &mut out) {
        // Leave no half-written game behind.
        let _ = fs::remove_file(&path);
        return Err(SessionError::Io(e));
    }
    log::info!(
        "extracted {} from {} to {}",
        entries[index].0,
        archive.display(),
        path.display()
    );
    Ok(path)
}

/// Creates `dir` if it's missing and removes everything in it.
fn empty_dir(dir: &Path) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            fs::remove_dir_all(entry.path())?;
        } else {
            fs::remove_file(entry.path())?;
        }
    }
    Ok(())
}

/// A path's extension, lowercased.
fn extension_of(path: &Path) -> Option<String> {
    path.extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
}

fn accepts(info: &SystemInfo, extension: &str) -> bool {
    info.valid_extensions
        .iter()
        .any(|e| e.eq_ignore_ascii_case(extension))
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use zip::write::SimpleFileOptions;

    use super::*;

    fn core(extensions: &[&str]) -> SystemInfo {
        SystemInfo {
            library_name: "Test".into(),
            library_version: "1".into(),
            valid_extensions: extensions.iter().map(|&e| e.to_owned()).collect(),
            need_fullpath: false,
            block_extract: false,
        }
    }

    #[test]
    fn extracts_a_zip_the_core_cant_open() {
        let mesen = core(&["nes", "fds", "unf", "unif"]);
        assert_eq!(
            plan(Path::new("/roms/Tetris (USA).zip"), &mesen),
            Plan::Extract
        );
        assert_eq!(
            plan(Path::new("/roms/Tetris (USA).ZIP"), &mesen),
            Plan::Extract
        );
    }

    #[test]
    fn passes_a_zip_the_core_opens() {
        let fbneo = core(&["zip", "7z"]);
        assert_eq!(plan(Path::new("/roms/sf2.zip"), &fbneo), Plan::PassThrough);
        let upper = core(&["ZIP"]);
        assert_eq!(plan(Path::new("/roms/sf2.zip"), &upper), Plan::PassThrough);
    }

    #[test]
    fn passes_a_zip_when_the_core_blocks_extraction() {
        let info = SystemInfo {
            block_extract: true,
            ..core(&["bin"])
        };
        assert_eq!(plan(Path::new("/roms/game.zip"), &info), Plan::PassThrough);
    }

    #[test]
    fn passes_files_that_arent_archives() {
        let mesen = core(&["nes"]);
        assert_eq!(
            plan(Path::new("/roms/Tetris.nes"), &mesen),
            Plan::PassThrough
        );
        assert_eq!(plan(Path::new("/roms/Tetris"), &mesen), Plan::PassThrough);
        // The core rejects it, but extracting won't help.
        assert_eq!(
            plan(Path::new("/roms/Tetris.7z"), &mesen),
            Plan::PassThrough
        );
    }

    #[test]
    fn passes_a_zip_when_the_core_lists_no_extensions() {
        assert_eq!(
            plan(Path::new("/roms/game.zip"), &core(&[])),
            Plan::PassThrough
        );
    }

    #[test]
    fn chooses_the_file_the_core_opens() {
        let mesen = core(&["nes"]);
        let entries = [("readme.txt", 9000), ("Tetris (USA).NES", 49_168)];
        assert_eq!(choose_entry(entries, &mesen), Some(1));
    }

    #[test]
    fn chooses_the_largest_file_the_core_opens() {
        let genesis = core(&["md", "gen", "bin"]);
        let entries = [
            ("Sonic (Proto).md", 262_144),
            ("Sonic.txt", 900_000),
            ("Sonic (USA).md", 524_288),
            ("Sonic (Europe).gen", 524_288),
        ];
        assert_eq!(choose_entry(entries, &genesis), Some(2));
    }

    #[test]
    fn chooses_nothing_when_the_core_opens_no_file() {
        let mesen = core(&["nes"]);
        assert_eq!(choose_entry([("readme.txt", 10), ("", 20)], &mesen), None);
    }

    fn write_zip(path: &Path, files: &[(&str, &[u8])]) {
        let mut zip = zip::ZipWriter::new(File::create(path).unwrap());
        for (name, data) in files {
            if name.ends_with('/') {
                zip.add_directory(*name, SimpleFileOptions::default())
                    .unwrap();
            } else {
                zip.start_file(*name, SimpleFileOptions::default()).unwrap();
                zip.write_all(data).unwrap();
            }
        }
        zip.finish().unwrap();
    }

    #[test]
    fn extracts_the_game_and_keeps_only_the_last() {
        let dir = tempfile::tempdir().unwrap();
        let cache = dir.path().join("cache");
        let mesen = core(&["nes"]);

        let first = dir.path().join("First.zip");
        write_zip(&first, &[("First.nes", b"first game")]);
        let extracted = prepare(&first, &mesen, Some(&cache)).unwrap();
        assert_eq!(extracted, cache.join("First.nes"));
        assert_eq!(fs::read(&extracted).unwrap(), b"first game");

        let second = dir.path().join("Second.zip");
        write_zip(
            &second,
            &[
                ("docs/", b""),
                ("docs/manual.txt", b"a long manual about the game"),
                ("roms/Second (Rev 1).nes", b"second game"),
                ("roms/Second (Proto).nes", b"proto"),
            ],
        );
        let extracted = prepare(&second, &mesen, Some(&cache)).unwrap();
        assert_eq!(extracted, cache.join("Second (Rev 1).nes"));
        assert_eq!(fs::read(&extracted).unwrap(), b"second game");
        let left: Vec<_> = fs::read_dir(&cache)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(left, ["Second (Rev 1).nes"]);
    }

    #[test]
    fn passes_the_zip_without_a_cache() {
        let dir = tempfile::tempdir().unwrap();
        let archive = dir.path().join("Game.zip");
        write_zip(&archive, &[("Game.nes", b"game")]);
        assert_eq!(prepare(&archive, &core(&["nes"]), None).unwrap(), archive);
    }

    #[test]
    fn fails_on_a_zip_without_the_game() {
        let dir = tempfile::tempdir().unwrap();
        let archive = dir.path().join("Game.zip");
        write_zip(&archive, &[("readme.txt", b"no game here")]);
        let cache = dir.path().join("cache");
        assert!(matches!(
            prepare(&archive, &core(&["nes"]), Some(&cache)),
            Err(SessionError::NoContentInArchive)
        ));
    }

    #[test]
    fn fails_on_a_broken_zip() {
        let dir = tempfile::tempdir().unwrap();
        let archive = dir.path().join("Game.zip");
        fs::write(&archive, b"not a zip").unwrap();
        let cache = dir.path().join("cache");
        assert!(matches!(
            prepare(&archive, &core(&["nes"]), Some(&cache)),
            Err(SessionError::Archive(_))
        ));
    }
}
