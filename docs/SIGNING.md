# Signing, Play Protect and Google's developer verification

## What the APK is signed with

Release APKs carry **APK Signature Scheme v2 + v3** signatures:

| | certificate SHA-256 | key |
|---|---|---|
| current (Android 13+) | `AA:48:56:3D:9C:A6:DC:40:23:D3:B0:C0:CB:6A:96:91:C1:3A:0A:1B:05:7B:E9:E2:89:BD:C3:69:E7:A6:FC:C9` | RSA 4096, valid to 2056, passphrase-protected |
| original (Android 9–12, and the lineage root) | `B0:B5:0B:84:C1:0D:F6:86:E7:98:9C:B0:0D:2C:2F:31:C9:9B:99:D6:76:63:8C:7E:F6:44:CC:62:5B:25:70:D7` | RSA 2048 |

The new key is a **rotation** of the original (a signed lineage proves it), so a phone that has any earlier homectl keeps its
data, pairings and keyring when it updates. Check an APK yourself:

    apksigner verify --verbose --print-certs homectl-X.Y.Z.apk

and compare it with `SHA256SUMS` from the same release.

## Building a signed release

    cd android && gradle :app:assembleRelease          # AGP signs with the original key
    packaging/sign-release.sh app/build/outputs/apk/release/app-release.apk dist/release/homectl-X.Y.Z.apk

Keys live in `~/.config/homectl/` (`release.keystore`, `release.pass`, `release.lineage`; mode 600) and are **never** in the
repository. Back them up: without the release key an installed app can't be updated, only reinstalled.

## Why Play Protect still says "unrecognized app" — and what actually fixes it

A signature only proves *who made an update*; it does not make Google *recognise* the developer. Play Protect warns about apps
that are installed from outside the Play Store and come from a developer/app it has never seen. What helps, cheapest first:

1. **On the phone — send the APK to Google once.** Play Protect → *Install anyway* → *Scan app* / "Send app for scanning".
   A scanned, clean APK stops being flagged (per file, so repeat per release).
2. **Register as an Android developer (free "student / hobbyist" account)** at the Android Developer Console, verify identity,
   and register the app: package `dev.tarilka0gg.homectl` + the **current certificate SHA-256 above**. From Google's developer
   verification rollout (enforced from September 2026 in the first countries, then worldwide) registered apps install without the
   "unverified developer" friction; unregistered ones need an extra confirmation step on the phone.
3. **Appeal a false flag:** if Play Protect *blocks* the install, submit the APK through Google's Play Protect appeal form
   (support.google.com → Play Protect appeals) with this repository's URL.
4. **Publish through Play (closed/internal testing track)** — a one-time $25 developer account; Play-distributed apps are never
   "unknown" to Play Protect.

None of these can be done from inside this repository: they need your Google account and identity.
