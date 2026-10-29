#!/usr/bin/env bash
# ============================================================================
# build-ariadne.sh — Ariadne as one static aarch64 musl binary for the pod
# ============================================================================
# Produces the vendor tarball that build-rootfs.sh seeds into /opt/ariadne
# inside the Podroid Alpine guest. Ariadne (daedalus-cpp, the C++ harness)
# replaces the Python Daedalus as the agent in the pod; the surface it serves
# is the one the app already speaks: /health, /health/detailed, /v1/models and
# /v1/chat/completions (SSE) on :8088, behind a per-install bearer key.
#
# Output:
#   files/usr/local/share/ariadne/ariadne-podroid.tar
#       opt/ariadne/ariadne                  static-pie aarch64: musl, libstdc++,
#                                            libgomp, libcurl + OpenSSL linked in
#       opt/ariadne/skills/highlight.yaml    the shipped language file (the
#                                            folder beside the binary is a
#                                            grammar root)
#
# Static on purpose: the binary needs nothing from the rootfs, so no apk line
# has to track what it links, and the same file runs on any aarch64 Linux.
#
# The source is `git archive` of DAEDALUS_CPP_SRC at DAEDALUS_CPP_REF: tracked
# files only, so nothing from a working home (config.yaml, state/, logs/) can
# reach a tarball that ships to devices.
#
# Usage:
#   ./build-ariadne.sh
#   DAEDALUS_CPP_SRC=/path/to/daedalus-cpp DAEDALUS_CPP_REF=main ./build-ariadne.sh
#
# Runs in an Alpine arm64 container: under qemu-aarch64 (binfmt) on an x86_64
# host, natively on arm64. The translation units compile in parallel, one
# compiler per core, because a single g++ over all of them under qemu takes
# far longer than the same work spread out.
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$HERE/files/usr/local/share/ariadne}"
SRC="${DAEDALUS_CPP_SRC:-$HOME/projects/daedalus-cpp}"
REF="${DAEDALUS_CPP_REF:-main}"
ALPINE_TAG="${ALPINE_TAG:-3.23}"

log()  { printf '\033[1;36m[ariadne]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

command -v podman >/dev/null 2>&1 || die "podman not found"
git -C "$SRC" rev-parse --verify -q "$REF^{commit}" >/dev/null || die "$SRC has no commit '$REF'"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src" "$WORK/out"
rev="$(git -C "$SRC" rev-parse --short "$REF")"
log "source: $SRC @ $REF ($rev)"
git -C "$SRC" archive --format=tar "$REF" | tar -x -C "$WORK/src"

cat > "$WORK/build.sh" <<'EOF'
set -eu
apk add -q build-base linux-headers pkgconf \
    curl-dev curl-static openssl-libs-static nghttp2-static zlib-static \
    brotli-static zstd-static libpsl-static libidn2-static libunistring-static
cd /src
make -s src/core/default_config.cpp   # the embedded config is generated from examples/
# The product rule's own source list, so this build cannot drift from the Makefile's.
srcs=$(make -n -B BUILD=/tmp/plan /tmp/plan/ariadne | tail -1 | tr ' ' '\n' | grep '\.cpp$')
[ -n "$srcs" ] || { echo "no sources from the Makefile" >&2; exit 1; }
mkdir -p /tmp/o
echo "$srcs" | xargs -P "$(nproc)" -n 1 sh -c \
    'g++ -std=c++20 -Wall -Wextra -Werror -O2 -pipe -Iinclude -fopenmp -pthread -c "$0" -o "/tmp/o/$(echo "$0" | tr / _).o"'
g++ -static -fopenmp -pthread /tmp/o/*.o $(pkg-config --static --libs libcurl) -o /out/ariadne
strip /out/ariadne
/out/ariadne --version
EOF

log "compiling in alpine:$ALPINE_TAG (arm64)"
podman run --rm --arch arm64 \
    -v "$WORK/src:/src:Z" -v "$WORK/out:/out:Z" -v "$WORK/build.sh:/build.sh:ro,Z" \
    "docker.io/library/alpine:$ALPINE_TAG" sh /build.sh

file "$WORK/out/ariadne" | grep -q 'ARM aarch64.*static' || die "not a static aarch64 binary: $(file -b "$WORK/out/ariadne")"

mkdir -p "$WORK/pkg/opt/ariadne/skills" "$OUT_DIR"
install -m 0755 "$WORK/out/ariadne" "$WORK/pkg/opt/ariadne/ariadne"
install -m 0644 "$WORK/src/skills/highlight.yaml" "$WORK/pkg/opt/ariadne/skills/highlight.yaml"
printf '%s\n' "$rev" > "$WORK/pkg/opt/ariadne/REVISION"
tar --owner=0 --group=0 -C "$WORK/pkg" -cf "$OUT_DIR/ariadne-podroid.tar" opt
log "wrote $OUT_DIR/ariadne-podroid.tar ($(du -h "$OUT_DIR/ariadne-podroid.tar" | cut -f1), daedalus-cpp $rev)"
