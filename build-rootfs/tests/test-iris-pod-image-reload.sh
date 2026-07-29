#!/bin/sh
# Exercises iris-pod start_pre() image-load logic against a stub podman.
set -u
mkdir -p /work/bin /usr/local/share/iris /var/lib/iris
cat > /work/bin/podman <<'PODMAN'
#!/bin/sh
case "$1 $2" in
  "image exists") [ -f /work/state/image ] && exit 0 || exit 1 ;;
esac
case "$1" in
  load) mkdir -p /work/state; cp "$3" /work/state/loaded_from; touch /work/state/image
        echo "LOAD $3" >> /work/log; exit 0 ;;
  rm)   echo "RM $*" >> /work/log; exit 0 ;;
esac
exit 0
PODMAN
chmod +x /work/bin/podman
export PATH=/work/bin:$PATH

# Stubs for the OpenRC helpers the script calls.
einfo()  { echo "INFO $*" >> /work/log; }
eerror() { echo "ERR $*" >> /work/log; }

IRIS_IMAGE="localhost/iris-messenger:arm64"
IRIS_CONTAINER_NAME="iris-messenger"
IRIS_DATA_DIR="/var/lib/iris"

# Extract start_pre() from the real script (skip the openrc shebang/directives).
sed -n '/^start_pre()/,/^}/p' /s > /work/start_pre.sh
. /work/start_pre.sh

fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2' want '$3'"; fail=1; fi; }

printf 'AAA' > /usr/local/share/iris/iris-messenger-arm64.tar
want_a=$(sha256sum /usr/local/share/iris/iris-messenger-arm64.tar | cut -d' ' -f1)

# 1) image missing -> load + stamp
rm -rf /work/state /work/log; : > /work/log
start_pre
check "1 missing: loaded"      "$(grep -c '^LOAD' /work/log)" "1"
check "1 missing: stamp"       "$(cat /var/lib/iris/.iris-image.sha256)" "$want_a"

# 2) image present, stamp matches -> no load, no rm
: > /work/log
start_pre
check "2 unchanged: no load"   "$(grep -c '^LOAD' /work/log)" "0"
check "2 unchanged: no rm"     "$(grep -c '^RM' /work/log)" "0"

# 3) tarball changed -> rm container + reload + new stamp
printf 'BBBB' > /usr/local/share/iris/iris-messenger-arm64.tar
want_b=$(sha256sum /usr/local/share/iris/iris-messenger-arm64.tar | cut -d' ' -f1)
: > /work/log
start_pre
check "3 changed: reloaded"    "$(grep -c '^LOAD' /work/log)" "1"
check "3 changed: container rm" "$(grep -c '^RM' /work/log)" "1"
check "3 changed: stamp bumped" "$(cat /var/lib/iris/.iris-image.sha256)" "$want_b"

# 4) idempotent after the reload
: > /work/log
start_pre
check "4 after reload: no load" "$(grep -c '^LOAD' /work/log)" "0"

exit $fail
