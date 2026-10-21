# homectl

Control your own machines and smart home from your phone: a small Rust daemon runs on each computer, and a native Android app (Material 3) talks to it — over Tailscale, your home network, or a public tunnel when you have neither.

<img src="docs/controls.png" width="280" align="right" alt="Controls tab">

**What you can control**
- **System:** uptime, load, memory
- **Displays:** laptop backlight, external monitors (DDC/CI)
- **Audio:** speaker and microphone volume and mute (PipeWire)
- **Bluetooth** and **Wi-Fi:** power, connect / disconnect devices
- **Lights:** Govee lamps over the local network — on/off, brightness, colour
- **Webcam:** brightness, contrast, etc. (V4L2)
- **Clipboard:** send text to / get text from the laptop (Wayland)
- **Files:** send anything from the phone's Share menu to the laptop
- **Services:** start / stop / restart OpenRC services

## Download

Grab the latest build from the **[Releases page](../../releases/latest)** (the repo is private, so open it while logged in to GitHub — on the phone too):

| File | What it is |
|---|---|
| `homectl-0.x.apk` | The Android app |
| `homectl-linux-x86_64` | The daemon for a Linux PC / server |
| `homectl-send-android-arm64` | Tiny static file uploader for Termux / ARM servers |
| `SHA256SUMS` | Checksums |

With the GitHub CLI: `gh release download --repo tarilka0gg/homectl`

## Quick start

**1. On the computer you want to control**
```sh
chmod +x homectl-linux-x86_64
./homectl-linux-x86_64          # listens on port 7878
```
On first start it creates a secret token in `~/.config/homectl/token`.
It uses `brightnessctl`, `wpctl`, `nmcli`, `bluetoothctl`, `ddcutil`, `wl-clipboard` when they are installed; features whose tool is missing just stay empty.

**2. Install the app** — open the `.apk` on the phone (allow "install unknown apps" once). Updating over an existing install works as long as it was signed with the same key; otherwise uninstall first.

**3. Pair** — on the computer:
```sh
./homectl-linux-x86_64 pair
```
It prints a `homectl://pair?...` link and a QR code. Open the link on the phone (send it to yourself in a chat), or paste it into the app's first screen. You can also type a server address and the token by hand.

Done. Add more computers later by repeating step 1 and pairing again.

## Reaching it from anywhere

The app stores every address the daemon reports and uses the best one that answers, so you don't pick a mode:

1. **Tailscale** — if both devices are on the same tailnet, nothing to configure.
2. **Home network** — direct IP, same Wi-Fi.
3. **Public tunnel (no Tailscale)** — start the daemon with a Cloudflare quick tunnel:
   ```sh
   HOMECTL_TUNNEL=quick ./homectl-linux-x86_64     # needs `cloudflared` on PATH
   ```
   It gets a `https://….trycloudflare.com` address. That address changes on every restart; the app learns the new one the next time it connects by any route. If it changes while you're away on the tunnel alone, pair again. For a permanent address use a Cloudflare named tunnel with your own domain.

**Security:** the tunnel puts the API on the internet, guarded only by the token (plus a growing delay after wrong guesses). Treat the token and pairing links like a password, and leave the tunnel off unless you need it. Traffic over Tailscale/LAN is plain HTTP inside that network.

## Run at boot (OpenRC)
`packaging/homectl.initd` is an init script; copy it to `/etc/init.d/homectl`, install the binary to `/usr/local/bin/homectl`, then `rc-update add homectl`. Service control goes through `doas -n rc-service …`, so it needs a passwordless doas rule if you want that feature.

## API
All routes need `Authorization: Bearer <token>` except `GET /api/ping`.

| Route | |
|---|---|
| `GET /api/stats`, `GET /api/endpoints` | system info; the daemon's reachable addresses |
| `GET/POST /api/brightness` | `{"percent": N}` laptop backlight |
| `GET /api/monitors`, `GET/POST /api/monitors/{n}/brightness` | external monitors |
| `GET /api/audio`, `POST /api/audio/{output,mic}` | `{"percent":N,"muted":bool}` |
| `GET /api/bluetooth`, `POST /api/bluetooth/power`, `POST /api/bluetooth/{mac}/{connect,disconnect}` | |
| `GET /api/wifi`, `POST /api/wifi/power` | |
| `GET /api/lights`, `POST /api/lights/{ip}` | `on`, `brightness`, `rgb`, `kelvin` (Govee needs **LAN Control** enabled per light in the Govee app) |
| `GET/POST /api/webcam/{n}` | V4L2 controls |
| `GET/POST /api/clipboard` | `{"text": "..."}` |
| `GET /api/services`, `POST /api/services/{name}/{start,stop,restart}` | |
| `POST /api/files/{name}` | raw body → `~/homectl-inbox/` |

## Build from source
```sh
cargo build --release                 # daemon (needs a C compiler for the V4L2 shim)
cd zig && zig build -Doptimize=ReleaseSmall   # homectl-send; add -Dtarget=aarch64-linux-musl for ARM
cd android && gradle :app:assembleRelease     # app; needs the Android SDK (platform 34, build-tools 34)
```
The app is signed with the key in `~/.config/homectl/debug.keystore` (override with `HOMECTL_KEYSTORE`). Create your own with `keytool -genkeypair -keystore ~/.config/homectl/debug.keystore -alias homectl -keyalg RSA -keysize 2048 -validity 10000` using password `android`.

## Layout
`src/` Rust daemon · `csrc/` C V4L2 shim · `zig/` uploader · `android/` Kotlin + Compose app · `web/` built-in web page (open `http://host:7878`) · `packaging/` init script

## Roadmap
Photos, SSH key management, TLS for LAN, Linux desktop client, stable tunnel setup.

License: GPL-2.0-or-later
