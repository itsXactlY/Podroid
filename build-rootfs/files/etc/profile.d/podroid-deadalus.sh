# DEADALUS_HOME for interactive shells.
#
# /opt/deadalus/start.sh exports this for the podroid-deadalus service, but an
# export inside that script is invisible to a login shell. Without this file
# the two disagree: `deadalus setup` in the Podroid terminal writes to
# ~/.deadalus (i.e. /root/.deadalus) while the running gateway reads
# /opt/deadalus/deadalus-agent-data — so the operator completes setup, the
# agent still has no credentials, and it asks them to run setup again.
#
# Keep this value identical to the default in /opt/deadalus/start.sh.
export DEADALUS_HOME=/opt/deadalus/deadalus-agent-data
