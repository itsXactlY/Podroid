# DAEDALUS_HOME for interactive shells.
#
# /opt/daedalus/start.sh exports this for the podroid-daedalus service, but an
# export inside that script is invisible to a login shell. Without this file
# the two disagree: `daedalus setup` in the Podroid terminal writes to
# ~/.daedalus (i.e. /root/.daedalus) while the running gateway reads
# /opt/daedalus/daedalus-agent-data — so the operator completes setup, the
# agent still has no credentials, and it asks them to run setup again.
#
# Keep this value identical to the default in /opt/daedalus/start.sh.
export DAEDALUS_HOME=/opt/daedalus/daedalus-agent-data
