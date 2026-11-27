# rcheevos

[rcheevos](https://github.com/RetroAchievements/rcheevos), RetroAchievements'
library for clients, at `v12.5.0` (commit
`1433173220a7eaede6a9ed7a18e94117be1821e0`, tarball SHA-256
`e6df83de4e18f0a19206e711e1e589624dcfcf6bf529c14f6dd2bb2d1ced983f`). It's under
the MIT licence in `LICENSE`.

`include/` and `src/` are upstream's, without its tests, validator
(`rc_validate`), editor API (`rc_api_editor`), RAIntegration
(`rc_client_raintegration*`), external client (`rc_client_external*`) and build
files. `build.rs` compiles them, and `mise run session:rcheevos-bindings`
regenerates `src/achievements/ffi.rs` from the headers. To update, replace both
directories with a newer release's, regenerate the bindings, update the version
in `session/licences/libraries/rcheevos.json`, which About shows, and replace
the text in `session/licences/licenses/rcheevos.json` with the new `LICENSE`.
