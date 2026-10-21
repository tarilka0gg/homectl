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
| **Monitor** | btop-style live view. CPU graph with per-core load *and temperature*; **each GPU** (iGPU and dGPU: load, VRAM, core/memory clocks, power, temperature, fan, with graphs); temperature graph and sensors (NVMe, DIMM); **fans** with RPM graph; memory/swap; network with **peaks**, totals and per-interface rates; disk I/O with peaks; disks; battery. **Per-core graphs** (one live graph per CPU thread, with temperature) or bars. A full **process table**: tap a column header (PID / NAME / CPU / MEM) to sort, tap again to reverse, search by name, command, user or PID, tap a process to terminate, kill, pause or resume it. Several machines (laptop *and* server) show side by side. |
| **Controls** | **Screens off/on** (built-in panel, external monitors, compositor output). Govee **lights** (real on/off, brightness, colour). Switches that follow the real state: Wi-Fi, Bluetooth and each paired device, airplane mode, public tunnel, webcam toggles. Sliders for screen and monitor brightness. **Audio**: every usable output and input as its own row (volume, mute, pick the default; unplugged ones are hidden), plus a **per-app mixer**. |
| **Terminal** | A real shell (xterm.js, canvas renderer, with Nerd Font symbols built in so fancy prompts — powerline, icons — draw correctly). The shell's size is the smallest attached screen, like tmux, so output wraps the same everywhere. **Sessions keep running on the machine** — leave the tab, close the app, lose Wi-Fi, and you reattach to the same screen. **Pick the shell** per session (＋ menu lists what's installed: fish, bash…) or switch the current one from ⋮. Multiple session tabs, **split view** (two terminals at once, can be different machines), **SSH** shortcut with history. Two rows of keys (Esc, Tab, Ctrl, Alt, arrows, F1–F12, Ctrl-combos…), keyboard show/hide button, **pinch to zoom** and A−/A+, touch scrolling, long-press to **select** text, Paste, and a **Buffer** view with the whole scrollback — filter it, select any part, or copy the last N lines. |
| **Files** | Browse the machine's files with **thumbnails** for images. Tap a file to **preview** it: images (pinch/double-tap zoom, swipe through the folder), text and code, PDFs. **Look inside archives** (zip, tar, tar.gz, gz natively; 7z, rar, xz, bz2 … through `bsdtar` if installed) like a folder, preview files inside without unpacking, and **unzip** everything or just what you tick. Download to the phone (Downloads/homectl), open, share, upload, make folders. The machines also appear in Android's **own file picker**, so any app (Telegram, Gmail…) can attach files straight from them. |
| **System** | **Power modes** (your `*-on.sh` / `*-off.sh` scripts, e.g. a deep-idle mode, with a confirmation first). **Update speed** (0.5 s – 5 s; machines sample slowly when nobody is watching to save power). Lock / suspend / restart / power off (with confirmation), services as switches, clipboard sync, send files. Share → **homectl** from any app sends files to the machine. |

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
- **GPU:** Intel and AMD GPUs are read from sysfs. An NVIDIA dGPU is read with `nvidia-smi` **only while it is already awake and the laptop is on AC power** — asking a sleeping card wakes it and drains the battery. On battery the Monitor shows a "read it anyway" switch.
- **Power / services** use `loginctl`, else `doas -n` (a passwordless doas rule for `poweroff`, `reboot` and `rc-service` is needed on hosts without logind, such as Alpine).
- **Govee:** enable **LAN Control** for each light in the Govee app.

## Screens and power modes
- **Screens off/on** (Controls tab): the built-in panel by backlight, external monitors by DDC/CI power mode (`ddcutil setvcp d6`) — DPMS makes some monitors cycle test patterns instead of sleeping — and optionally also the compositor output (niri) so the GPU can stop driving a dark screen and sleep. Needs `ddcutil` (and `brightnessctl` for the panel).
- **Power modes** (System tab): your own on/off script pairs. A pair named `<name>-on.sh` + `<name>-off.sh` in your home, `~/.local/bin` or `/usr/local/bin` shows up automatically; more (with a state check and a warning text) go in `~/.config/homectl/actions.json` — see `docs/actions.example.json`. They run through `doas -n`, so each script needs a passwordless rule; when it's missing the app shows the exact line to add, for example
  ```
  permit nopass YOU cmd /usr/local/bin/battery-powersave-on.sh        # executable script
  permit nopass YOU cmd sh args /home/YOU/idle-on.sh                  # run via sh
  ```
  (one rule for the `-on` and one for the `-off` script). The daemon never edits `/etc/doas.conf`.

## Run at boot (OpenRC)
`packaging/homectl.initd` is an init script: copy it to `/etc/init.d/homectl`, install the binary to `/usr/local/bin/homectl`, then `rc-update add homectl`. For the tunnel put `export HOMECTL_TUNNEL=quick` in `/etc/conf.d/homectl`.

## API
All routes need `Authorization: Bearer <token>` except `/api/ping` and pairing. JSON in and out.

| Route | |
|---|---|
| `GET /api/monitor[?gpu=1]` | full snapshot (CPU, per-core temps, GPUs, fans, network, disks…) + 2-minute history |
| `GET /api/procs?sort=&order=&q=&limit=` | the process table (sort by pid/name/user/cpu/mem) |
| `GET /api/fs/list?path=`, `GET /api/fs/download?path=` (Range ok), `POST /api/fs/upload?dir=&name=`, `POST /api/fs/mkdir` | file browser |
| `GET /api/term/sessions`, `DELETE …/{id}`, `POST …/{id}/name` | persistent terminal sessions |
| `GET /api/endpoints`, `GET/POST /api/tunnel` | reachable addresses; public tunnel on/off |
| `GET /api/mixer`, `POST /api/mixer/{stream,recording,sink,source}/{id}` | per-app and per-device audio (`percent`, `muted`, `default`) |
| `GET /api/radios`, `GET/POST /api/wifi…`, `/api/bluetooth…` | radios and Bluetooth devices |
| `GET/POST /api/brightness`, `/api/monitors/{n}/brightness`, `/api/webcam/{n}` | displays, webcam |
| `GET /api/lights[?rescan=1]`, `POST /api/lights/{ip}` | Govee state and control |
| `POST /api/power/{lock,suspend,reboot,poweroff}`, `POST /api/procs/{pid}/{term,kill,stop,cont}` | |
| `GET /api/services`, `POST /api/services/{name}/{start,stop,restart}` | |
| `GET /api/displays`, `POST /api/displays/{internal,N,all}/{on,off}[?compositor=0]` | screens off/on |
| `GET /api/actions`, `POST /api/actions/{id}/{on,off}` | power modes (your scripts) |
| `GET/POST /api/clipboard`, `POST /api/files/{name}` | clipboard; upload to `~/homectl-inbox/` |
| `GET /api/term?t=<token>&s=<session>` (WebSocket) | attach to a session (created on first use); replays recent output; binary = bytes, text = `{"resize":[cols,rows]}` |
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
