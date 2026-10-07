#!/usr/bin/env bash
# Build libsignal's Android JNI library (libsignal_jni.so, arm64-v8a) from the pinned source.
#
# Usage: build-libsignal.sh [all|fetch|build]
#   fetch  Network phase (F-Droid `prebuild`): install the pinned rustup and Rust toolchain
#          (if missing), check out the pinned libsignal commit, delete everything this build
#          does not use, and download the locked crates into CARGO_HOME (outside the tree).
#   build  Offline phase (F-Droid `build`, after the source scan): build libsignal-jni with
#          the pinned NDK and toolchain, strip it, check it and write
#          build/output/{PROVENANCE,SHA256SUMS}. The output is reproducible for a fixed build
#          path, toolchain and NDK; see build.expectedSha256 in source.lock.json.
#   all    fetch + build (default).
#
# The matching Java/Kotlin API is compiled by Gradle straight from the pruned checkout
# (modules :libsignal:client and :libsignal:android, enabled by -PlibsignalBuiltFromSource).
# PRIVEE_LIBSIGNAL_JOBS overrides the build parallelism (default: nproc).
set -euo pipefail

phase="${1:-all}"
case "$phase" in
  all | fetch | build) ;;
  *)
    echo "Usage: $0 [all|fetch|build]" >&2
    exit 2
    ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$root/source.lock.json"
source_dir="$root/build/source/libsignal"
output_dir="$root/build/output"
jobs="${PRIVEE_LIBSIGNAL_JOBS:-$(nproc)}"
export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.rustup}"
export PATH="$CARGO_HOME/bin:$PATH"

require() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null || {
      echo "Required host tool is unavailable: $tool" >&2
      exit 1
    }
  done
}

lock_value() {
  python3 - "$lock" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for key in sys.argv[2].split("."):
    value = value[key]
if isinstance(value, dict):
    print("\n".join(f"{k} {v}" for k, v in sorted(value.items())))
elif isinstance(value, list):
    print("\n".join(str(item) for item in value))
else:
    print(value)
PY
}

require git python3 sha256sum
repository="$(lock_value source.repository)"
commit="$(lock_value source.commit)"
toolchain="$(lock_value rust.toolchain)"
rust_target="$(lock_value rust.target)"

install_rust() {
  if ! command -v rustup >/dev/null; then
    require curl
    local version sha init
    version="$(lock_value rust.rustupVersion)"
    sha="$(lock_value rust.rustupInitSha256)"
    init="$(mktemp)"
    curl -fsSL --retry 3 -o "$init" \
      "https://static.rust-lang.org/rustup/archive/$version/x86_64-unknown-linux-gnu/rustup-init"
    echo "$sha  $init" | sha256sum -c --quiet - || {
      echo "rustup-init $version does not match SHA-256 $sha." >&2
      exit 1
    }
    chmod +x "$init"
    "$init" -y --no-modify-path --profile minimal --default-toolchain none
    rm -f "$init"
  fi
  rustup toolchain install "$toolchain" --profile minimal --target "$rust_target"
}

fetch() {
  install_rust
  mkdir -p "$(dirname "$source_dir")"
  if [[ ! -d "$source_dir/.git" ]]; then
    git init -q "$source_dir"
    git -C "$source_dir" remote add origin "$repository"
  fi
  git -C "$source_dir" fetch -q --depth 1 origin "$commit"
  git -C "$source_dir" checkout -q --force --detach "$commit"
  git -C "$source_dir" clean -q -f -d -x
  if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
    echo "libsignal checkout does not match the pinned commit $commit." >&2
    exit 1
  fi
  if [[ "$(cat "$source_dir/rust-toolchain")" != "$toolchain" ]]; then
    echo "libsignal pins Rust $(cat "$source_dir/rust-toolchain"), source.lock.json pins $toolchain." >&2
    exit 1
  fi

  # Keep only what this build reads, so the scanned tree has no Gradle wrapper jars,
  # prebuilt test fixtures or other platforms' bindings.
  python3 - "$source_dir" "$lock" <<'PY'
import json, pathlib, shutil, sys
tree = pathlib.Path(sys.argv[1])
preparation = json.load(open(sys.argv[2], encoding="utf-8"))["preparation"]
keep = set(preparation["keep"])

def prune(directory, prefix):
    for child in sorted(directory.iterdir()):
        relative = f"{prefix}{child.name}"
        if relative == ".git" or relative in keep:
            continue
        if child.is_dir() and not child.is_symlink() and any(k.startswith(relative + "/") for k in keep):
            prune(child, relative + "/")
        elif child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()

prune(tree, "")
# Inside kept directories but unused by the build, e.g. fuzzing seeds the F-Droid scanner
# flags as binaries.
for relative in preparation.get("remove", []):
    path = tree / relative
    if not path.exists():
        sys.exit(f"preparation.remove entry {relative} does not exist in the checkout")
    shutil.rmtree(path) if path.is_dir() else path.unlink()
PY
  if [[ -n "$(git -C "$source_dir" status --porcelain | grep -v '^ D ' || true)" ]]; then
    echo "Preparing the libsignal tree changed more than deletions." >&2
    exit 1
  fi

  (cd "$source_dir" && cargo "+$toolchain" fetch --locked)
  echo "libsignal $commit and its crates are ready."
}

build() {
  local ndk="${NDK_ROOT:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}}"
  if [[ -z "$ndk" || ! -f "$ndk/source.properties" ]]; then
    echo "Set NDK_ROOT (or ANDROID_NDK_ROOT/ANDROID_NDK_HOME) to the pinned Android NDK." >&2
    exit 1
  fi
  ndk="$(cd "$ndk" && pwd -P)"
  local ndk_revision expected_ndk
  ndk_revision="$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk/source.properties" | tr -d '\r')"
  expected_ndk="$(lock_value build.ndkRevision)"
  if [[ "$ndk_revision" != "$expected_ndk" ]]; then
    echo "NDK $ndk_revision does not match pinned NDK $expected_ndk." >&2
    exit 1
  fi
  if [[ ! -d "$source_dir/.git" || "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
    echo "Pinned libsignal source is missing; run '$0 fetch' first." >&2
    exit 1
  fi
  if [[ -n "$(git -C "$source_dir" status --porcelain | grep -v '^ D ' | grep -v '^?? target/' || true)" ]]; then
    echo "The libsignal tree has changes other than the fetch-phase deletions." >&2
    exit 1
  fi
  require cargo rustup
  local rustc_version
  rustc_version="$(rustc "+$toolchain" -V)"

  local api bin package output
  api="$(lock_value build.androidApi)"
  package="$(lock_value build.package)"
  output="$root/$(lock_value build.output)"
  bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"

  # Reproducible builds: timestamps from the pinned libsignal commit, no build paths in
  # Rust or C outputs (libsignal's own remapping plus the C prefix maps below).
  SOURCE_DATE_EPOCH="$(git -C "$source_dir" log -1 --format=%ct)"
  export SOURCE_DATE_EPOCH
  local prefix_map="-ffile-prefix-map=$source_dir=/libsignal -ffile-prefix-map=$CARGO_HOME=/cargo -ffile-prefix-map=$ndk=/ndk"

  local rustflags="" cfg
  while read -r cfg; do
    rustflags+="--cfg $cfg "
  done < <(lock_value build.rustCfg)
  local prefix
  while read -r prefix; do
    rustflags+="--remap-path-prefix $prefix= "
  done < <(cd "$source_dir" && RUSTUP_TOOLCHAIN="$toolchain" python3 bin/build_helpers.py print-rust-paths-to-remap)
  rustflags+="--remap-path-prefix $ndk=/ndk"

  local -a features
  mapfile -t features < <(lock_value build.features)

  # The Android settings of libsignal's java/build_jni.sh, for the one ABI we ship.
  local cc="$bin/aarch64-linux-android$api-clang"
  (
    cd "$source_dir"
    export RUSTFLAGS="$rustflags"
    export CARGO_NET_OFFLINE=true
    export CARGO_PROFILE_RELEASE_DEBUG=0
    export CARGO_PROFILE_RELEASE_OPT_LEVEL=s
    export CARGO_PROFILE_RELEASE_LTO=fat
    export CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
    export CFLAGS="-DOPENSSL_SMALL -flto=full $prefix_map"
    export CXXFLAGS="-DOPENSSL_SMALL -flto=full $prefix_map"
    export CC_aarch64_linux_android="$cc"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$cc"
    export TARGET_AR="$bin/llvm-ar"
    export ANDROID_NDK_HOME="$ndk"
    # boring-sys runs bindgen; use the pinned NDK's libclang and clang (for its builtin
    # headers) rather than the host's.
    export LIBCLANG_PATH="$ndk/toolchains/llvm/prebuilt/linux-x86_64/lib"
    export CLANG_PATH="$bin/clang"
    # Same package set as upstream, so feature unification (and the bytes) match; only
    # libsignal_jni.so is shipped.
    cargo "+$toolchain" build --locked --offline --release -j "$jobs" \
      -p "$package" -p "$package-testing" --features "$(IFS=,; echo "${features[*]}")" --target "$rust_target"
  )

  rm -rf "$output_dir"
  mkdir -p "$(dirname "$output")" "$output_dir/assets/acknowledgments"
  # Upstream's libsignal-android AAR ships the notices of the Rust dependencies as this asset.
  cp "$source_dir/acknowledgments/acknowledgments-android.md" \
    "$output_dir/assets/acknowledgments/libsignal.md"
  cp "$source_dir/target/$rust_target/release/libsignal_jni.so" "$output"
  # Strip with the pinned NDK so the shipped bytes do not depend on AGP's NDK; the app
  # packages this file with keepDebugSymbols.
  "$bin/llvm-strip" --strip-unneeded "$output"

  "$bin/llvm-readelf" -h "$output" | grep -Eq 'AArch64' || {
    echo "$output is not an AArch64 ELF." >&2
    exit 1
  }
  local align needed
  while read -r align; do
    if (( align < 16384 )); then
      echo "$output is not 16 KB page aligned." >&2
      exit 1
    fi
  done < <("$bin/llvm-readelf" -lW "$output" | awk '$1 == "LOAD" { print $NF }')
  while read -r needed; do
    case "$needed" in
      libc.so | libm.so | libdl.so | liblog.so | libz.so) ;;
      *)
        echo "$output links unexpected library $needed." >&2
        exit 1
        ;;
    esac
  done < <("$bin/llvm-readelf" -dW "$output" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
  if grep -aq -- '-LEVEL LOGS ENABLED' "$output"; then
    echo "$output was built with debug-level logs." >&2
    exit 1
  fi

  (
    cd "$root"
    sha256sum "$(lock_value build.output)"
  ) | tee "$output_dir/SHA256SUMS"
  # Gradle's -PlibsignalBuiltFromSource enforces build.expectedSha256; report early.
  python3 - "$lock" "$output_dir/SHA256SUMS" <<'PY'
import json, sys
expected = json.load(open(sys.argv[1], encoding="utf-8"))["build"].get("expectedSha256")
actual = {}
for line in open(sys.argv[2], encoding="utf-8"):
    if line.strip():
        digest, path = line.rstrip("\n").split("  ", 1)
        actual[path] = digest
if expected is None:
    print("source.lock.json does not pin build.expectedSha256 yet; this build produced:")
    print(json.dumps(actual, indent=2, sort_keys=True))
elif expected != actual:
    print("WARNING: the source-built libsignal differs from build.expectedSha256 in "
          "source.lock.json; the build is not reproducible on this host.", file=sys.stderr)
    for path in sorted(set(expected) | set(actual)):
        print(f"  {path}: expected {expected.get(path)}, built {actual.get(path)}", file=sys.stderr)
PY
  {
    printf 'source=%s\n' "$repository"
    printf 'commit=%s\n' "$commit"
    printf 'rustc=%s\n' "$rustc_version"
    printf 'target=%s\n' "$rust_target"
    printf 'ndk=%s\n' "$ndk_revision"
  } > "$output_dir/PROVENANCE"
  cat "$output_dir/PROVENANCE"
}

case "$phase" in
  fetch) fetch ;;
  build) build ;;
  all)
    fetch
    build
    ;;
esac
