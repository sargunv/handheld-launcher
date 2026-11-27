# Handheld

The software a gaming handheld runs. Browse, get, play and keep the games on
your [RomM](https://romm.app) server, with a controller, on Android handhelds
first and Linux handhelds later.

It's early. See [docs/vision.md](docs/vision.md) for where it's going.

## Development

Install [mise](https://mise.jdx.dev), and the Android SDK with the NDK that
`mise.toml` pins: `sdkmanager --install "ndk;30.0.16248370"`. Then:

```sh
mise install
mise run build
mise run run:desktop
```

## License

[GPL-3.0-or-later](LICENSE)
