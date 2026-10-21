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
- `GET /api/audio`, `POST /api/audio/{output,mic}` — wpctl volume/mute
- `GET /api/bluetooth`, `POST /api/bluetooth/power`, `POST /api/bluetooth/{mac}/{connect,disconnect}`
- `GET /api/wifi`, `POST /api/wifi/power` — NetworkManager
- `GET /api/services`, `POST /api/services/{name}/{start,stop,restart}` — OpenRC via `doas -n`
- `GET /api/monitors`, `GET|POST /api/monitors/{n}/brightness` — DDC/CI (ddcutil)
- `GET|POST /api/clipboard` — Wayland (wl-clipboard)
- `GET|POST /api/webcam/{n}` — V4L2 controls via a C shim (`csrc/`)

Govee needs "LAN Control" enabled per device in the Govee app.

## Other pieces
- `zig/` — `homectl-send IP:PORT TOKEN FILE`, static uploader (cross-compile: `zig build -Dtarget=aarch64-linux-musl -Doptimize=ReleaseSmall`) for the Pixel/Termux and servers.
- `android/` — Android app (WebView shell for the UI + share-sheet "Send to homectl" for files). Build: `android/build.sh` (needs the SDK in `~/Android/sdk`: platform 34, build-tools 34.0.0) → `android/build/homectl.apk`. On first launch enter `http://<ip>:7878` and the token; long-press Back to change them.
  Tested in an Android 14 x86_64 emulator (Google APIs image): setup dialog, live UI over `10.0.2.2`, and share-sheet upload from the Files app.
- `packaging/homectl.initd` — OpenRC script.

## Roadmap
build/test the Android app, photos, ssh key management, TLS.

License: GPL-2.0-or-later
