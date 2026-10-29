#!/bin/sh
# Ariadne launcher for the podroid Alpine guest: the OpenAI-compatible surface
# (/health, /health/detailed, /v1/models, /v1/chat/completions) the Mazemaker
# app talks to over HTTP on the guest's :8088.
#
# NO SECRET IS BAKED IN. The bearer is generated RANDOM per install and kept in
# the overlay at $ARIADNE_HOME/api_server.key; the operator reads it once and
# pastes it into the app. Migration 49 copies the Daedalus key here, so an app
# that already holds one keeps working across the switch.
ARIADNE_HOME="${ARIADNE_HOME:-/opt/ariadne/home}"
mkdir -p "$ARIADNE_HOME"

KEY_FILE="$ARIADNE_HOME/api_server.key"
if [ ! -s "$KEY_FILE" ]; then
    (umask 077; od -An -tx1 -N32 /dev/urandom | tr -d ' \n' > "$KEY_FILE")
fi

# config.yaml from pod.env (what the app pushed: the model, the MCP proxy),
# unless the operator has taken the file over.
/opt/ariadne/configure.sh || echo "start.sh: configure.sh failed; ariadne reads the config.yaml that is there" >&2

DD_SERVE_KEY="$(cat "$KEY_FILE")"
export DD_SERVE_KEY
exec /opt/ariadne/ariadne --home "$ARIADNE_HOME" --serve 0.0.0.0:8088
