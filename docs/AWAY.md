# Using homectl away from home

Order the app tries, per machine (all probed at once, best answer wins):

1. **LAN** — same Wi-Fi, instant.
2. **Tunnel** — a free Cloudflare *quick tunnel* that the machine opens **outward** (no router settings, works behind carrier-grade
   NAT). Started by the daemon when `HOMECTL_TUNNEL=quick` (always on) or `auto` (the boot service's default): with `auto`, a
   machine that a server relays to (route 3) keeps its own tunnel **off** while that server answers and has its tunnel up, starts it
   after about a minute without an answer, and stops it again after about a minute of the server being back. The app tells each
   machine its server's LAN/Tailscale address when it links them (`/api/hubs`); a machine with no server, or one that is itself
   somebody's server, keeps its tunnel on. Switching the tunnel by hand pauses `auto` until the daemon restarts.
3. **Through another machine** — a machine with a tunnel (the always-on server) relays to your other machines, so the laptop
   works away from home too, as long as the laptop is on and at home. Set up automatically once both are paired.
4. **Tailscale / Headscale** — last fallback.

## How the phone finds the tunnel after it changes

A quick tunnel gets a new random address whenever it restarts. The daemon posts its current address to a private topic on
ntfy.sh (the topic name is random and kept in `~/.config/homectl/rendezvous`), **signed with the machine's access token**
(HMAC-SHA256 over `url|timestamp`). When nothing answers, the app polls that topic, accepts only correctly signed, non-older
messages for `*.trycloudflare.com`, and switches to the new address. Anyone who learns the topic name can neither redirect the
app nor read anything useful: the message is just an address, and the machine still needs the access token.
Disable with `HOMECTL_NO_RENDEZVOUS=1`.

## Before you leave home

Open the app once on the home network (after updating): it learns the tunnel, the announcement channel and registers the other
machines with the server. System → Connection → *Test the connection* shows what works.

## Your phone's key (device registration)

Each phone makes an EC P-256 key pair **inside its secure hardware** (StrongBox where available); the private key can't be read
out. The first time the app reaches a machine over the home network or Tailscale it registers the *public* key there
(`~/.config/homectl/devices.json`). From then on every request that arrives **through the tunnel** must carry a signature from a
registered phone over `method + path + query + timestamp + one-time nonce` (valid two minutes, once). A leaked access token alone
is refused with `device_required`; so are replays, a signature reused on another path, and old timestamps. Registering (and
changing security settings) is refused through the tunnel or another machine, so it can only happen at home.

- A machine with no registered phone refuses everything through the tunnel — open the app once at home after updating.
- System → Security shows this phone's fingerprint and the registered phones; Remove revokes one. "Require this phone on the home
  network too" extends the check to LAN and Tailscale.
- Not covered: request bodies (they travel inside TLS), and an attacker who also controls the phone itself.

## Security notes

- The tunnel is reachable by anyone who knows its address; it is protected by the access token **and** a registered phone's signature
  (see above). Turn the tunnel off with `HOMECTL_TUNNEL=off`.
- Cloudflare terminates TLS for the tunnel and can see the traffic. The pinned-certificate HTTPS used on LAN/Tailscale does not
  apply to the tunnel. For stricter privacy, don't use the tunnel (Tailscale only).
