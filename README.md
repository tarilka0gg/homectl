# homectl

Control your own computers and smart home from your phone. A small daemon runs on each machine; a native Android app (Material 3) finds them, pairs with a 6-digit code, and gives you a live system monitor, files, a persistent terminal, audio, power controls, lights and a shared clipboard — over your Wi-Fi, Tailscale, or a public tunnel when you are away from home.

<p>
<img src="docs/monitor.png" width="190" alt="Monitor">
<img src="docs/controls.png" width="190" alt="Controls">
<img src="docs/terminal.png" width="190" alt="Terminal">
<img src="docs/files.png" width="190" alt="Files">
</p>

> **Security first.** homectl gives whoever controls it a **shell, your files and power control**. Read [SECURITY.md](SECURITY.md) and
> [docs/AWAY.md](docs/AWAY.md) before turning on the public tunnel. It is built for people who run their own machines; it is not a
> hosted service and there is no account.

## What it does

| Tab | |
|---|---|
| **Monitor** | btop-style live view: CPU (per-core load, temperature, clocks, governor, throttling), every GPU, memory and swap, **per-disk activity** (busy %, read/write, IOPS, temperature, partitions, which processes use the disk, and a fast "what takes the space" view with open/delete), network, battery health and power draw, pressure (PSI), **all hardware sensors**, fans, and a sortable, searchable process table with terminate/kill/pause. Several machines side by side. |
| **Controls** | Screens off/on and **brightness for every screen** (built-in panel and DDC/CI monitors), Govee lights with a **colour wheel** and white temperature (per light and for all), Wi-Fi/Bluetooth/airplane, per-device and per-app **audio mixer**, webcam controls, public-tunnel switch. |
| **Terminal** | A real shell (xterm.js) that **keeps running on the machine**: leave, close the app, lose Wi-Fi, and reattach to the same screen. Pick the shell, several sessions, split view, special-key rows, scrollback buffer, SSH shortcut. |
| **Files** | Browse with thumbnails and icons, **type a path**, **search**, back/forward (gestures included), **preview everything** (images, text, PDF, video, audio, hex), **edit text**, rename, delete, upload, unzip and look inside archives, **details** (owner, permissions, dates, checksums, image size…). The machines also appear in Android's own file picker. |
| **System** | Power modes (your own on/off scripts), services, lock/suspend/reboot/power off, a **clipboard** you can send to and get from with pickers (text *and pictures*, plus Noctalia's history), an optional **common clipboard** that runs as a background service, send files, connection test, and the **Security** section (below). |

## Download

Releases are on the **[Releases page](../../releases/latest)**:

| File | What it is |
|---|---|
| `homectl-x.y.z.apk` | The Android app (Android 8+) |
| `homectl-linux-x86_64` | The daemon: a static binary that runs on any x86-64 Linux |
| `homectl-send-android-arm64` | Tiny file uploader for Termux / ARM |
| `SHA256SUMS` | Checksums |

The APK is signed with APK Signature Scheme v2+v3; the certificate fingerprints are in [docs/SIGNING.md](docs/SIGNING.md).
Check yours with `apksigner verify --print-certs homectl-x.y.z.apk`. Android may warn about an app from outside the Play Store;
that page also explains what that warning means and how to deal with it.

## Quick start

**1. On each computer you want to control**
```sh
chmod +x homectl-linux-x86_64
./homectl-linux-x86_64          # listens on port 7878 (HTTP and HTTPS on the same port)
```
It uses `brightnessctl`, `wpctl`/`pactl`, `nmcli`, `bluetoothctl`, `ddcutil`, `wl-clipboard`, `loginctl`, `doas` when present; a feature whose tool is missing simply doesn't appear.

**2. Install the app** — open the `.apk` on the phone (allow "install unknown apps" once).

**3. Pair** — the app lists machines it finds on your network; tap **Pair**, and a 6-digit code appears on the machine (a desktop notification, or run `./homectl-linux-x86_64 pair` on a server). Alternatively `homectl pair` prints a `homectl://pair?…` link and a QR code you can open on the phone. A pairing link contains the machine's secret token: treat it like a password.

**4. Open the app once on the home network after pairing.** That is when the phone registers its key with the machine (see below), learns the machine's tunnel and relays, and pins its certificate.

## How the connection works

The app keeps every address a machine reports and tries them **all at once**, using the best answer:

1. **LAN** — same Wi-Fi.
2. **Tunnel** — an outbound Cloudflare *quick tunnel* the daemon opens (`HOMECTL_TUNNEL=quick`, needs `cloudflared` next to the binary or on `PATH`). No router settings; works behind carrier-grade NAT. The phone finds the tunnel's new address whenever it changes through a signed announcement on a private [ntfy](https://ntfy.sh) topic.
3. **Through another machine** — an always-on machine with a tunnel relays to your others.
4. **Tailscale / Headscale** — last fallback.

Details, and what each third party can see: [docs/AWAY.md](docs/AWAY.md).

### Security model in short

- Every request needs the machine's **access token**.
- LAN and Tailscale use **HTTPS with the certificate the phone pinned** when it paired (the daemon makes its own self-signed certificate; no CA, no account). Optionally refuse plain HTTP.
- **Through the tunnel a request must also be signed by a registered phone**: each phone has a hardware-backed key that never leaves it, registered once on the home network, and every tunnel request carries a one-time signature. A leaked token alone is refused.
- The app **locks behind your fingerprint, face or screen lock**, keeps tokens and your `doas` password in the Android Keystore, and can hide itself from screenshots.
- Root actions (power modes, services, restart) use **your own `doas` password**, typed into a private terminal once and never stored or logged by the daemon.

Environment switches: `HOMECTL_NO_TERMINAL=1`, `HOMECTL_NO_DELETE=1`, `HOMECTL_NO_EDIT=1`, `HOMECTL_TUNNEL=off`, `HOMECTL_NO_RENDEZVOUS=1`, `HOMECTL_ADDR=host:port`, `HOMECTL_DRYRUN=1` (power actions only log).

## Notes per machine type

- **GPU:** Intel and AMD are read from sysfs. An NVIDIA dGPU is read with `nvidia-smi` **only while it is already awake and the laptop is on AC power** — asking a sleeping card wakes it. CPU package power needs read access to `/sys/class/powercap/intel-rapl:*/energy_uj` (the app offers to allow it).
- **Power / services** use `loginctl`, else `doas`. Without a `permit nopass` rule the app asks for your doas password instead.
- **Govee:** enable **LAN Control** for each light in the Govee app.
- **Clipboard** uses `wl-clipboard`; Noctalia's history is read from `~/.local/state/noctalia/clipboard` if it exists.

## Power modes and screens

- **Screens off/on:** the built-in panel by backlight, external monitors by DDC/CI power mode (`ddcutil setvcp d6`), and optionally the compositor output (niri).
- **Power modes:** a pair `<name>-on.sh` + `<name>-off.sh` in your home, `~/.local/bin` or `/usr/local/bin` shows up automatically; more (with a state check and warning text) go in `~/.config/homectl/actions.json` — see `docs/actions.example.json`. They run through `doas`; the app shows the exact `permit nopass` line to add or asks for your password.

## Run at boot (OpenRC)
```sh
doas sh packaging/install-service.sh [USER] [PATH-TO-homectl]
```
Installs a supervised service that restarts itself and (by default) starts the public tunnel — set `HOMECTL_TUNNEL=off` in `/etc/conf.d/homectl` to disable. Updating later needs no root: replace the binary and `kill` the running one.

## API

All routes need `Authorization: Bearer <token>` except `/api/ping` and pairing; through the tunnel they also need the device signature. JSON in and out. A selection:

| Route | |
|---|---|
| `GET /api/monitor[?since=&cores=1]` | full snapshot, history (only newer samples with `since`) |
| `GET /api/glance` | three numbers per machine, cheap |
| `GET /api/fs/{list,search,usage,stat,hash,download,thumb}`, `POST /api/fs/{upload,mkdir,rename,write,delete}` | files |
| `GET /api/term` (WebSocket), `GET /api/term/sessions` | persistent terminal sessions |
| `GET/POST /api/clipboard`, `/api/clipboard/{image,state,history,noctalia}` | clipboard |
| `GET/POST /api/devices`, `GET/POST /api/security` | registered phones; security switches (home network only) |
| `GET/POST/DELETE /api/peers`, `/peer/{id}/…` | relay to other machines |
| `GET /api/endpoints`, `GET/POST /api/tunnel` | addresses, tunnel on/off |
| `GET /api/mixer`, `/api/radios`, `/api/wifi`, `/api/bluetooth`, `/api/lights`, `/api/displays`, `/api/actions`, `/api/services`, `/api/procs` … | controls |
| `POST /api/priv/{test,rapl}` | root helper (uses `X-Homectl-Pass`) |

## Build from source

```sh
cargo build --release && cargo test --release       # daemon (needs a C compiler)
packaging/build-static.sh                           # portable static musl build (zig + official rustc)
(cd zig && zig build -Doptimize=ReleaseSmall)       # homectl-send
(cd android && ./gradlew :app:assembleRelease)      # app: JDK 17, Android SDK platform 34 + build-tools 34.0.0
```
Without a keystore the app is signed with the Android debug key. To use your own set `HOMECTL_KEYSTORE` (and `HOMECTL_KEYSTORE_PASS`, `HOMECTL_KEY_ALIAS`, `HOMECTL_KEY_PASS`). See [CONTRIBUTING.md](CONTRIBUTING.md).

## Layout

`src/` Rust daemon · `csrc/` C shims (V4L2 webcam, PTY) · `zig/` uploader · `android/` Kotlin + Compose app · `web/` tiny built-in page · `packaging/` init script, static-build and release-signing scripts · `docs/` away-from-home design, signing notes, screenshots

## Licence

GPL-2.0-or-later, see [LICENSE](LICENSE). Bundled third-party code (xterm.js, Symbols Nerd Font, dependencies): [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
