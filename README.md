# homectl

Control your own computers and smart home from your phone. A small daemon runs on each machine; a native Android app (Material 3) finds them, pairs with a 6-digit code, and gives you a live system monitor, per-app audio mixer, terminal, power controls and lights — over your Wi-Fi, Tailscale, or a public tunnel when you have neither.

<p>
<img src="docs/monitor.png" width="230" alt="Monitor">
<img src="docs/controls.png" width="230" alt="Controls">
<img src="docs/terminal.png" width="230" alt="Terminal">
</p>

## What's in the app

| Tab | |
|---|---|
| **Monitor** | btop-style live view: CPU graph + per-core bars, memory/swap, network and disk I/O, disks, temperatures, battery, GPU state, and a process list you can sort and kill. Several machines (laptop *and* server) show side by side. |
| **Controls** | Switches that follow the real state: Wi-Fi, Bluetooth and each paired device, airplane mode, mute, public tunnel, webcam toggles. Sliders for volume, screen and monitor brightness. A **mixer** with a volume slider and mute per app, plus output/input device choice. |
| **Lights** | Govee lights: real on/off, brightness and colour; an "everything" switch. |
| **Terminal** | A real shell on the selected machine (xterm.js, with Esc/Tab/Ctrl/arrow keys). Type `user@host` and tap **SSH** to hop to any other machine from there. |
| **System** | Lock / suspend / restart / power off (with confirmation), services as switches, clipboard sync, file send. Share → **homectl** from any app sends files to the machine. |

## Download

Get the latest build from the **[Releases page](../../releases/latest)** (the repo is private: open it while logged in to GitHub, on the phone too). Or `gh release download --repo tarilka0gg/homectl`.

| File | What it is |
|---|---|
| `homectl-x.y.z.apk` | The Android app |
| `homectl-linux-x86_64` | The daemon. Static binary: runs on any x86-64 Linux (Gentoo, Arch, Debian, Alpine…) |
| `homectl-send-android-arm64` | Tiny file uploader for Termux / ARM servers |
| `SHA256SUMS` | Checksums |

## Quick start

**1. On each computer you want to control**
```sh
chmod +x homectl-linux-x86_64
./homectl-linux-x86_64          # listens on port 7878
```
It uses `brightnessctl`, `wpctl`/`pactl`, `nmcli`, `bluetoothctl`, `ddcutil`, `wl-clipboard`, `loginctl` when present; features whose tool is missing just don't appear.

**2. Install the app** — open the `.apk` on the phone (allow "install unknown apps" once). If Android refuses to update an older install, uninstall it first.

**3. Pair** — open the app. It scans your Wi-Fi and Tailscale network and lists the machines it finds. Tap **Pair**; a 6-digit code appears on the machine (a desktop notification, or run `./homectl-linux-x86_64 pair` on a server). Type it in. That's it — repeat for each machine.
Alternatives: `homectl pair` also prints a `homectl://pair?…` link and a QR code you can open on the phone, or enter an address and token by hand.

## Reaching it from anywhere

The app remembers every address a machine reports and uses the best one that answers, so you never choose a mode: **Tailscale** → **home network** → **public tunnel**.

- Tailscale / Wi-Fi: nothing to set up.
- **No Tailscale:** turn on *Controls → Public tunnel* (or start the daemon with `HOMECTL_TUNNEL=quick`; needs `cloudflared`). It gets a `https://….trycloudflare.com` address. That address changes whenever the tunnel restarts; the app learns the new one the next time it connects by any route. If it changes while you're away with only the tunnel, pair again. For a permanent address use a Cloudflare *named* tunnel with your own domain.

**Security — please read.** The token gives full control, **including a shell** (Terminal) and power-off. With the tunnel on, that's reachable from the internet, guarded only by the token (192 bits, plus a growing delay after wrong guesses). Pairing by code is refused over the tunnel and rate-limited, and each code allows 5 guesses. Keep the token private, leave the tunnel off unless needed, and set `HOMECTL_NO_TERMINAL=1` to disable the terminal on a machine. Traffic over Tailscale/LAN is plain HTTP inside that network.

## Notes per machine type
- **GPU:** only its power state is shown by default. Asking an NVIDIA dGPU for utilisation wakes it and drains a laptop battery; set `HOMECTL_GPU=1` to also read utilisation when it is already awake.
- **Power / services** use `loginctl`, else `doas -n` (a passwordless doas rule for `poweroff`, `reboot` and `rc-service` is needed on hosts without logind, such as Alpine).
- **Govee:** enable **LAN Control** for each light in the Govee app.

## Run at boot (OpenRC)
`packaging/homectl.initd` is an init script: copy it to `/etc/init.d/homectl`, install the binary to `/usr/local/bin/homectl`, then `rc-update add homectl`. For the tunnel put `export HOMECTL_TUNNEL=quick` in `/etc/conf.d/homectl`.

## API
All routes need `Authorization: Bearer <token>` except `/api/ping` and pairing. JSON in and out.

| Route | |
|---|---|
| `GET /api/monitor` | full snapshot + 2-minute history (CPU, memory, network) |
| `GET /api/endpoints`, `GET/POST /api/tunnel` | reachable addresses; public tunnel on/off |
| `GET /api/mixer`, `POST /api/mixer/{stream,recording,sink,source}/{id}` | per-app and per-device audio (`percent`, `muted`, `default`) |
| `GET /api/radios`, `GET/POST /api/wifi…`, `/api/bluetooth…` | radios and Bluetooth devices |
| `GET/POST /api/brightness`, `/api/monitors/{n}/brightness`, `/api/webcam/{n}` | displays, webcam |
| `GET /api/lights[?rescan=1]`, `POST /api/lights/{ip}` | Govee state and control |
| `POST /api/power/{lock,suspend,reboot,poweroff}`, `POST /api/procs/{pid}/{term,kill,stop,cont}` | |
| `GET /api/services`, `POST /api/services/{name}/{start,stop,restart}` | |
| `GET/POST /api/clipboard`, `POST /api/files/{name}` | clipboard; upload to `~/homectl-inbox/` |
| `GET /api/term?t=<token>` (WebSocket) | PTY: binary = bytes, text = `{"resize":[cols,rows]}` |
| `POST /api/pair/request`, `POST /api/pair/confirm` | code pairing (LAN/tailnet only) |

## Build from source
```sh
cargo build --release                              # daemon (needs a C compiler)
packaging/build-static.sh                          # portable static musl build (zig + official rustc)
(cd zig && zig build -Doptimize=ReleaseSmall)      # homectl-send
(cd android && gradle :app:assembleRelease)        # app: Android SDK platform 34 + build-tools 34
```
The app is signed with `~/.config/homectl/debug.keystore` (override with `HOMECTL_KEYSTORE`). Create one with `keytool -genkeypair -keystore ~/.config/homectl/debug.keystore -alias homectl -keyalg RSA -keysize 2048 -validity 10000` (password `android`).

## Layout
`src/` Rust daemon · `csrc/` C shims (V4L2 webcam, PTY) · `zig/` uploader · `android/` Kotlin + Compose app · `web/` tiny built-in web page · `packaging/` init script, static-build script

License: GPL-2.0-or-later
