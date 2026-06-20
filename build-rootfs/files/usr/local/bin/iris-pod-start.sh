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

IRIS_IMAGE="${IRIS_IMAGE:-localhost/iris-messenger:amd64}"
IRIS_CONTAINER_NAME="${IRIS_CONTAINER_NAME:-iris-messenger}"
IRIS_DATA_DIR="${IRIS_DATA_DIR:-/var/lib/iris}"
IRIS_CONFIG="${IRIS_CONFIG:-/etc/iris/runtime.env}"

# podman flags common to rootless containers.
PODMAN_FLAGS="\
    --name=${IRIS_CONTAINER_NAME} \
    --detach \
    --restart=unless-stopped \
    --network=host \
    --userns=keep-id \
    -e LC_ALL=C.UTF-8 \
    -e LANG=C.UTF-8 \
    -e PYTHONIOENCODING=utf-8 \
"

# Persisted state.
PODMAN_FLAGS="${PODMAN_FLAGS} -v ${IRIS_DATA_DIR}:/var/lib/iris:Z"

# Config (bind-mounted so the operator can edit /etc/iris/runtime.env
# without rebuilding the container).
if [ -f "${IRIS_CONFIG}" ]; then
    PODMAN_FLAGS="${PODMAN_FLAGS} -v ${IRIS_CONFIG}:/etc/iris/runtime.env:ro,Z"
    set -a
    . "${IRIS_CONFIG}"
    set +a
fi

# Build the final command and exec into podman run.
# We use --network=host so the WS port 9092 is reachable from the
# guest's eth0 directly; podroid-forward on the host then forwards
# 9091/9092 to 127.0.0.1 on the Android side.
exec podman run ${PODMAN_FLAGS} "${IRIS_IMAGE}"
