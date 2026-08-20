#!/bin/sh
# 43.sh — carry the agent across the Deadalus -> Daedalus rename.
#
# The rootfs is ONE overlay: lowerdir=/mnt/lower (the squashfs, swapped
# wholesale on update), upperdir=/mnt/persist/upper (survives forever). So
# swapping in a squashfs that ships /opt/daedalus does NOT remove the
# /opt/deadalus that runtime writes left in the upper -- both directories end
# up visible, and the one holding the agent's state is the obsolete one.
#
# What is actually at stake is the API key. /opt/daedalus/start.sh does
#
#     KEY_FILE="$DAEDALUS_HOME/api_server.key"
#     [ -f "$KEY_FILE" ] || generate a new one
#
# and DAEDALUS_HOME now points at /opt/daedalus/daedalus-agent-data. On a
# renamed rootfs that path is empty, so the agent would mint a FRESH key --
# silently invalidating the one already pasted into the Mazemaker app, with
# the only symptom being 401s from a pod that looks perfectly healthy.
# Moving the old data dir keeps the key, the sessions and the agent's state.
#
# Idempotent, as podroid-migrate requires: every step is guarded, so a crash
# mid-migration re-runs safely and a second pass is a no-op.
set -eu

OLD_HOME=/opt/deadalus/deadalus-agent-data
NEW_HOME=/opt/daedalus/daedalus-agent-data

# 1. Move the agent's state, but never over the top of a populated new home.
#    A fresh install seeds the marker instead of running migrations, so if
#    NEW_HOME already has content the pod has been running under the new name
#    and its state wins.
if [ -d "$OLD_HOME" ]; then
    mkdir -p /opt/daedalus
    if [ ! -d "$NEW_HOME" ] || [ -z "$(ls -A "$NEW_HOME" 2>/dev/null)" ]; then
        rm -rf "$NEW_HOME"
        mv "$OLD_HOME" "$NEW_HOME"
        echo "43.sh: moved agent data $OLD_HOME -> $NEW_HOME (api_server.key preserved)"
    else
        echo "43.sh: $NEW_HOME already populated — leaving it, old data stays at $OLD_HOME"
    fi
fi

# 2. Retire stale runlevel symlinks. These normally live only in the squashfs
#    and vanish with it, but any `rc-update add` ever run inside the guest
#    copied them into the upper, where they now dangle at an init script that
#    no longer exists. A dangling service makes OpenRC error every boot and
#    stalls podroid-ready's `after *` on a phantom -- the exact failure
#    build-rootfs.sh guards against when it wires these links.
for svc in podroid-deadalus podroid-deadalus-mcp; do
    for lvl in default boot; do
        if [ -L "/etc/runlevels/$lvl/$svc" ] || [ -e "/etc/runlevels/$lvl/$svc" ]; then
            rm -f "/etc/runlevels/$lvl/$svc"
            echo "43.sh: removed stale runlevel link $lvl/$svc"
        fi
    done
    [ -e "/etc/init.d/$svc" ] && rm -f "/etc/init.d/$svc" && echo "43.sh: removed stale init script $svc"
done

# 3. Drop the old tree once its data has been rescued. Guarded on NEW_HOME
#    existing so a failure in step 1 cannot take the only copy with it.
if [ -d /opt/deadalus ] && [ -d "$NEW_HOME" ]; then
    rm -rf /opt/deadalus
    echo "43.sh: removed /opt/deadalus"
fi

# 4. The profile fragment is a plain file in the squashfs; the old one only
#    lingers if it was edited in-guest. Same reasoning as the init scripts.
[ -e /etc/profile.d/podroid-deadalus.sh ] && rm -f /etc/profile.d/podroid-deadalus.sh \
    && echo "43.sh: removed stale /etc/profile.d/podroid-deadalus.sh"

exit 0
