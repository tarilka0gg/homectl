#!/bin/sh
# Runs the homectl daemon for the OpenRC service (see homectl.initd) as the service user.
#     homectl-launch /path/to/homectl
# The daemon starts at once, so the tunnel and the monitor work from boot. If the user's sound server (PipeWire) is not
# up yet, it is restarted as soon as PipeWire answers after login, so audio, clipboard and screen controls see the session.
BIN=${1:?usage: homectl-launch BINARY}
uid=$(id -u)
RT=/run/user/$uid

session_ready() { [ -S "$RT/pipewire-0" ] && XDG_RUNTIME_DIR=$RT timeout 3 wpctl status >/dev/null 2>&1; }

session_env() {
	[ -d "$RT" ] || return 0
	export XDG_RUNTIME_DIR=$RT
	for s in "$RT"/wayland-*; do
		case "$s" in *.lock) ;; *) [ -S "$s" ] && { export WAYLAND_DISPLAY=${s##*/}; break; } ;; esac
	done
}

child=
trap '[ -n "$child" ] && kill "$child" 2>/dev/null; exit 0' TERM INT

while :; do
	ready=0; session_ready && ready=1
	session_env
	"$BIN" &
	child=$!
	if [ "$ready" = 0 ]; then
		( # wait for the session, give PipeWire a moment to settle, then ask the daemon to restart
			until session_ready; do sleep 3; done
			sleep 5
			kill -USR1 $$ 2>/dev/null
		) &
		watcher=$!
		trap 'kill "$child" "$watcher" 2>/dev/null; exit 0' TERM INT
		trap 'kill "$child" 2>/dev/null' USR1
	fi
	wait "$child"; rc=$?
	# a trapped signal also ends `wait`; keep waiting while the daemon is still alive
	while kill -0 "$child" 2>/dev/null; do wait "$child"; rc=$?; done
	[ -n "${watcher:-}" ] && kill "$watcher" 2>/dev/null
	# 143 = we stopped it for the restart above; any other exit goes back to the supervisor
	[ "$rc" = 143 ] && [ "$ready" = 0 ] && { ready=1; continue; }
	exit "$rc"
done
