#!/bin/sh
set -eu
ROOTFS=/work/rootfs

# ALPINE_VERSION comes from the Dockerfile ENV (full release like 3.23.4).
# Strip the patch component to get the major branch (e.g. 3.23) used in repo URLs.
: "${ALPINE_VERSION:?ALPINE_VERSION must be set (e.g. 3.23.4)}"
ALPINE_BRANCH="${ALPINE_VERSION%.*}"

mkdir -p "$ROOTFS/etc/apk"
# The edge entry is TAGGED (@edge), not a plain repo line: apk only pulls from
# it for packages asked for as pkg@edge. Everything else stays on v$ALPINE_BRANCH,
# so this does not turn the guest into a rolling release. It exists for one
# package — python3 3.14, which Deadalus requires and 3.23 main does not carry
# (it ships 3.12). Verified: python3@edge installs without moving musl off
# 1.2.5-r23, so there is no libc mix.
cat > "$ROOTFS/etc/apk/repositories" <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community
@edge https://dl-cdn.alpinelinux.org/alpine/edge/main
EOF

apk -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main" \
    -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community" \
    -X "@edge https://dl-cdn.alpinelinux.org/alpine/edge/main" \
    -U --allow-untrusted --root "$ROOTFS" --initdb add \
    alpine-base \
    openrc \
    busybox-openrc \
    bash \
    podman \
    docker docker-openrc docker-cli-compose \
    lxc lxc-templates lxc-download lxc-openrc lxc-bridge \
    crun \
    fuse-overlayfs \
    iptables \
    ip6tables \
    nftables \
    bridge-utils \
    iproute2 \
    dropbear dropbear-openrc \
    curl \
    ca-certificates \
    shadow shadow-uidmap \
    slirp4netns \
    aardvark-dns netavark \
    libcap-utils \
    doas sudo \
    gcompat \
    python3@edge \
    gzip \
    xz \
    tigervnc \
    pulseaudio \
    pulseaudio-utils \
    font-misc-misc \
    font-cursor-misc \
    ttf-dejavu

# User-mode x86_64 emulation: lets rootless podman run amd64 container
# images (the iris-messenger .bin is built x86_64-only on the host).
# binfmt_misc registration happens in podroid-bootstrap at VM boot.
apk -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community" \
    -U --allow-untrusted --root "$ROOTFS" add qemu-x86_64

# Apply file capabilities to newuidmap/newgidmap. apk's package install often
# does this, but we set them explicitly so the squashfs ships with the
# correct security.capability xattr (preserved by mksquashfs without -no-xattrs).
if command -v setcap >/dev/null 2>&1; then
    setcap cap_setuid+ep "$ROOTFS/usr/bin/newuidmap" 2>/dev/null || true
    setcap cap_setgid+ep "$ROOTFS/usr/bin/newgidmap" 2>/dev/null || true
fi

# Ensure doas and sudo are setuid-root. apk usually does this, but on
# overlay-mounted build hosts it can silently fail.
chmod u+s "$ROOTFS/usr/bin/doas"  2>/dev/null || true
chmod u+s "$ROOTFS/usr/bin/sudo"  2>/dev/null || true

# doas: members of the `wheel` group can become root after entering their
# password (cached for ~5 min). Standard *BSD/Alpine convention.
mkdir -p "$ROOTFS/etc/doas.d"
echo "permit persist :wheel" > "$ROOTFS/etc/doas.d/doas.conf"
chmod 0400 "$ROOTFS/etc/doas.d/doas.conf"

# sudo: equivalent rule for users who prefer sudo over doas.
mkdir -p "$ROOTFS/etc/sudoers.d"
echo "%wheel ALL=(ALL) ALL" > "$ROOTFS/etc/sudoers.d/wheel"
chmod 0440 "$ROOTFS/etc/sudoers.d/wheel"

# Set root password to "podroid" (pre-hashed with openssl).
# We can't run chpasswd inside the aarch64 rootfs from an x86_64 host,
# so write the SHA-512 hash directly into /etc/shadow.
# No fixed -salt: openssl generates a random salt so the stored hash differs
# per build (the password stays the documented default "podroid").
ROOT_HASH=$(openssl passwd -6 podroid)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"

# Strip docs/man/locale to shrink squashfs
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# Remove the stock pulseaudio OpenRC service. Podroid starts pulseaudio
# directly from podroid-x11 (start-stop-daemon), never as a service; left in
# place its depend() pulls in a non-existent "udev" service, so OpenRC logs
# "Service 'pulseaudio' needs non existent service 'udev'" on every boot.
rm -f "$ROOTFS/etc/init.d/pulseaudio"

# Pre-create podman storage dirs (saves first-boot mkdir)
mkdir -p "$ROOTFS/var/lib/containers/storage" \
         "$ROOTFS/run/containers/storage" \
         "$ROOTFS/run/libpod" \
         "$ROOTFS/run/crun"

# Copy custom service files into the rootfs
cp /work/files/etc/init.d/podroid-bootstrap "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-network   "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-resize    "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-ready     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-x11       "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-vsock     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-hostd     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podroid-migrate   "$ROOTFS/etc/init.d/"
chmod +x "$ROOTFS/etc/init.d/podroid-"*

# deadalus pod (B): OpenRC init that runs the native Python Deadalus gateway
# inside the Podroid VM on guest :8088. Deadalus replaced Hermes as the in-pod
# agent; it exposes the same surface (`gateway run`, the API_SERVER_* env
# contract, :8088), so PodroidService's port forward is unchanged. Seeded venv
# lands at /opt/deadalus via the vendor tarball block below; this just installs
# the script.
if [ -f /work/files/etc/init.d/podroid-deadalus ]; then
    cp /work/files/etc/init.d/podroid-deadalus "$ROOTFS/etc/init.d/"
    chmod +x "$ROOTFS/etc/init.d/podroid-deadalus"
fi

# podroid-deadalus-mcp: wires the guest agent to mazemaker via the app MCP proxy
if [ -f /work/files/etc/init.d/podroid-deadalus-mcp ]; then
    cp /work/files/etc/init.d/podroid-deadalus-mcp "$ROOTFS/etc/init.d/"
    chmod +x "$ROOTFS/etc/init.d/podroid-deadalus-mcp"
fi

# iris-pod: the OpenRC init that runs the iris-messenger container inside
# the Podroid VM. Source lives in /home/alca/projects/jrwl-messenger/
# android/vm-image/overlay/etc/init.d/iris-pod (same overlay that builds
# the iris-messenger-arm64-overlay.tar). Staged here so it ends up in
# the squashfs at /etc/init.d/iris-pod.
if [ -f /work/files/etc/init.d/iris-pod ]; then
    cp /work/files/etc/init.d/iris-pod "$ROOTFS/etc/init.d/"
    chmod +x "$ROOTFS/etc/init.d/iris-pod"
fi
if [ -f /work/files/usr/local/bin/iris-pod-start.sh ]; then
    mkdir -p "$ROOTFS/usr/local/bin"
    cp /work/files/usr/local/bin/iris-pod-start.sh "$ROOTFS/usr/local/bin/"
    chmod +x "$ROOTFS/usr/local/bin/iris-pod-start.sh"
fi
if [ -d /work/files/etc/iris ]; then
    mkdir -p "$ROOTFS/etc/iris"
    cp -a /work/files/etc/iris/. "$ROOTFS/etc/iris/"
fi
# Vendor tarball of the iris-messenger container image (the obfuscated .bin
# wrapped in a minimal scratch container). iris-pod's start_pre() loads this
# via `podman load -i` if `localhost/iris-messenger:amd64` is not already
# present. Sourced from /home/alca/projects/jrwl-messenger/build-podman-image.sh
# on the host, then mirrored into the Podroid APK assets at
# app/src/main/assets/iris-messenger/iris-messenger-amd64.tar.
if [ -f /work/files/usr/local/share/iris/iris-messenger-arm64.tar ]; then
    mkdir -p "$ROOTFS/usr/local/share/iris"
    cp /work/files/usr/local/share/iris/iris-messenger-arm64.tar \
       "$ROOTFS/usr/local/share/iris/iris-messenger-arm64.tar"
    chmod 0644 "$ROOTFS/usr/local/share/iris/iris-messenger-arm64.tar"
fi
if [ -f /work/files/usr/local/share/iris/iris-messenger-amd64.tar ]; then
    mkdir -p "$ROOTFS/usr/local/share/iris"
    cp /work/files/usr/local/share/iris/iris-messenger-amd64.tar \
       "$ROOTFS/usr/local/share/iris/iris-messenger-amd64.tar"
    chmod 0644 "$ROOTFS/usr/local/share/iris/iris-messenger-amd64.tar"
    einfo() { :; }  # noop outside OpenRC runlevel context
    einfo "Seeded /usr/local/share/iris/iris-messenger-amd64.tar (vendor tarball)"
fi
if [ -f /work/files/usr/local/share/iris/iris-messenger.bin ]; then
    cp /work/files/usr/local/share/iris/iris-messenger.bin \
       "$ROOTFS/usr/local/share/iris/iris-messenger.bin" 2>/dev/null || true
    chmod 0644 "$ROOTFS/usr/local/share/iris/iris-messenger.bin" 2>/dev/null || true
fi

# deadalus pod (B): seed the native Python venv at /opt/deadalus from a vendor
# tarball produced by build-deadalus-venv.sh (run on the host; builds a musl
# aarch64 venv under qemu or natively on arm64). The tarball stores paths
# relative to / (opt/deadalus/...), so `tar -C $ROOTFS -xf` drops it straight
# into place. Mirrors the iris-pod vendor-tarball pattern. If absent,
# podroid-deadalus still installs but start_pre() refuses to start until the
# venv is seeded.
#
# The venv links against the SYSTEM python, and Deadalus is requires-python
# >=3.14 while Alpine 3.23 main ships 3.12 — hence the python3@edge pin in the
# apk block above. Dropping that pin leaves this venv present but unrunnable.
if [ -f /work/files/usr/local/share/deadalus/deadalus-podroid.tar ]; then
    mkdir -p "$ROOTFS/opt/deadalus"
    tar -xf /work/files/usr/local/share/deadalus/deadalus-podroid.tar -C "$ROOTFS"
    chmod +x "$ROOTFS/opt/deadalus/start.sh" 2>/dev/null || true
    # override the tarball start.sh with the repo copy (per-install key gen,
    # no baked secret) + seed the LLM config template (empty api_key).
    if [ -f /work/files/opt/deadalus/start.sh ]; then
        cp /work/files/opt/deadalus/start.sh "$ROOTFS/opt/deadalus/start.sh"
        chmod +x "$ROOTFS/opt/deadalus/start.sh"
    fi
    if [ -f /work/files/opt/deadalus/config.template.yaml ]; then
        cp /work/files/opt/deadalus/config.template.yaml "$ROOTFS/opt/deadalus/config.template.yaml"
    fi
    # seed the mazemaker MCP watcher alongside the venv (added on top of the tarball)
    if [ -f /work/files/opt/deadalus/mcp-mazemaker-watch.sh ]; then
        cp /work/files/opt/deadalus/mcp-mazemaker-watch.sh "$ROOTFS/opt/deadalus/"
        chmod +x "$ROOTFS/opt/deadalus/mcp-mazemaker-watch.sh"
    fi
    # Put the CLI on the guest's PATH. Without this, opening the Podroid
    # terminal and typing `deadalus` just says "not found" — the entrypoint
    # only exists at /opt/deadalus/venv/bin/. /usr/bin and NOT /usr/local/bin:
    # the guest PATH is /usr/sbin:/usr/bin:/sbin:/bin, so a link in
    # /usr/local/bin is invisible.
    ln -sf /opt/deadalus/venv/bin/deadalus       "$ROOTFS/usr/bin/deadalus"
    ln -sf /opt/deadalus/venv/bin/deadalus-agent "$ROOTFS/usr/bin/deadalus-agent"
fi

# Copy /usr/local/bin scripts (resize daemon + login wrapper + getty selector)
mkdir -p "$ROOTFS/usr/local/bin"
cp /work/files/usr/local/bin/podroid-resize "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podroid-login  "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podroid-getty  "$ROOTFS/usr/local/bin/"
# podroid-vsock-agent is COPY'd in from the vsock-builder Docker stage. Make
# sure it's executable (cross-arch COPY can lose the mode bit on some buildkit
# versions).
chmod +x "$ROOTFS/usr/local/bin/podroid-vsock-agent" 2>/dev/null || true
# podroid-hostd is also COPY'd from the vsock-builder stage; same mode-bit guard.
# The CLIs are argv[0]-dispatch symlinks onto the one multi-call binary.
chmod +x "$ROOTFS/usr/local/bin/podroid-hostd" 2>/dev/null || true
# podroid-overlay-normalize is COPY'd from the vsock-builder stage; mode-bit guard.
chmod +x "$ROOTFS/usr/local/bin/podroid-overlay-normalize" 2>/dev/null || true
ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-notify"
ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-forward"
ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-open"
ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-power"
ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-headless"
ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-server"
chmod +x "$ROOTFS/usr/local/bin/podroid-"*
mkdir -p "$ROOTFS/etc/conf.d"
cp /work/files/etc/conf.d/podroid "$ROOTFS/etc/conf.d/"
# vsock agent's initial forward table (read at podroid-vsock startup).
mkdir -p "$ROOTFS/etc/podroid"
cp /work/files/etc/podroid/forwards.conf "$ROOTFS/etc/podroid/forwards.conf"
chmod 0644 "$ROOTFS/etc/podroid/forwards.conf"
# Migration scripts dir (seeded with its README; per-version <v>.sh added over time).
mkdir -p "$ROOTFS/etc/podroid/migrations"
cp /work/files/etc/podroid/migrations/README "$ROOTFS/etc/podroid/migrations/README"
# System-version stamp: the migration anchor. Baked from the app versionCode at
# build time; compared against /mnt/persist/.podroid/applied-version at boot.
printf '%s\n' "${SYSTEM_VERSION:-0}" > "$ROOTFS/etc/podroid/system-version"
chmod 0644 "$ROOTFS/etc/podroid/system-version"
cp /work/files/etc/inittab "$ROOTFS/etc/inittab"
cp /work/files/etc/rc.conf "$ROOTFS/etc/rc.conf"

# /etc/profile.d/*.sh — sourced by Alpine's /etc/profile in login shells.
# podroid-color.sh: COLORTERM=truecolor (24-bit color). podroid-x11.sh:
# DISPLAY / PULSE_SERVER for the in-app GUI viewer. Copy by explicit name so
# a renamed/removed asset fails the build (set -e) instead of silently
# shipping a squashfs without these exports.
mkdir -p "$ROOTFS/etc/profile.d"
cp /work/files/etc/profile.d/podroid-color.sh "$ROOTFS/etc/profile.d/"
cp /work/files/etc/profile.d/podroid-x11.sh   "$ROOTFS/etc/profile.d/"
# podroid-deadalus.sh: DEADALUS_HOME, so an interactive `deadalus setup` writes
# to the same home the service reads. Without it setup lands in /root/.deadalus,
# the gateway keeps reading /opt/deadalus/deadalus-agent-data, and setup looks
# like it never took.
cp /work/files/etc/profile.d/podroid-deadalus.sh "$ROOTFS/etc/profile.d/"
chmod 0644 "$ROOTFS/etc/profile.d/podroid-color.sh" "$ROOTFS/etc/profile.d/podroid-x11.sh"

# /etc/containers/storage.conf — pin Podman to the in-kernel overlay driver.
# Without this file, Podman auto-detects fuse-overlayfs (still apk-installed
# as a fallback) and uses it, which is slower than native overlay.
mkdir -p "$ROOTFS/etc/containers"
cp /work/files/etc/containers/storage.conf "$ROOTFS/etc/containers/storage.conf"
chmod 0644 "$ROOTFS/etc/containers/storage.conf"

# Hostname (read by podroid-bootstrap via `hostname -F /etc/hostname`)
echo "podroid" > "$ROOTFS/etc/hostname"
echo "127.0.0.1 localhost podroid" > "$ROOTFS/etc/hosts"
echo "::1 localhost ip6-localhost" >> "$ROOTFS/etc/hosts"

# Login banner shown by getty before the login prompt.
# \S=Alpine release, \r=kernel, \m=arch, \l=tty
cat > "$ROOTFS/etc/issue" <<'EOF'
Welcome to Podroid (Alpine \S)
Kernel \r on \m (\l)

  Default login:  root  /  podroid
  Change root password:    passwd
  Create a regular user:   adduser -G wheel <name>
                           (wheel group → can run doas/sudo)

EOF

# Set runlevels via direct symlinks (host is x86_64, can't chroot into aarch64 rootfs to run rc-update).
# rc-update is just `ln -s /etc/init.d/X /etc/runlevels/<level>/X` under the hood.
mkdir -p "$ROOTFS/etc/runlevels/default" "$ROOTFS/etc/runlevels/boot"
# Guard each link: a dangling symlink (e.g. dnsmasq.lxcbr0, which lxc-bridge
# may ship only as dnsmasq config and not an init script) makes OpenRC log
# an error every boot and stalls podroid-ready's `after *` on a phantom.
for svc in podroid-migrate podroid-bootstrap podroid-network podroid-resize dropbear docker lxc dnsmasq.lxcbr0 podroid-x11 podroid-vsock podroid-hostd podroid-ready iris-pod podroid-deadalus podroid-deadalus-mcp; do
    if [ -e "$ROOTFS/etc/init.d/$svc" ]; then
        ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/default/$svc"
    else
        echo "WARN: init script /etc/init.d/$svc missing, skipping runlevel symlink"
    fi
done

# Disable services we don't need (initramfs already handles them, or they're noise in the VM)
for svc in hwclock swclock urandom networking sysctl bootmisc syslog; do
    rm -f "$ROOTFS/etc/runlevels/boot/$svc" "$ROOTFS/etc/runlevels/default/$svc"
done
