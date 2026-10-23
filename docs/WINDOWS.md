# Windows: your desktop on the phone as data, not pixels

The **Windows** tab is not a remote desktop. The phone never receives a picture of the screen. It receives *descriptions* of
what is open (a title, an app, an icon, a workspace, what has focus) and, for terminals, their *text*. The app draws all of
it natively, so it is sharp at any size, fast on a slow link, selectable, and uses almost no data.

## What is listed

| Source | What the phone gets |
|---|---|
| **niri** (the compositor's IPC socket) | every window: title, app id, icon, workspace, focus, floating. Bring to front and close work too. |
| **`homectl-pty`** | a terminal's real content: text, colours, attributes, cursor, title, working directory, scrollback. The phone can type into it. |

A terminal that was not started through `homectl-pty` still appears, with its title and icon, but a Wayland compositor does
not let any program read another window's text, so its content cannot be shown. Without niri, only `homectl-pty` terminals are
listed. Other compositors would need a small adapter (`src/windows.rs`, the `niri_*` functions).

## Mirroring a terminal

`homectl-pty [COMMAND…]` runs your shell (or the command) inside the terminal window you already have, and is transparent: it
puts the real terminal in raw mode, runs the command on a pty and copies bytes both ways. It also keeps a small model of the
screen. Use it as the terminal's command, for example in ghostty `command = homectl-pty`, in foot `shell=homectl-pty`, or
type `homectl-pty` once in a window. A window started this way is matched with its compositor window through the process tree.

The wrapper is a single static Zig program with no dependencies (`zig/pty.zig`). It serves the screen on a unix socket,
`$XDG_RUNTIME_DIR/homectl/win/<pid>.sock`, in a directory only you can enter; the daemon is the only thing that talks to it.

## The format

`GET /api/windows`

```json
{"niri": true, "windows": [
  {"id": "n12", "title": "…", "app_id": "com.mitchellh.ghostty", "workspace": "1", "focused": true, "floating": false,
   "kind": "terminal", "term": "p4242", "preview": ["last", "few", "lines"], "cwd": "/home/me", "cmd": "/bin/fish", "cols": 120, "rows": 32},
  {"id": "n15", "title": "…", "app_id": "zen", "workspace": "1", "focused": false, "floating": false, "kind": "window"}
]}
```

`kind` says how to draw a card. Today it is `terminal` or `window`; the card format is deliberately small so more kinds
(rendered text, markdown, notifications) can be added without changing the rest.

`GET /api/windows/{id}/screen?since=SEQ&back=N&wait=SECONDS` is a long poll. It answers as soon as the screen differs from
`since` (or `{"same":true}` after `wait` seconds, at most 20):

```json
{"v":1,"pid":4242,"seq":57,"cols":80,"rows":24,"cx":3,"cy":23,"cv":true,"alt":false,"title":"…","cwd":"…","cmd":"…",
 "sb":200,"lines":[ [["text",fg,bg,attrs], …], … ]}
```

`lines` is `sb` lines of scrollback followed by `rows` screen lines; each line is a list of runs of equal style. Colours:
`-1` default, `0–255` the palette, `256 + 0xRRGGBB` true colour. `attrs` bits: 1 bold, 2 dim, 4 italic, 8 underline, 16
inverse, 32 strike. Trailing blanks are dropped.

Other calls: `POST /api/windows/{id}/input {"text": "…"}` (keys, including escape sequences), `POST …/focus`, `POST …/close`,
`GET /api/window-icon/{app_id}` (the app's PNG icon from the machine's icon themes; 404 when it has none).

The long poll, rather than a WebSocket, is what makes this work through the signed tunnel and the relay with no extra code:
each request is an ordinary signed request.

## Safety

Everything above sits behind the same token, pinned certificate and (through the tunnel) hardware-key signature as the rest of
homectl. Window titles and terminal contents can be private, and typing into a mirrored terminal is a shell. `HOMECTL_NO_TERMINAL=1`
turns terminal mirroring and input off; `HOMECTL_NO_DELETE=1` also stops closing windows.

## Limits

* Terminals only for now; other windows show a card, not content.
* The screen model covers what shells and common full-screen programs (less, vim, htop, tmux) use. It is not a complete
  xterm: no mouse reporting, no sixel or images, no bracketed paste markers, combining characters are dropped.
* The phone's view is the size of the window on the machine; it scrolls rather than reflowing.

## Mirroring terminals that are already open

New fish shells wrap themselves (`packaging/shell/homectl.fish`). For windows that were open before, `homectl-adopt` (started by `homectl-launch`) looks every three seconds for fish shells of your user that are idle at the prompt (no foreground program, no keyboard input for 8 s) and not yet wrapped, duplicates the terminal's pty master with `pidfd_getfd` and types `homectl-attach` into it; the shell then restarts inside `homectl-pty`, keeping the folder and exported variables but not background jobs. Each shell is tried once. It needs the kernel to let you duplicate your own processes' fds (`/proc/sys/kernel/yama/ptrace_scope` = 0) and does nothing otherwise. Turn it off with `HOMECTL_NO_ADOPT=1` (or `HOMECTL_NO_TERMINAL=1`).
