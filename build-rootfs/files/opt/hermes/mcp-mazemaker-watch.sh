#!/bin/sh
# mcp-mazemaker-watch.sh — wire the guest's hermes to mazemaker over the app's
# on-device MCP proxy, with NO hardcoded IP (URL derived from the live default
# gateway = the TAP host). Also restarts hermes when the proxy appears, because
# hermes only retries an MCP server 3x at startup then gives up for the session.

HERMES_HOME="${HERMES_HOME:-/opt/hermes/hermes-agent-data}"
VENV_PY=/opt/hermes/venv/bin/python3
CONFIG="$HERMES_HOME/config.yaml"
AGENT_LOG="$HERMES_HOME/logs/agent.log"
PORT=8790
POLL=15
SETTLE=45

default_gw() { ip route 2>/dev/null | awk '/^default/{print $3; exit}'; }

write_config() {
    gw="$1"
    [ -n "$gw" ] || return 1
    [ -x "$VENV_PY" ] || return 1
    MZK_GW="$gw" MZK_PORT="$PORT" MZK_CONFIG="$CONFIG" "$VENV_PY" - <<'PY'
import os, pathlib, yaml
gw=os.environ["MZK_GW"]; port=os.environ["MZK_PORT"]; path=pathlib.Path(os.environ["MZK_CONFIG"])
cfg=yaml.safe_load(path.read_text()) if path.exists() else {}
cfg=cfg or {}
url=f"http://{gw}:{port}/mcp"
srv=cfg.setdefault("mcp_servers",{}); cur=srv.get("mazemaker")
if not isinstance(cur,dict) or cur.get("url")!=url or cur.get("enabled") is not True:
    srv["mazemaker"]={"url":url,"enabled":True}
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(yaml.safe_dump(cfg,sort_keys=False,allow_unicode=True)); print("wrote",url)
PY
}

proxy_reachable() {
    gw="$1"; [ -n "$gw" ] || return 1
    curl -sf -m 5 -o /dev/null -X POST "http://$gw:$PORT/mcp" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' 2>/dev/null
}

hermes_mcp_servers() {
    [ -f "$AGENT_LOG" ] || { echo 0; return; }
    n=$(grep 'MCP: registered' "$AGENT_LOG" 2>/dev/null | tail -1 | sed -n 's/.*from \([0-9][0-9]*\) server.*/\1/p')
    echo "${n:-0}"
}

write_config "$(default_gw)" || true
while :; do
    gw="$(default_gw)"
    write_config "$gw" >/dev/null 2>&1 || true
    if proxy_reachable "$gw"; then
        if [ "$(hermes_mcp_servers)" -lt 1 ] && pgrep -f 'hermes gateway run' >/dev/null 2>&1; then
            echo "[mcp-watch] proxy up at $gw:$PORT, hermes has no mazemaker tools — restarting podroid-hermes"
            rc-service podroid-hermes restart >/dev/null 2>&1 || true
            sleep "$SETTLE"
        fi
    fi
    sleep "$POLL"
done
