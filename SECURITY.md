# Security policy

homectl gives whoever controls it a shell, file access and power control on your machines, so security reports are taken seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's private reporting: *Security → Report a vulnerability* on this repository.
Include what you found, how to reproduce it, and the version (the app shows it under System; the daemon prints it at start).
You will get an answer within a few days; fixes ship as a new release and are credited unless you prefer otherwise.

## What the design assumes

- **Access token** (24 random bytes, `~/.config/homectl/token`, mode 600) is the credential for every request. Anyone who has it
  can use the machine over any route that doesn't additionally require a device signature.
- **Through the public tunnel** a request must *also* carry a signature from a registered phone (hardware-backed EC P-256 key,
  one-time, two-minute window). Registration only happens over the home network. See `docs/AWAY.md`.
- **On the LAN / Tailscale** the connection is HTTPS with a certificate the phone pinned when it paired (or plain HTTP inside the
  tailnet); "Refuse plain connections" and "Require this phone on the home network too" tighten this.
- **Root actions** use the user's own `doas` password, sent once over the encrypted link and typed into a pseudo-terminal; the
  daemon never stores or logs it. The phone keeps it in the Android Keystore behind biometrics/screen lock.
- **Govee API key** (optional, for lights without LAN Control) is saved in `~/.config/homectl/govee.key` (mode 600). The daemon never
  returns it through its API or logs it, passes it to `curl` on standard input (not on the command line) and sends it only to
  `openapi.api.govee.com`. Anyone with the access token can replace or remove it, not read it.
- **Not covered:** a compromised phone that is unlocked; request bodies aren't signed (they travel inside TLS); the tunnel's TLS is
  terminated by Cloudflare, which can see that traffic; free quick tunnels have no uptime guarantee.

## Windows

The Windows tab lists window titles and can show and type into terminals started through `homectl-pty`, so it carries the same
risk as the terminal: whoever holds the token (and, through the tunnel, a registered phone) can read and type into those
windows. The wrapper's socket lives in a private directory of the user (`$XDG_RUNTIME_DIR/homectl/win`, mode 0700; the socket
is 0600), so other users on the machine cannot reach it. See [docs/WINDOWS.md](docs/WINDOWS.md).

## Hardening checklist

`HOMECTL_NO_TERMINAL=1` disables the terminal and the mirroring of terminal windows, `HOMECTL_NO_DELETE=1` file deletion and closing windows, `HOMECTL_NO_EDIT=1` file saving,
`HOMECTL_TUNNEL=off` the tunnel, `HOMECTL_NO_RENDEZVOUS=1` the tunnel-address announcements.

## Supported versions

Only the latest release is supported.
