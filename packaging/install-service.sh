#!/bin/sh
# Install homectl as a boot service on an OpenRC system (Alpine, Gentoo…). Run as root:
#     doas sh install-service.sh [USER] [PATH-TO-homectl]
# USER defaults to whoever ran doas/sudo; the binary defaults to ~USER/homectl/homectl.
# Updating later needs no root: replace the binary and `kill` the running one — the supervisor restarts it.
set -eu
[ "$(id -u)" = 0 ] || { echo "run as root:  doas sh $0"; exit 1; }
U=${1:-${DOAS_USER:-${SUDO_USER:-}}}
[ -n "$U" ] || { echo "usage: $0 USER [BINARY]"; exit 1; }
H=$(getent passwd "$U" | cut -d: -f6)
BIN=${2:-$H/homectl/homectl}
[ -x "$BIN" ] || { echo "homectl binary not found or not executable: $BIN"; exit 1; }
DIR=$(cd "$(dirname "$0")" && pwd)

install -m 755 "$DIR/homectl-launch.sh" /usr/local/bin/homectl-launch
install -m 755 "$DIR/homectl.initd" /etc/init.d/homectl
if [ ! -f /etc/conf.d/homectl ]; then
	printf 'HOMECTL_USER=%s\nHOMECTL_BIN=%s\n' "$U" "$BIN" > /etc/conf.d/homectl
fi
rc-update add homectl default

# a hand-started copy would keep port 7878 busy
for p in $(ps -o pid,args | awk -v b="$BIN" '$2=="./homectl" || $2==b {print $1}'); do
	kill "$p" 2>/dev/null || true
done
sleep 1
rc-service homectl restart
sleep 1
rc-service homectl status
echo "ok — homectl now starts at boot (log: /var/log/homectl.log)"
