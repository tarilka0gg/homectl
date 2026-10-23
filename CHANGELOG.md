# Changelog

All notable changes to homectl, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/), with
`## [version] - date` headings. The app and the daemon share one version number.

## [Unreleased]

## [0.18.0] - 2026-10-23

### Added
- **Windows tab.** The windows open on the machine's desktop as data, not pixels: title, app, icon, workspace and focus for every niri window, with bring-to-front and close. Cards open and close with an animation, and the focused window is outlined.
- **`homectl-pty`**, a small static Zig program that runs your shell inside a terminal window and mirrors its screen. A mirrored terminal shows its real text, colours, true colour, wide characters, cursor, title and scrollback on the phone, live, and you can type into it with a key row (Esc, Tab, Ctrl-C, arrows, Home/End, PgUp/PgDn). Full-screen programs (less, vim, htop) work.
- `docs/WINDOWS.md` describes the format; `/api/windows`, `/api/windows/{id}/screen|input|focus|close` and `/api/window-icon/{app}`.
- Tests: eight for the terminal screen model in Zig (including random-input fuzzing with safety checks on), three for the daemon side.

### Changed
- The OpenRC service starts the daemon through a small launcher that restarts it once your sound server is up after login, so audio, clipboard and screen controls work after a reboot without a manual restart. `cloudflared` is also found in `~/.local/bin`.

## [0.17.1] - 2026-10-22

### Added
- Licence (GPL-2.0), `SECURITY.md`, `CONTRIBUTING.md`, third-party notices, an issue template and a README with screenshots of the current app.
- CI on GitHub Actions: daemon build and tests, app build; every action pinned to a commit.
- Unit tests for the authentication code (device signatures, announcement signatures, the root-password header).
- System shows the app's and the machine's daemon version; the daemon prints its version at start.

### Changed
- The app builds without a keystore (it falls back to the Android debug key); `HOMECTL_KEYSTORE` selects your own. The Gradle wrapper is included and checksum-pinned.
- Release binaries are no longer tracked in the repository.

## [0.17.0] - 2026-10-22

### Added
- **Device keys.** Each phone creates an EC P-256 key in its secure hardware and registers the public half with each machine once, on the home network. Through the public tunnel a machine only answers requests signed by a registered phone (one-time, two-minute signatures over method, path, query and a nonce). A leaked access token alone no longer opens the tunnel.
- System → Security shows the phone's fingerprint and the registered phones, with Remove, and an option to require the signature on the LAN too.
- The daemon logs why it refused a request (method and path only).

### Changed
- The terminal asks for a fresh signed address on every reconnect. The video player downloads the file first when the route needs signatures.

## [0.16.0] - 2026-10-22

### Added
- **Away from home.** The daemon opens an outbound Cloudflare quick tunnel; its changing address is announced on a private ntfy topic, signed with the access token, and the app switches to it when nothing else answers. Route order: LAN, tunnel, through another machine, Tailscale.
- The always-on machine relays to the others, registered automatically once both are paired.
- `docs/AWAY.md`.

## [0.15.2] - 2026-10-22

### Added
- Connection test: when a machine is offline the app tests every address and says in plain words why (VPN state, which address answered, how fast).

### Changed
- Tailscale, relay and tunnel addresses get up to 6 s to find a path; only same-network addresses are given up on quickly.

## [0.15.1] - 2026-10-22

### Changed
- Releases are signed with a new RSA-4096 key (APK Signature Scheme v2 + v3), set up as a rotation of the original key so existing installs update in place. `docs/SIGNING.md`.

## [0.15.0] - 2026-10-22

### Added
- The common clipboard runs as a foreground service with a notification, a **Send clipboard** action and a quick-settings tile, and restarts after a reboot.

## [0.14.0] - 2026-10-22

### Added
- Clipboard pictures in both directions; "Send to…" and "Get from…" pickers; the common clipboard syncs pictures too.

### Changed
- The clipboard card is a switch and two buttons; the phone → machine / machine → phone sub-sections are gone.

## [0.13.1] - 2026-10-22

### Added
- Browse Noctalia's clipboard history (text and pictures) from "Get from…", with search and "Use on <machine>".

## [0.13.0] - 2026-10-22

### Added
- Clipboard history on the machine (in memory only) and a **common clipboard** switch that shares copies in both directions.
- A colour wheel, brightness and white temperature for all lights at once.

## [0.12.1] - 2026-10-22

### Added
- A round colour wheel and white-temperature slider for each light; clipboard results are shown in a toast and a line under the card; the machine's clipboard image is shown instead of garbled text.

## [0.12.0] - 2026-10-22

### Added
- **App lock** with fingerprint, face or screen lock; tokens and `doas` passwords kept in the Android Keystore.
- **Root actions with your own `doas` password** (typed into a private terminal, never stored by the daemon) when no `permit nopass` rule exists.
- **HTTPS with a pinned certificate** on the LAN, Tailscale and relay routes; "Refuse plain connections".

## [0.11.0] - 2026-10-22

### Added
- File **details** (owner, permissions, dates, checksums, image size, line counts); a **Details** chip on every row.
- The app remembers where you were: tab, scroll positions, the folder in Files and each folder's position.
- Brightness slider for every screen; many more sensors (power, battery health, pressure, all hwmon sensors, CPU and memory details, Wi-Fi signal).

## [0.10.1] - 2026-10-22

### Changed
- The app connects without waiting for dead addresses, shows the last data at once on every tab, and draws all per-core graphs in one canvas.

## [0.10.0] - 2026-10-22

### Changed
- Monitor sends only samples newer than the last one (about 7 KB per poll instead of 85); slow readings are cached on the machine; connections are reused; a baseline profile ships with the app.

## [0.9.0] - 2026-10-22

### Added
- Disk-space scans are cached on the machine; folders you have seen show instantly; drawn file-type icons.

## [0.8.0] - 2026-10-22

### Added
- Files: rename, delete and edit text. The terminal's ＋ button opens a dialog that creates nothing until you pick a shell.

## [0.7.1] - 2026-10-22

### Changed
- "What takes space" scans all folders on every core (about a second for a 600 GiB home), with Open and Delete on each row.

## [0.7.0] - 2026-10-22

### Added
- Per-disk activity cards in the Monitor; a folder-size view; Files: path entry, search, back/forward history and swipes, previews for every type including video and audio.
- Relay: one machine forwards the app's requests to another (HTTP, ranges, uploads, terminal).

## [0.6.0] - 2026-10-21

### Added
- Screens off/on (backlight, DDC/CI, compositor output) and power modes (`*-on.sh` / `*-off.sh` pairs).

## [0.5.0] - 2026-10-21

### Added
- Files: thumbnails, previews, archive viewer and unzip. Boot service for OpenRC.

## [0.4.1] - 2026-10-21

### Fixed
- Terminal: shell picker, canvas renderer with Nerd Font symbols, tmux-style sizing.

## [0.4.0] - 2026-10-21

### Added
- Persistent terminal sessions, split view, the Files tab, GPU/temperature/fan graphs and a sortable process table.

## [0.3.0] - 2026-10-21

### Added
- Several machines, discovery and pairing with a 6-digit code, the public tunnel.

## [0.2.0] - 2026-10-21

### Added
- First release: daemon (monitor, audio, Wi-Fi, Bluetooth, DDC monitors, clipboard, webcam, Govee lights, file inbox), Zig uploader and the Android app.
