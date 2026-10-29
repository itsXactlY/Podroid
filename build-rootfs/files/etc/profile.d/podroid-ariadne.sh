# DD_HOME for interactive shells.
#
# podroid-ariadne runs `ariadne --home /opt/ariadne/home`. Typing `ariadne` in
# the Podroid terminal must open the same home -- the same config.yaml, key and
# skills -- or the screen and the service disagree about which model and which
# memory the agent has. Without this, ariadne would take the folder beside its
# binary (/opt/ariadne) as its home.
#
# Keep this value identical to ARIADNE_HOME in /opt/ariadne/start.sh.
export DD_HOME=/opt/ariadne/home
