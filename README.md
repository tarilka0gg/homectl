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

## Reaching the daemon
The app keeps every address the daemon reports (Tailscale, LAN, tunnel), probes them in parallel and uses the best one that answers, so it works with or without Tailscale.
- **Tailscale / LAN:** nothing to set up.
- **Public tunnel (no Tailscale):** run the daemon with `HOMECTL_TUNNEL=quick` (needs `cloudflared` on PATH). It prints a `https://*.trycloudflare.com` URL; that URL changes on every restart, but the app re-learns it whenever it connects over any route.
- **Pair a phone:** `homectl pair` prints a `homectl://pair?...` link and a QR code. Open the link on the phone or paste it into the app.
The tunnel exposes the API to the internet, protected only by the token (plus slow-down on failed attempts). Keep the token private; leave the tunnel off if you don't need it.

## Other pieces
- `zig/` — `homectl-send IP:PORT TOKEN FILE`, static uploader (cross-compile: `zig build -Dtarget=aarch64-linux-musl -Doptimize=ReleaseSmall`) for the Pixel/Termux and servers.
- `android/` — native Android app (Kotlin, Jetpack Compose, Material 3 with Material You colours): Home, Lights, Controls, Tools tabs, plus Share → "Send to homectl". Build: `cd android && gradle :app:assembleRelease` (SDK in `~/Android/sdk`, signing key `~/.config/homectl/debug.keystore`, override with `HOMECTL_KEYSTORE`). Tested in an Android 14 emulator over both Tailscale and the tunnel.
- `packaging/homectl.initd` — OpenRC script.

## Roadmap
build/test the Android app, photos, ssh key management, TLS.

License: GPL-2.0-or-later
