#!/bin/sh
# Re-signs a built APK with the release key (RSA-4096, passphrase kept outside the repo) using APK Signature Scheme v2+v3,
# and the rotation lineage so phones that have an older homectl signed with the previous key update in place.
#   packaging/sign-release.sh in.apk out.apk
# Keys live in ~/.config/homectl: release.keystore, release.pass (mode 600), release.lineage. BACK THESE UP — losing the
# release key means every install must be removed and re-paired.
set -eu
BT=${ANDROID_BUILD_TOOLS:-$HOME/Android/sdk/build-tools/34.0.0}
CFG=${HOMECTL_KEYS:-$HOME/.config/homectl}
in=$1; out=$2
HOMECTL_KS_PASS=$(cat "$CFG/release.pass"); export HOMECTL_KS_PASS
cp "$in" "$out.tmp"
# v2 is signed by the original key (what every earlier release used), v3 by the new key plus the lineage proving the new key
# is a legitimate successor — Android 9+ accepts the new key as an update of the old one.
"$BT/apksigner" sign \
  --ks "$CFG/debug.keystore" --ks-key-alias homectl --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks "$CFG/release.keystore" --ks-key-alias homectl-release --ks-pass env:HOMECTL_KS_PASS --key-pass env:HOMECTL_KS_PASS \
  --lineage "$CFG/release.lineage" --min-sdk-version 26 \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$out" "$out.tmp"
rm -f "$out.tmp" "$out.idsig"
"$BT/apksigner" verify --verbose --print-certs "$out" | grep -E 'Verifies|scheme \(APK Signature Scheme v[23]\)|certificate SHA-256|key size'
