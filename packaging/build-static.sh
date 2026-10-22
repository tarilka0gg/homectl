#!/bin/sh
# Builds a static, CPU-agnostic (x86-64 baseline) musl binary that runs on any x86_64 Linux, Alpine included.
# Needs: zig (as C compiler/linker) and an *official* rustc (rustup) with the musl target, because
# distro rustc builds reject the official musl std. Example setup:
#   RUSTUP_HOME=~/rustup CARGO_HOME=~/rustup-cargo rustup toolchain install stable -t x86_64-unknown-linux-musl
set -eu
cd "$(dirname "$0")/.."
ZIGCC=$(mktemp)
cat > "$ZIGCC" <<'EOF'
#!/bin/sh
args=""
for a in "$@"; do
  case "$a" in --target=*) ;; *) args="$args '$(printf %s "$a" | sed "s/'/'\\\\''/g")'" ;; esac
done
eval exec zig cc -target x86_64-linux-musl -mcpu=x86_64 $args
EOF
chmod +x "$ZIGCC"
export CC_x86_64_unknown_linux_musl="$ZIGCC" CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER="$ZIGCC"
# the home directory, the cargo registry and the checkout location must not end up inside the binary (panic messages carry source paths)
CH=${CARGO_HOME:-$HOME/.cargo}
export CARGO_ENCODED_RUSTFLAGS="$(printf -- '-Clink-self-contained=no\037-Ctarget-cpu=x86-64\037--remap-path-prefix=%s=/cargo\037--remap-path-prefix=%s=/home/user\037--remap-path-prefix=%s=/src' "$CH" "$HOME" "$(pwd)")"
cargo build --release --target x86_64-unknown-linux-musl
rm -f "$ZIGCC"
echo "built target/x86_64-unknown-linux-musl/release/homectl"
