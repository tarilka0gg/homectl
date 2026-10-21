# homectl

One daemon per machine (laptop, server) exposing an authenticated HTTP API and a phone-friendly web UI.
The Android app is a thin wrapper around that UI (planned).

## Run
    cargo run --release          # listens on 0.0.0.0:7878, override with HOMECTL_ADDR
Token is generated in `~/.config/homectl/token`; the UI asks for it once. Use it over Tailscale/LAN only.

## API (Bearer token)
- `GET /api/stats` — hostname, uptime, load, memory
- `GET|POST /api/brightness` — `{"percent": N}` (brightnessctl)
- `GET /api/lights` — Govee LAN scan; `POST /api/lights/{ip}` with `on`, `brightness`, `rgb`, `kelvin`
- `POST /api/files/{name}` — raw body saved to `~/homectl-inbox`

Govee needs "LAN Control" enabled per device in the Govee app.

## Roadmap
ddcutil monitors (C shim), bluetooth/wifi/audio, services, webcam/mic (C, V4L2), clipboard, photos, Android wrapper, Zig agent for Pixel/Termux.

License: GPL-2.0-or-later
