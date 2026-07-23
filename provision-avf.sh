#!/usr/bin/env bash
#
# provision-avf.sh — The Box bench-provisioning tool.
#
# Grants Podroid the two AVF (Android Virtualization Framework) permissions
# over USB, ONCE, per phone. The grant is stored in runtime-permissions.xml and
# PERSISTS across reboots AND app updates — only a full uninstall clears it. So
# a phone provisioned here boots straight into hardware-accelerated AVF forever;
# the end user never touches Developer Options, Wireless Debugging, or adb.
#
# This is the "factory bench" step: plug a fresh phone into the assembly
# machine, run this, unplug. Zero on-device intervention for the end user.
#
# Why a bench step at all: on stock, non-rooted Android the two perms are
# protectionLevel signature|DEVELOPMENT. `pm grant` sets them, but pm grant runs
# as shell-uid (2000), and the ONLY on-device route to shell-uid is Wireless
# Debugging (a human-approved pairing) — Google's SELinux design leaves no
# app-level bypass. Running pm grant over USB from this bench sidesteps that
# entirely: the operator's machine already IS an authorized adb host.
#
# Usage:
#   ./provision-avf.sh                 # provision the single connected device
#   ./provision-avf.sh -s <serial>     # target a specific device (adb -s)
#   ./provision-avf.sh --all           # provision EVERY connected device
#   ./provision-avf.sh --revoke        # revoke the perms (back to QEMU/TCG)
#   ADB=/path/to/adb ./provision-avf.sh
#
# Exit codes: 0 all targeted devices provisioned+verified; 1 usage/precondition
# error; 2 one or more devices failed to verify.

set -euo pipefail

PKG="com.excp.podroid"
PERMS=(
  "android.permission.MANAGE_VIRTUAL_MACHINE"
  "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
)
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  for cand in "$HOME/Android/Sdk/platform-tools/adb" /usr/lib/android-sdk/platform-tools/adb; do
    [ -x "$cand" ] && ADB="$cand" && break
  done
fi
command -v "$ADB" >/dev/null 2>&1 || { echo "FATAL: adb not found (set ADB=/path/to/adb)"; exit 1; }

MODE="single"     # single | all
REVOKE=0
SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="${2:-}"; shift 2 ;;
    --all) MODE="all"; shift ;;
    --revoke) REVOKE=1; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

# Resolve the device list.
mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "No authorized device connected. Plug a phone in (accept the USB-debugging"
  echo "prompt on the phone) and retry."
  exit 1
fi
if [ -n "$SERIAL" ]; then
  TARGETS=("$SERIAL")
elif [ "$MODE" = "all" ]; then
  TARGETS=("${DEVICES[@]}")
elif [ "${#DEVICES[@]}" -gt 1 ]; then
  echo "Multiple devices connected — pass -s <serial> or --all:"
  printf '  %s\n' "${DEVICES[@]}"
  exit 1
else
  TARGETS=("${DEVICES[0]}")
fi

# Run a shell command on one device.
sh_on() { "$ADB" -s "$1" shell "$2"; }

# True if the perm reads as granted for user 0. dumpsys prints the runtime
# grant state per user; user 0 is the primary user (a granted=false line for a
# secondary/Private-Space user id like 10/11 is irrelevant).
is_granted() {
  # Print the runtime-permissions block and check the perm's line just below
  # it says granted=true. Robust to OEM formatting: we look for the perm name
  # followed (within a few lines) by granted=true, scoped to the first
  # (primary-user) occurrence.
  sh_on "$1" "dumpsys package $PKG" 2>/dev/null \
    | awk -v p="$2" '
        $0 ~ p {found=1; c=0; next}
        found && c<3 {c++; if ($0 ~ /granted=true/){print "yes"; exit}
                              if ($0 ~ /granted=false/){print "no"; exit}}
      ' | head -1
}

provision_one() {
  local dev="$1" rc=0 model
  model="$(sh_on "$dev" "getprop ro.product.model" 2>/dev/null | tr -d '\r')"
  echo "=== $dev  (${model:-unknown}) ==="

  # Precondition 1: Podroid installed.
  if ! sh_on "$dev" "pm path $PKG" >/dev/null 2>&1; then
    echo "  ✗ $PKG is NOT installed — install The Box APK first."
    return 2
  fi

  # Precondition 2: AVF actually exists on this hardware (Pixel-class / pKVM).
  if sh_on "$dev" "pm list features" 2>/dev/null | grep -qi "virtualization_framework"; then
    echo "  • AVF hardware: present (android.software.virtualization_framework)"
  else
    echo "  ⚠ AVF hardware feature NOT advertised — this phone may lack pKVM."
    echo "    Granting anyway (harmless); Podroid will fall back to QEMU if AVF won't start."
  fi

  for perm in "${PERMS[@]}"; do
    if [ "$REVOKE" -eq 1 ]; then
      sh_on "$dev" "pm revoke $PKG $perm" >/dev/null 2>&1 || true
      echo "  • revoked ${perm##*.}"
      continue
    fi
    # pm grant is idempotent; a signature|development perm accepts it (a pure
    # signature perm would error 'not a changeable permission type').
    if err="$(sh_on "$dev" "pm grant $PKG $perm" 2>&1)"; then
      :
    else
      echo "  ✗ pm grant ${perm##*.} FAILED: $err"
      rc=2
    fi
  done

  if [ "$REVOKE" -eq 1 ]; then
    echo "  ↺ revoked — device will use QEMU/TCG on next launch."
    return 0
  fi

  # Verify.
  for perm in "${PERMS[@]}"; do
    case "$(is_granted "$dev" "$perm")" in
      yes) echo "  ✓ ${perm##*.} = granted" ;;
      *)   echo "  ✗ ${perm##*.} = NOT granted after pm grant"; rc=2 ;;
    esac
  done

  if [ "$rc" -eq 0 ]; then
    echo "  ✅ AVF provisioned — persists across reboot + app update (cleared only by uninstall)."
    echo "     Set Podroid's engine to AUTO or AVF; next VM boot runs on hardware pKVM."
  fi
  return "$rc"
}

OVERALL=0
for dev in "${TARGETS[@]}"; do
  provision_one "$dev" || OVERALL=2
done
exit "$OVERALL"
