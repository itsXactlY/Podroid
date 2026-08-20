#!/bin/sh
# mcp-mazemaker-watch.sh — wire the guest's daedalus to the operator's setup over
# the app's on-device proxy, with NO hardcoded IP (URL derived from the live
# default gateway = the TAP host).
#
# Two jobs, one poll loop and one gateway discovery:
#
#   mcp_servers.mazemaker  <- the proxy's /mcp endpoint, so the agent can reach
#                             the desktop memory. Also restarts daedalus when the
#                             proxy appears, because daedalus retries an MCP
#                             server only 3x at startup then gives up.
#   model + providers      <- the proxy's /agent-config endpoint (config push),
#                             so the operator does not type an LLM endpoint into
#                             a phone keyboard. The SHAPE comes from the paired
#                             host; the KEY comes from the app, because the
#                             desktop bridge redacts secrets by design.

DAEDALUS_HOME="${DAEDALUS_HOME:-/opt/daedalus/daedalus-agent-data}"
VENV_PY=/opt/daedalus/venv/bin/python3
CONFIG="$DAEDALUS_HOME/config.yaml"
AGENT_LOG="$DAEDALUS_HOME/logs/agent.log"
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

# Pull the LLM shape from the app and merge it in. Deliberately conservative:
#   * writes only when something actually differs, so the operator's own edits
#     are not rewritten on every 15s tick,
#   * never replaces a non-empty api_key with an empty one — a phone that has
#     no key yet must not wipe a working config,
#   * merges into the existing document instead of replacing it, so unrelated
#     keys (mcp_servers, agent settings) survive.
# Returns 0 when it changed something, 1 otherwise, so the caller can restart.
pull_agent_config() {
    gw="$1"
    [ -n "$gw" ] || return 1
    [ -x "$VENV_PY" ] || return 1
    body=$(curl -sf -m 8 "http://$gw:$PORT/agent-config" 2>/dev/null) || return 1
    [ -n "$body" ] || return 1

    MZK_BODY="$body" MZK_CONFIG="$CONFIG" "$VENV_PY" - <<'PY'
import json, os, pathlib, sys, yaml

body = json.loads(os.environ["MZK_BODY"])
if not body.get("ok"):
    sys.exit(1)

path = pathlib.Path(os.environ["MZK_CONFIG"])
cfg = (yaml.safe_load(path.read_text()) if path.exists() else {}) or {}
before = yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True)

if isinstance(body.get("model"), dict):
    cfg["model"] = body["model"]

incoming = body.get("providers")
if isinstance(incoming, dict):
    providers = cfg.setdefault("providers", {})
    for name, spec in incoming.items():
        if not isinstance(spec, dict):
            continue
        spec = dict(spec)
        existing = providers.get(name) if isinstance(providers.get(name), dict) else {}
        # Keep a key we already hold when the push carries none. Losing a
        # working credential to an empty push would be the worst outcome here.
        new_key = spec.get("api_key") or ""
        if not new_key and existing.get("api_key"):
            spec["api_key"] = existing["api_key"]
        providers[name] = {**existing, **spec}

after = yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True)
if after == before:
    sys.exit(1)

path.parent.mkdir(parents=True, exist_ok=True)
tmp = path.with_suffix(".yaml.tmp")
tmp.write_text(after)
tmp.replace(path)
print("agent-config: updated model/providers from host")
PY
}

proxy_reachable() {
    gw="$1"; [ -n "$gw" ] || return 1
    curl -sf -m 5 -o /dev/null -X POST "http://$gw:$PORT/mcp" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' 2>/dev/null
}

daedalus_mcp_servers() {
    [ -f "$AGENT_LOG" ] || { echo 0; return; }
    n=$(grep 'MCP: registered' "$AGENT_LOG" 2>/dev/null | tail -1 | sed -n 's/.*from \([0-9][0-9]*\) server.*/\1/p')
    echo "${n:-0}"
}

write_config "$(default_gw)" || true
while :; do
    gw="$(default_gw)"
    write_config "$gw" >/dev/null 2>&1 || true
    if proxy_reachable "$gw"; then
        # Config push first: if it changed anything the agent has to be
        # restarted anyway, and doing both restarts separately would bounce
        # the agent twice in one tick.
        if pull_agent_config "$gw"; then
            echo "[mcp-watch] agent-config changed — restarting podroid-daedalus"
            rc-service podroid-daedalus restart >/dev/null 2>&1 || true
            sleep "$SETTLE"
        elif [ "$(daedalus_mcp_servers)" -lt 1 ] && pgrep -f 'daedalus gateway run' >/dev/null 2>&1; then
            echo "[mcp-watch] proxy up at $gw:$PORT, daedalus has no mazemaker tools — restarting podroid-daedalus"
            rc-service podroid-daedalus restart >/dev/null 2>&1 || true
            sleep "$SETTLE"
        fi
    fi
    sleep "$POLL"
done
