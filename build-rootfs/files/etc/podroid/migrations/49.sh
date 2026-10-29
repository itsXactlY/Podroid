#!/bin/sh
# 49.sh — Ariadne replaces the Python Daedalus as the pod's agent.
#
# The squashfs no longer ships /opt/daedalus, podroid-daedalus or
# podroid-daedalus-mcp; it ships /opt/ariadne and podroid-ariadne(-mcp), linked
# into the default runlevel. The upper survives the swap, so the same things
# 43.sh had to carry across the last rename are at stake here:
#
#   * The API key. /opt/ariadne/start.sh mints a fresh api_server.key when
#     /opt/ariadne/home has none, which would silently invalidate the key the
#     operator already pasted into the Mazemaker app -- 401s from a healthy
#     looking pod, nothing else. So the Daedalus key becomes Ariadne's.
#   * Runlevel links and init scripts that an in-guest `rc-update` copied into
#     the upper: they now dangle at services that no longer exist, OpenRC errors
#     on them every boot, and podroid-ready's `after *` stalls on a phantom.
#
# The Daedalus data dir (sessions, its config.yaml) is the operator's and stays
# where it is. The model is not carried over: the app pushes it to
# podroid-ariadne-mcp whenever it is open, key included.
#
# Idempotent, as podroid-migrate requires: every step is guarded.
set -eu

OLD_KEY=/opt/daedalus/daedalus-agent-data/api_server.key
NEW_HOME=/opt/ariadne/home
NEW_KEY=$NEW_HOME/api_server.key

# 1. The key, never over one Ariadne already uses.
if [ -s "$OLD_KEY" ] && [ ! -s "$NEW_KEY" ]; then
    mkdir -p "$NEW_HOME"
    (umask 077; cp "$OLD_KEY" "$NEW_KEY")
    chmod 600 "$NEW_KEY"
    echo "49.sh: carried the app's api_server.key from Daedalus to $NEW_KEY"
fi

# 2. Retire the Daedalus services that live on in the upper.
for svc in podroid-daedalus podroid-daedalus-mcp; do
    for lvl in default boot; do
        if [ -L "/etc/runlevels/$lvl/$svc" ] || [ -e "/etc/runlevels/$lvl/$svc" ]; then
            rm -f "/etc/runlevels/$lvl/$svc"
            echo "49.sh: removed stale runlevel link $lvl/$svc"
        fi
    done
    [ -e "/etc/init.d/$svc" ] && rm -f "/etc/init.d/$svc" && echo "49.sh: removed stale init script $svc"
done

# 3. The same for the profile fragment and the CLI links, which only linger in
#    the upper if they were edited or recreated in-guest.
[ -e /etc/profile.d/podroid-daedalus.sh ] && rm -f /etc/profile.d/podroid-daedalus.sh \
    && echo "49.sh: removed stale /etc/profile.d/podroid-daedalus.sh"
for bin in /usr/bin/daedalus /usr/bin/daedalus-agent; do
    if [ -L "$bin" ] && [ ! -e "$bin" ]; then rm -f "$bin" && echo "49.sh: removed dangling $bin"; fi
done

exit 0
