# Changelog

All notable changes to homectl, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/), with
`## [version] - date` headings. The app and the daemon share one version number.

## [0.19.1] - 2026-10-26

### Changed (memory)
- **The monitor allocates far less.** Measured on a desktop with 630 processes, with an allocation counter attached: a sample used to make about 18,000 allocations, almost two thirds of them in the process scan, even when nobody was watching. Now polled at once a second it makes **57% fewer allocations and about 74% fewer bytes** (156,000 instead of 364,000 calls, 21 MB instead of 79 MB in 20 seconds), and an idle daemon about 6,600 per sample. What changed: `/proc` and `/sys` files are read into a small buffer instead of `read_to_string`, which reserves 4 KB for every sysfs attribute because the kernel reports each as 4096 bytes long; the `/proc` directory is walked directly instead of making a name for every entry; the process table shares its command-line and user strings and reuses its maps between samples; the sensor layout (which files exist, labels, limits) is discovered once a minute and each sample only reads the values; and a process's name is read only for the few that are shown in the disk list. The output is unchanged.

### Fixed
- **A restart of the daemon no longer leaves its tunnel process behind.** `cloudflared` is now stopped together with the daemon (it is killed when its parent dies); before, a stopped daemon left an orphaned tunnel running next to the new one (35-50 MB each).
- **The app never makes a machine that is reached through a server into a server itself.** That could link two machines through each other, which also kept both of their tunnels running.

### Changed
- **A machine's own tunnel is off while a server reaches it, and starts by itself when the server does not.** Every machine ran its own `cloudflared` (35-50 MB each) even though the always-on server already relays to the others. With `HOMECTL_TUNNEL=auto` (now the boot service's default) a machine that a server is linked to keeps its tunnel off while that server answers and has its tunnel up; after about a minute without an answer it opens its own tunnel, and it closes it again after about a minute of the server being back. A machine with no server linked, or one that is itself somebody's server, keeps its tunnel on as before, and switching the tunnel by hand in Controls pauses the automatic control until the daemon restarts.
  - The app tells each machine its server's LAN and Tailscale addresses when it links them (`POST /api/hubs`; `GET` and `DELETE` too). Only private and tailnet `http://` addresses are accepted, and a machine that relays for others refuses to have a server of its own.
  - `/api/ping` now says whether the machine's public tunnel is up (`tunnel`), so a machine relying on it can tell. The tunnel row in Controls says when it is off because the server covers it.
  - `HOMECTL_TUNNEL=quick` (always on) and `off` work as before. Boot services that set nothing now get `auto`; with no server linked that is the same as `quick`.

## [0.19.0] - 2026-10-26

### Added
- **Govee lights without LAN Control can be controlled through Govee's cloud.** Some bulbs (for example the H6009 Smart LED Bulb) never answer the LAN protocol, so homectl could not see them at all. Paste a Govee API key (Govee Home app → Profile → Settings → Apply for API Key; it arrives by email) under **Controls → Lights → Govee cloud**, and the lights in your Govee account that the LAN scan did not find appear next to the others, marked "cloud", with the same power, brightness, colour wheel and white-temperature controls, and in the "all lights" group. Lights that answer on the LAN stay on the faster LAN path.
  - The key is stored on the machine (`~/.config/homectl/govee.key`, mode 600), is never sent back to the app or written to the log, is handed to `curl` on standard input so it is not in the process list, and goes only to Govee.
  - Govee allows about 10 commands a minute per light, so light states are cached for 45 seconds and the device list for 10 minutes; what you set is shown at once.
  - New daemon endpoints: `GET/POST/DELETE /api/govee` (is a key set / set it / remove it). An older daemon simply does not show the section.

## [0.18.8] - 2026-10-26

### Fixed
- **The app no longer closes the moment it opens when two machines relay through each other.** Finding a machine's address first resolves the machine it relays through; with two machines linked to each other (or one to itself) each waited for the other without end, the stack overflowed, and the app died at start whenever nothing was reachable yet, such as right after an update. A machine is never resolved twice in one chain now, and a relay is never routed through another relay.
- **A failed login no longer locks the phone out of the app.** A machine that answers but rejects this phone (it forgot the phone, or asks for a signature the app had not sent) used to count as unreachable, so the app showed "offline" for good and never registered the phone again. The address now stays usable, the top bar says **not registered**, and the Monitor tab says why.
- **The app signs requests as soon as a machine asks for it.** A machine set to require a signature on every request answered "device required" after the app restarted (it did not remember the setting) and the app stayed on "Loading…". It now switches to signing and retries, and registers the phone again when it is reached on the home network.
- **Saved logins that cannot be read for a moment are not treated as lost.** The Android keystore can be busy after an update or a restart; the app used to throw the machine list away and ask for a new pairing, and a new pairing would overwrite the saved logins. It now retries, and if the keystore still does not answer it says so, with **Try again**, and writes nothing until the logins can be read.
- **Daemon: a phone can register when the machine has none on record.** With "always sign" on and the device list lost, every request was refused, including the registration, so no phone could ever sign in again. A phone that holds the access token can now register from the home network in that one case, as it could before "always sign" was switched on.

## [0.18.7] - 2026-10-24

### Fixed
- **Power off, restart and suspend no longer fail silently.** The daemon used to answer "done" and then do nothing when the machine would only allow it with a password (a service started by OpenRC is not inside a login session, so logind refuses, and a later catch-all `doas` rule can override an earlier `nopass` one). It now checks first, without running anything, and answers `password_required`, so the app asks for the doas password; a wrong one is reported as `wrong_password`.
- **doas password prompts work with OpenDoas.** The daemon only recognised the OpenBSD wording ("Authorization required/failed"); OpenDoas (Gentoo, Arch…) says "Authentication required/failed", so a needed or wrong password was never noticed for power actions, services and other root actions. Both wordings are understood now.

## [0.18.6] - 2026-10-24

### Changed
- **The Terminal tab's key rows now work like the Windows tab's**: only the main row shows, and `⋯` opens the tools row (keyboard, paste, copy, select, buffer, font size, `^C`…) **above** it. In the Windows tab it is the same, so the two match.

## [0.18.5] - 2026-10-24

### Changed
- **The terminal view has the ordinary text box again**: type a whole line, with the cursor where you tap and your own keyboard, and it goes out with Enter. Typing letter by letter is a button (Live) in the second key row, for programs that need it.
- The second row of keys opens under the first, as in the Terminal tab.
- The text is a fixed, readable 13 sp (what the Terminal tab uses). Wide windows scroll sideways; **Fit** shrinks the font to the screen width when you want that.

### Added
- **Select and copy text**: press and hold on the output, drag, then Copy.
- **History up to 5000 lines** (the wrapper kept 1000): the app loads more as you scroll up.

### Fixed
- The keyboard button opens and closes the keyboard reliably (it used to act on a stale state and only ever opened it), and the bottom dock comes back when the keyboard closes however it was closed.

## [0.18.4] - 2026-10-23

### Changed
- **The terminal view types with your own keyboard, straight into the terminal**, in whatever layout and language it is set to (suggestions off, the way terminal apps ask for it), with the Terminal tab's key rows above it. The invisible-field keyboard from 0.18.2 is gone.

### Fixed
- Scrolling back through a terminal no longer fights live updates: the screen used to pull the view to the bottom while a finger was on it. The view follows the end of the output until you scroll away, and the app asks for all 1000 lines of history the wrapper keeps (it was 400).
- Closing a window now says whether the close was sent or refused. A card for a wrapped terminal that no window title points to (an older wrapper) can be closed too: its shell is hung up.

## [0.18.3] - 2026-10-23

### Fixed
- The app never replaces its device key or its keyring key when one exists but cannot be read at that moment (the keystore can be busy right after an update); it waits and retries. A new key would make the phone a stranger to every machine and lose the saved logins. Saved logins that cannot be opened are kept instead of deleted.

### Added
- **System → Copy diagnostics**: a short trail kept on the phone (last 60 events, no secrets) with crashes, why the previous run ended (crash, killed, force-stopped, …), unlock results and errors, app updates and lock state changes, so a problem after an update can be read off instead of guessed.

## [0.18.2] - 2026-10-23

### Added
- **The Windows tab no longer depends on niri.** Windows come from the standard wlroots foreign-toplevel protocol, so any compositor that offers it works; only title and app id are used, and the app no longer shows the desktop's focus.
- **Every terminal window has an invisible name of its own** (24 bits of zero-width characters that `homectl-pty` appends to its title), so several windows of one ghostty process are told apart exactly. Wrappers started before this version show up as separate cards.
- **Terminals mirror themselves.** New fish shells start inside `homectl-pty` through `packaging/shell/homectl.fish` (copy it to `~/.config/fish/conf.d/`); `homectl-attach` does the same for a shell that is already open. The new `homectl-adopt` helper, started by `homectl-launch`, does it for idle fish shells that were open before (it duplicates the terminal's pty with `pidfd_getfd` and types `homectl-attach`; needs yama `ptrace_scope` 0; off with `HOMECTL_NO_ADOPT=1` or `HOMECTL_NO_TERMINAL=1`).
- **The terminal view has the Terminal tab's keys** (Esc, Tab, sticky Ctrl and Alt, arrows with auto-repeat, F1–F12, `^C` and friends, Paste, Copy, font size) and types straight into the terminal. The bottom dock slides away with the keyboard and back when it closes. A tap clicks for programs that ask for the mouse, and otherwise opens the keyboard.

### Fixed
- Scrolling back through a terminal's history no longer snaps to the bottom whenever the screen changes.
- The app says so when saved logins could not be opened after a reinstall (Android removes the keys), and no longer half-restores a backup; the "this phone isn't registered" message always says what to do.
- OpenRC service: the daemon never started when `/var/log/homectl.log` did not exist yet (the supervisor opens it after dropping to the service user, so the service now creates it first).
- The shell part never wraps a login shell or a Linux console (it would have stopped a tty1 login script from starting the compositor).
- A refused device is logged with its id prefix, and every bring-to-front request is logged.

## [0.18.1] - 2026-10-23

### Fixed
- The app remembers the open tab by name instead of position, so adding the Windows tab no longer moved people to a different tab after updating (it opened Windows instead of Files).
- On a machine whose daemon is older than 0.18.0, the Windows tab no longer marks the whole machine offline every few seconds; it says the daemon needs updating.

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
