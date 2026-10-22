# Using homectl away from home

Order the app tries, per machine (all probed at once, best answer wins):

1. **LAN** — same Wi-Fi, instant.
2. **Tunnel** — a free Cloudflare *quick tunnel* that the machine opens **outward** (no router settings, works behind carrier-grade
   NAT). Started by the daemon when `HOMECTL_TUNNEL=quick` (the server's boot service does this by default).
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

## Security notes

- The tunnel is reachable by anyone who knows its address; the only thing protecting the daemon is the access token (24 random
  bytes, with slow-down on wrong guesses). Turn the tunnel off with `HOMECTL_TUNNEL=off`.
- Cloudflare terminates TLS for the tunnel and can see the traffic. The pinned-certificate HTTPS used on LAN/Tailscale does not
  apply to the tunnel. For stricter privacy, don't use the tunnel (Tailscale only).
