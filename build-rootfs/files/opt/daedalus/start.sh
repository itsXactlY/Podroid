#!/bin/sh
# daedalus native-python launcher for the podroid Alpine guest.
# Runs the OpenAI-compatible api_server gateway that the mazemaker Android app
# talks to over HTTP on the guest's :8088.
#
# NO SECRET IS BAKED IN. The api_server bearer (API_SERVER_KEY) is generated
# RANDOM per install and persisted in the overlay; the operator reads it once
# (this file is at /opt/daedalus/api_server.key) and pastes it into the app.
export API_SERVER_ENABLED="${API_SERVER_ENABLED:-true}"
export API_SERVER_HOST="${API_SERVER_HOST:-0.0.0.0}"
export API_SERVER_PORT="${API_SERVER_PORT:-8088}"
export API_SERVER_CORS_ORIGINS="${API_SERVER_CORS_ORIGINS:-*}"
export DAEDALUS_HOME="${DAEDALUS_HOME:-/opt/daedalus/daedalus-agent-data}"
export PYTHONUNBUFFERED=1
mkdir -p "$DAEDALUS_HOME"

# Per-install api_server bearer — generated once, then stable across reboots.
KEY_FILE="$DAEDALUS_HOME/api_server.key"
if [ ! -s "$KEY_FILE" ]; then
    /opt/daedalus/venv/bin/python3 -c 'import secrets;print(secrets.token_hex(32))' > "$KEY_FILE" 2>/dev/null
    chmod 600 "$KEY_FILE" 2>/dev/null || true
fi
export API_SERVER_KEY="${API_SERVER_KEY:-$(cat "$KEY_FILE" 2>/dev/null)}"

# Seed the LLM config template on first run (no key — the operator supplies it,
# via the app's paste field or by editing this file). Never overwrites an
# existing config, so a pasted key survives.
if [ ! -f "$DAEDALUS_HOME/config.yaml" ] && [ -f /opt/daedalus/config.template.yaml ]; then
    cp /opt/daedalus/config.template.yaml "$DAEDALUS_HOME/config.yaml"
fi

exec /opt/daedalus/venv/bin/daedalus gateway run
