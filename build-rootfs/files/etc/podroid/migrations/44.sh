#!/bin/sh
# 44.sh — install an operator SSH public key for root, if this build carries one.
#
# Recovers access without the root password, and stops the password being the
# only way in. dropbear authenticates straight against /etc/shadow, so a
# forgotten password meant no shell at all: the console needs the phone
# unlocked with its screen held open, which on a burned-in OLED is not a real
# option, and every other route into the guest goes through that shell.
#
# Runs as root from podroid-migrate at boot, which is why it needs no
# credential. dropbear reads ~/.ssh/authorized_keys natively, so there is no
# sshd config to change.
#
# The key is read from /etc/podroid/operator_key.pub rather than written into
# this script, and that file is gitignored and copied in only when present.
# Embedding it here would publish one operator's key into every install of a
# distributed product: a public key is not a secret, but anyone holding the
# matching private key would then have root on every device that ever shipped
# this rootfs. Absent the file, this migration does nothing.
#
# Password auth stays enabled — disabling it would make the key a single point
# of failure, and the operator can now get in and run `passwd`.
#
# Idempotent: appends only when the key is absent.
set -eu

SRC=/etc/podroid/operator_key.pub
[ -s "$SRC" ] || { echo "44.sh: no operator key in this build — nothing to do"; exit 0; }

mkdir -p /root/.ssh
chmod 700 /root/.ssh
touch /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys

while IFS= read -r KEY; do
    [ -n "$KEY" ] || continue
    case "$KEY" in \#*) continue ;; esac
    if grep -qF "$KEY" /root/.ssh/authorized_keys 2>/dev/null; then
        echo "44.sh: key already present"
    else
        printf '%s\n' "$KEY" >> /root/.ssh/authorized_keys
        echo "44.sh: installed operator SSH key for root"
    fi
done < "$SRC"

# The sshd gate starts dropbear only once a password has been set. Seed the
# marker if missing, so key auth cannot be locked out by a gate designed around
# passwords alone.
if [ ! -e /etc/podroid/password-set ]; then
    mkdir -p /etc/podroid
    : > /etc/podroid/password-set
    echo "44.sh: seeded password-set marker so sshd starts for key auth"
fi

exit 0
