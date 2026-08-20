#!/usr/bin/env bash
# ============================================================================
# build-deadalus-venv.sh — native aarch64 musl venv of Deadalus for the pod
# ============================================================================
# Produces the vendor tarball that build-rootfs.sh seeds into /opt/deadalus
# inside the Podroid Alpine guest. Deadalus replaces Hermes as the agent that
# runs in the pod; the surface it exposes is identical (`gateway run`, the
# API_SERVER_* env contract, :8088), so nothing above it had to change.
#
# Output:
#   files/usr/local/share/deadalus/deadalus-podroid.tar
#       opt/deadalus/venv/...            the musl aarch64 venv
#       opt/deadalus/start.sh            launcher, execs `deadalus gateway run`
#       opt/deadalus/config.template.yaml
#
# Paths are stored relative to /, matching hermes-podroid.tar, so build-rootfs
# extracts it straight into $ROOTFS.
#
# PYTHON 3.14, AND WHY IT COMES FROM edge
#   Deadalus is requires-python >=3.14. Alpine 3.23 main ships python3 3.12.14,
#   so the interpreter is pinned from edge/main (3.14.7). Verified: it installs
#   onto a 3.23 base without moving musl (stays 1.2.5-r23), i.e. no libc mix.
#   build-rootfs.sh must install the same python3@edge, because a venv links
#   against the system interpreter — a 3.12-only rootfs would leave this venv
#   unrunnable.
#
# NO COMPILER IS NEEDED. Every base dependency has a musl/aarch64 wheel
# (pydantic-core and cryptography included), verified on 2026-08-20. If that
# ever stops being true the build fails loudly here rather than silently
# shipping a broken pod, so do not paper over it with a toolchain install
# without checking what actually changed.
#
# Usage:
#   ./build-deadalus-venv.sh                     # uses DEADALUS_SRC or clones
#   DEADALUS_SRC=/path/to/deadalus ./build-deadalus-venv.sh
#
# Runs under qemu-aarch64 on an x86_64 host, or natively on arm64.
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$HERE/files/usr/local/share/deadalus}"
DEADALUS_SRC="${DEADALUS_SRC:-}"
DEADALUS_REPO="${DEADALUS_REPO:-https://github.com/itsXactlY/deadalus.git}"
DEADALUS_REF="${DEADALUS_REF:-main}"
ALPINE_TAG="${ALPINE_TAG:-3.23}"

log()  { printf '\033[1;36m[deadalus-venv]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[FATAL]\033[0m %s\n' "$*" >&2; exit 1; }

command -v podman >/dev/null 2>&1 || die "podman not found"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---- resolve source ---------------------------------------------------------
# Always export via `git archive`: it takes tracked files only, so an agent
# HOME that doubles as a checkout (~/.deadalus keeps auth.json, sessions/ and
# .env there) cannot leak credentials into a tarball that ships to devices.
SRC_EXPORT="$WORK/src"
mkdir -p "$SRC_EXPORT"
if [[ -n "$DEADALUS_SRC" && -d "$DEADALUS_SRC/.git" ]]; then
    log "exporting tracked files from $DEADALUS_SRC"
    git -C "$DEADALUS_SRC" archive --format=tar HEAD | tar -x -C "$SRC_EXPORT"
else
    log "cloning $DEADALUS_REPO ($DEADALUS_REF)"
    git clone --depth 1 -b "$DEADALUS_REF" "$DEADALUS_REPO" "$WORK/clone" >/dev/null 2>&1 \
        || die "clone failed"
    git -C "$WORK/clone" archive --format=tar HEAD | tar -x -C "$SRC_EXPORT"
fi
[[ -f "$SRC_EXPORT/pyproject.toml" ]] || die "no pyproject.toml in exported source"

for leak in auth.json .env .anthropic_oauth.json; do
    [[ -e "$SRC_EXPORT/$leak" ]] && die "refusing to build: $leak present in export"
done

mkdir -p "$OUT_DIR" "$WORK/out"

log "building musl aarch64 venv (alpine $ALPINE_TAG + python3.14@edge)…"
podman run --rm --platform linux/arm64 \
    -v "$SRC_EXPORT":/src:ro \
    -v "$WORK/out":/out:Z \
    "docker.io/library/alpine:$ALPINE_TAG" sh -c '
set -e
printf "@edge https://dl-cdn.alpinelinux.org/alpine/edge/main\n" >> /etc/apk/repositories
apk update -q
apk add --no-cache python3@edge >/dev/null

PY=$(python3 --version)
case "$PY" in
    *3.14*) ;;
    *) echo "FATAL: expected python 3.14 from edge, got $PY" >&2; exit 1 ;;
esac
echo "[guest] $(uname -m)  $PY"

# setuptools writes *.egg-info next to pyproject.toml, so the source must be
# writable — a :ro mount fails here for a reason unrelated to musl or arm64.
cp -a /src /build

python3 -m venv /opt/deadalus/venv
/opt/deadalus/venv/bin/python -m pip install -q --upgrade pip >/dev/null 2>&1
/opt/deadalus/venv/bin/pip install --no-cache-dir /build

test -x /opt/deadalus/venv/bin/deadalus || { echo "FATAL: no deadalus entrypoint" >&2; exit 1; }
/opt/deadalus/venv/bin/deadalus gateway --help >/dev/null 2>&1 \
    || { echo "FATAL: deadalus gateway --help failed" >&2; exit 1; }

mkdir -p /opt/deadalus/deadalus-agent-data
tar -cf /out/deadalus-podroid.tar -C / opt/deadalus
' || die "venv build failed"

[[ -f "$WORK/out/deadalus-podroid.tar" ]] || die "tarball not produced"

# start.sh + config template are appended from the rootfs overlay so they stay
# editable in git rather than baked inside the tarball.
mv "$WORK/out/deadalus-podroid.tar" "$OUT_DIR/deadalus-podroid.tar"
log "wrote $OUT_DIR/deadalus-podroid.tar ($(du -h "$OUT_DIR/deadalus-podroid.tar" | cut -f1))"
log "entries: $(tar -tf "$OUT_DIR/deadalus-podroid.tar" | wc -l)"
