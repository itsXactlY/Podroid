#!/bin/sh
# iris-pod-start.sh — invoked by the OpenRC iris-pod service inside the
# Podroid Alpine VM. Pulls the iris-messenger arm64 image (or loads it
# from a vendor tarball) and runs it as a rootless podman container with
# /var/lib/iris bind-mounted for persistent state and /etc/iris/runtime.env
# for config.
#
# Exit codes:
#   0  container started
#   1  image load failed
#   2  container start failed (will be retried by OpenRC)

set -eu

IRIS_IMAGE="${IRIS_IMAGE:-localhost/iris-messenger:arm64}"
IRIS_CONTAINER_NAME="${IRIS_CONTAINER_NAME:-iris-messenger}"
IRIS_DATA_DIR="${IRIS_DATA_DIR:-/var/lib/iris}"
IRIS_CONFIG="${IRIS_CONFIG:-/etc/iris/runtime.env}"

# podman flags. NOTE: do NOT add --userns=keep-id here. We run as root inside
# the Podroid guest, so keep-id forces an unnecessary user namespace, and crun
# then fails to mount sysfs into the container:
#   crun: mount `sysfs` to `sys`: Operation not permitted: OCI permission denied
# leaving the container stuck in `Created`. Plain root + --network=host lets
# crun bind-mount the host /sys and the pod starts cleanly.
PODMAN_FLAGS="\
    --name=${IRIS_CONTAINER_NAME} \
    --detach \
    --restart=unless-stopped \
    --network=host \
    -e LC_ALL=C.UTF-8 \
    -e LANG=C.UTF-8 \
    -e PYTHONIOENCODING=utf-8 \
    -e IRIS_DATA_DIR=/var/lib/iris \
    -e IRIS_TLS_ENABLED=false \
    -e IRIS_RELAY_TRANSPORT=dht \
"
# IRIS_RELAY_TRANSPORT=dht: the Podroid pod's reason for existing is
# torrent-style cross-NAT federation, so it runs the Kademlia DHT-direct
# transport (not the same-LAN DLM store-and-forward). Peers are paired in-app
# via handshake URL (Peers panel → persisted federation_peers.json) and the
# DHT bootstrap node + optional circuit-relay URL come from runtime.env
# (IRIS_PEER_GATEWAYS_BOOTSTRAP / IRIS_FEDERATION_RELAY_URL), so NO peer
# address is ever baked into the image (honors the no-hardcoded-IP rule).
# IRIS_TLS_ENABLED=false: the Android client (IrisGateway.kt) speaks PLAIN
# http://127.0.0.1:9091 over the Podroid port-forward. config.yaml bakes
# tls.enabled=true + mTLS (require_client_cert), which would serve HTTPS and
# reject the plain client. The env override (gateway_tls precedence:
# IRIS_TLS_ENABLED > config > auto) forces plain HTTP, deterministically.
# NB: the container's IRIS_DATA_DIR is the literal mount TARGET (/var/lib/iris),
# NOT the host ${IRIS_DATA_DIR}. The bind below maps host ${IRIS_DATA_DIR} ->
# container /var/lib/iris, so inside the container the persisted gateway
# keypair + ratchet state always live at /var/lib/iris regardless of where the
# host keeps them. Passing it explicitly (rather than leaning on the gateway's
# compiled default) makes persistence robust to any future default change.

# Persisted state.
PODMAN_FLAGS="${PODMAN_FLAGS} -v ${IRIS_DATA_DIR}:/var/lib/iris:Z"

# Config (bind-mounted so the operator can edit /etc/iris/runtime.env
# without rebuilding the container).
if [ -f "${IRIS_CONFIG}" ]; then
    PODMAN_FLAGS="${PODMAN_FLAGS} -v ${IRIS_CONFIG}:/etc/iris/runtime.env:ro,Z"
    # --env-file passes the KEY=VALUE lines INTO the container process. Without
    # this, sourcing runtime.env below only sets vars in THIS start script's
    # shell (used for the storage-fstype guard), and they never reach the
    # gateway — so federation knobs (IRIS_PEER_GATEWAYS_BOOTSTRAP,
    # IRIS_FEDERATION_RELAY_URL, IRIS_FEDERATION_SCHEME, …) set by the operator
    # in /etc/iris/runtime.env had no effect. With --env-file they do, so the
    # pod is runtime-configurable for federation without an image rebuild.
    PODMAN_FLAGS="${PODMAN_FLAGS} --env-file ${IRIS_CONFIG}"
    set -a
    . "${IRIS_CONFIG}"
    set +a
fi

# Volatile-storage guard. The gateway persists its federation keypair (stable
# identity) + ratchet state under the data dir. If that dir sits on tmpfs/ramfs
# it silently evaporates on reboot — the pod would mint a NEW identity every
# boot. Podroid keeps /var/lib on the ext4 persistent overlay, so this should
# never fire; it's a loud tripwire if a future change moves it onto tmpfs.
_iris_fstype=$(stat -f -c %T "${IRIS_DATA_DIR}" 2>/dev/null || echo unknown)
case "${_iris_fstype}" in
    tmpfs|ramfs)
        echo "iris-pod: WARNING — IRIS_DATA_DIR=${IRIS_DATA_DIR} is on ${_iris_fstype};" \
             "gateway identity + ratchet state will NOT survive a reboot." >&2
        ;;
esac

# Remove any stale container of the same name first. After a VM reboot (or a
# crash) a previously-created `iris-messenger` container persists on the ext4
# overlay, and `podman run --name iris-messenger` would then hard-fail with
# "container name is already in use", leaving the pod down. Idempotent cleanup
# makes boot self-healing.
podman rm -f "${IRIS_CONTAINER_NAME}" 2>/dev/null || true

# Build the final command and exec into podman run.
# We use --network=host so the WS port 9092 is reachable from the
# guest's eth0 directly; podroid-forward on the host then forwards
# 9091/9092 to 127.0.0.1 on the Android side.
exec podman run ${PODMAN_FLAGS} "${IRIS_IMAGE}"
