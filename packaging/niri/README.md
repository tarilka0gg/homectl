# niri window-capture patch

niri can only copy what a monitor is showing, and its `screenshot-window` action always puts the picture in the clipboard. This
patch adds one IPC action, `CaptureWindow { id, path }`, which renders a single window, on any workspace and with the monitors
off, to a PAM file without touching the clipboard, then asks that window to draw its next frame. homectl uses it to show windows
that are not on a screen (watch only: taps and typing would reach whatever is on screen). An unpatched niri refuses the action,
so homectl simply falls back to showing what is on screen.

Gentoo:

    mkdir -p /etc/portage/patches/gui-wm/niri-26.04
    cp 26.04-capture-window.patch /etc/portage/patches/gui-wm/niri-26.04/
    emerge -1 gui-wm/niri

then log out and back in (niri is replaced only when it restarts). Elsewhere: `patch -p1 < 26.04-capture-window.patch` in the
niri 26.04 source and build as usual.
