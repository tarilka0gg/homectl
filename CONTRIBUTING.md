# Contributing

Thanks for looking! The project is small; issues and pull requests are welcome.

## Build

    cargo build --release && cargo test --release       # daemon (needs a C compiler)
    cd android && ./gradlew :app:assembleRelease         # app (JDK 17, Android SDK platform 34 + build-tools 34.0.0)

Without a keystore the app is signed with the standard Android debug key, which is fine for testing. To sign with your own key set
`HOMECTL_KEYSTORE` (and `HOMECTL_KEYSTORE_PASS`, `HOMECTL_KEY_ALIAS`, `HOMECTL_KEY_PASS`).

## Before sending a change

- `cargo test --release` passes; add a test when you touch anything in `device.rs`, `net.rs` or `privileged.rs` (they guard
  authentication).
- Don't commit keys, tokens, pairing links or screenshots that show them. `.gitignore` already blocks the usual files.
- Keep new daemon features optional when they need an external tool: if the tool is missing the feature should simply not appear.
- Anything that changes who can talk to the daemon (auth, tunnel, device keys) needs a note in `docs/AWAY.md` and `SECURITY.md`.
- Commit messages: say what changed and why, in the imperative.

## Layout

See the end of `README.md`. Security design: `SECURITY.md`, `docs/AWAY.md`. Release signing (maintainer only): `docs/SIGNING.md`.

By contributing you agree your work is licensed GPL-2.0-or-later, like the rest of the project.
