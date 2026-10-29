#!/bin/sh
# mcp-mazemaker-watch.sh — wire the guest's Ariadne to the operator's setup over
# the app's on-device proxy, with NO hardcoded IP (URL derived from the live
# default gateway = the TAP host).
#
# The Mazemaker app runs a proxy on the guest's default gateway, port 8790, only
# while it is open. Two things come through it:
#
#   MCP_URL             the proxy's /mcp endpoint, so Ariadne reaches the desktop
#                       memory (mcp_servers.mazemaker).
#   MODEL_*             the proxy's /agent-config (config push), so the operator
#                       does not type an LLM endpoint into a phone keyboard. The
#                       SHAPE comes from the paired host; the KEY comes from the
#                       app, because the desktop bridge redacts secrets by design.
#
# Both land in $ARIADNE_HOME/pod.env; configure.sh turns that into config.yaml.
# Ariadne connects its MCP servers when it starts, so it is restarted when
# pod.env changes and when the proxy appears after it booted -- but never in the
# middle of a turn: the restart waits for /health/detailed to say busy:false.
#
# No Python: the pod's agent is a single static binary, and this script is
# busybox sh + curl + jq.

ARIADNE_HOME="${ARIADNE_HOME:-/opt/ariadne/home}"
POD_ENV="$ARIADNE_HOME/pod.env"
KEY_FILE="$ARIADNE_HOME/api_server.key"
PORT=8790
POLL=15
SETTLE=45

default_gw() { ip route 2>/dev/null | awk '/^default/{print $3; exit}'; }

# Single-quoted for sh: sourcing pod.env assigns strings and runs nothing, even
# when a pushed value carries $( ) or quotes.
sq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

load() {
    MODEL_BASE_URL=""; MODEL_NAME=""; MODEL_API_KEY=""; MCP_URL=""
    [ -f "$POD_ENV" ] && . "$POD_ENV"
}

save() {
    mkdir -p "$ARIADNE_HOME"
    (
        umask 077
        {
            echo "MODEL_BASE_URL=$(sq "$MODEL_BASE_URL")"
            echo "MODEL_NAME=$(sq "$MODEL_NAME")"
            echo "MODEL_API_KEY=$(sq "$MODEL_API_KEY")"
            echo "MCP_URL=$(sq "$MCP_URL")"
        } > "$POD_ENV.tmp"
    ) && mv "$POD_ENV.tmp" "$POD_ENV"
}

proxy_reachable() {
    gw="$1"; [ -n "$gw" ] || return 1
    curl -sf -m 5 -o /dev/null -X POST "http://$gw:$PORT/mcp" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' 2>/dev/null
}

# Merge the pushed model into MODEL_*. The push is the Hermes/Daedalus shape:
# model.provider names an entry in providers, which carries base_url, api_key
# and default_model; model.default is the model id. A flat model.base_url /
# model.name / model.api_key is read too. Deliberately conservative: an empty
# field never overwrites a held one -- a phone with no key yet must not wipe a
# working one. Returns 0 when something changed.
pull_agent_config() {
    gw="$1"; [ -n "$gw" ] || return 1
    body=$(curl -sf -m 8 "http://$gw:$PORT/agent-config" 2>/dev/null) || return 1
    [ "$(printf '%s' "$body" | jq -r '.ok // false' 2>/dev/null)" = "true" ] || return 1
    sel='(.model // {}) as $m | ((.providers // {})[$m.provider // ""] // {}) as $p'
    base=$(printf '%s' "$body" | jq -r "$sel | \$p.base_url // \$m.base_url // \"\"")
    name=$(printf '%s' "$body" | jq -r "$sel | \$m.default // \$p.default_model // \$m.name // \"\"")
    key=$(printf '%s' "$body" | jq -r "$sel | \$p.api_key // \$m.api_key // \"\"")
    changed=1
    if [ -n "$base" ] && [ "$base" != "$MODEL_BASE_URL" ]; then MODEL_BASE_URL="$base"; changed=0; fi
    if [ -n "$name" ] && [ "$name" != "$MODEL_NAME" ]; then MODEL_NAME="$name"; changed=0; fi
    if [ -n "$key" ] && [ "$key" != "$MODEL_API_KEY" ]; then MODEL_API_KEY="$key"; changed=0; fi
    return $changed
}

busy() {
    [ -s "$KEY_FILE" ] || return 1
    [ "$(curl -s -m 3 -H "Authorization: Bearer $(cat "$KEY_FILE")" \
        http://127.0.0.1:8088/health/detailed 2>/dev/null | jq -r '.busy // false' 2>/dev/null)" = "true" ]
}

restart_ariadne() {
    why="$1"
    rc-service podroid-ariadne status >/dev/null 2>&1 || return 0   # not running: it reads the new config when it starts
    waited=0
    while busy && [ "$waited" -lt 600 ]; do sleep 5; waited=$((waited + 5)); done
    echo "[mcp-watch] $why — restarting podroid-ariadne"
    rc-service podroid-ariadne restart >/dev/null 2>&1 || true
    sleep "$SETTLE"
}

load
was_up=0
while :; do
    gw="$(default_gw)"
    changed=0
    if [ -n "$gw" ] && [ "http://$gw:$PORT/mcp" != "$MCP_URL" ]; then
        MCP_URL="http://$gw:$PORT/mcp"
        changed=1
    fi
    came_up=0
    if proxy_reachable "$gw"; then
        pull_agent_config "$gw" && changed=1
        [ "$was_up" = 0 ] && came_up=1
        was_up=1
    else
        was_up=0
    fi
    if [ "$changed" = 1 ]; then
        save
        /opt/ariadne/configure.sh
        restart_ariadne "pod.env changed (proxy at ${gw:-?}:$PORT)"
    elif [ "$came_up" = 1 ]; then
        restart_ariadne "proxy up at $gw:$PORT after ariadne started"
    fi
    sleep "$POLL"
done
