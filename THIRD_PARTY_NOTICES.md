# Third-party software

homectl itself is GPL-2.0-or-later (see `LICENSE`). It bundles or builds on:

| Component | Where | Licence |
|---|---|---|
| [xterm.js](https://github.com/xtermjs/xterm.js) with the fit and canvas add-ons | `android/app/src/main/assets/xterm/` (terminal in the app) | MIT |
| Symbols Nerd Font Mono ([Nerd Fonts](https://github.com/ryanoasis/nerd-fonts)) | `android/app/src/main/assets/fonts/` (icon glyphs in the terminal; licence text next to the font) | MIT (the glyph sets inside keep their own permissive licences, see the Nerd Fonts licence file) |
| `virtual-keyboard-unstable-v1.xml` (Wayland protocol description, from wlroots / Purism) | `protocols/` (typing into windows from the phone) | MIT (notice inside the file) |
| Rust crates: axum, tokio, hyper, rustls, ring, rcgen, serde, image, jpeg-encoder, mimalloc (with Microsoft's mimalloc C library, static build only), zip, tar, flate2, libc … | built into the daemon (`Cargo.lock` lists exact versions) | MIT and/or Apache-2.0 (ring: ISC-style), each crate carries its own notice |
| AndroidX (Compose, Material 3, Activity, Biometric, ExifInterface, ProfileInstaller), Kotlin coroutines | built into the app | Apache-2.0 |
| [cloudflared](https://github.com/cloudflare/cloudflared) | **not bundled** — downloaded separately by whoever wants the public tunnel | Apache-2.0 |

The public tunnel and the tunnel-address announcements use third-party services (Cloudflare quick tunnels, ntfy.sh); see
`docs/AWAY.md` for what each one can see.
