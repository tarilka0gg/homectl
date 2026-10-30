#!/bin/sh
# Installs the Claude Code reader as an OpenRC service (run as root):
#     sh packaging/install-claude-helper.sh USER [path/to/homectl]
# It copies the homectl binary to /usr/local/libexec/homectl-claude-helper (owned by root), so the user cannot change what runs
# as root, and starts the service at boot.
set -eu
user=${1:?usage: install-claude-helper.sh USER [BINARY]}
bin=${2:-$(dirname "$0")/../target/release/homectl}
here=$(dirname "$0")
install -D -o root -g root -m 0755 "$bin" /usr/local/libexec/homectl-claude-helper
install -o root -g root -m 0755 "$here/homectl-claude.initd" /etc/init.d/homectl-claude
printf 'HOMECTL_USER=%s\n' "$user" > /etc/conf.d/homectl-claude
rc-update add homectl-claude default
rc-service homectl-claude restart
