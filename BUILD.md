# BUILD.md — Podroid Android app (the phone pod)

How to **build**, **start/deploy**, and **maintain** the Podroid APK — the
Android app that runs a real Alpine VM (QEMU/TCG) hosting the iris-messenger pod.
For the gateway itself see the repo-root **`BUILD.md`**; for the deep
architecture map see **`CLAUDE.md`** in this directory.

> **Podman only** (the stock `build-all.sh` uses `docker` — this doc gives the
> podman-equivalent flow, which is the supported path here). Persistence is
> sacred: same `applicationId` + signing key, monotonic `versionCode`.

---

## 0. Artifact map

```
gateway.py / dht_discovery.py / config.py        (repo root — the pod source)
        │  podman build -f Containerfile.source
        ▼
localhost/iris-messenger:arm64  (216 MB image, fixes baked via COPY . .)
        │  podman save --format docker-archive
        ▼
build-rootfs/files/usr/local/share/iris/iris-messenger-arm64.tar  (206 MB)
        │  podman build -f build-rootfs/Dockerfile.rootfs  (stages tar + iris-pod-start.sh)
        ▼
app/src/main/assets/alpine-rootfs.squashfs  (332 MB, zstd)   + vmlinuz/initrd/qemu
        │  ./gradlew :app:assembleDebug
        ▼
app/build/outputs/apk/debug/app-debug.apk   (478 MB)
        │  adb install -r
        ▼
P20 — Alpine VM boots, /etc/init.d/iris-pod `podman load`s the tar, runs the pod
```

`build-all.sh` targets (Docker-cached, upstream): `kernel`, `initramfs`,
`rootfs`, `qemu`, `apk`, `deploy`, `test`, `all`. Kernel/QEMU rarely change and
are slow — build them once. The **pod-update loop below uses podman**.

---

## 1. Build — the pod-update pipeline (podman, the common loop)

After editing the gateway (e.g. `gateway.py`, `dht_discovery.py`, `config.py`)
or the pod launcher (`build-rootfs/files/usr/local/bin/iris-pod-start.sh`):

```bash
cd ~/projects/jrwl-messenger
TAR=android/podroid/build-rootfs/files/usr/local/share/iris/iris-messenger-arm64.tar

# 1) image — COPY . . picks up the source edits (pip layer is cached → ~1 min)
podman build --platform linux/arm64 -f Containerfile.source \
  -t localhost/iris-messenger:arm64 .

# 2) save to the squashfs staging slot — MUST be docker-archive (preserves the
#    repo:tag the guest checks with `podman image exists`; oci-archive can drop
#    it). docker-archive won't overwrite, so rm first:
rm -f "$TAR"
podman save --format docker-archive -o "$TAR" localhost/iris-messenger:arm64

# 3) squashfs via podman (the final `FROM scratch AS export` stage emits just the
#    squashfs; build-rootfs.sh stages the tar + iris-pod-start.sh into the rootfs)
cd android/podroid
sysver=$(grep -E '^[[:space:]]*versionCode' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
podman build -f build-rootfs/Dockerfile.rootfs \
  --build-arg "SYSTEM_VERSION=${sysver:-0}" \
  -o "type=local,dest=app/src/main/assets" build-rootfs/

# 4) APK (bundles the squashfs)
./gradlew :app:assembleDebug
```

**Kotlin-only change** (e.g. `PodroidService.kt`)? Skip 1–3 — just
`./gradlew :app:assembleDebug` (the existing squashfs in `assets/` is reused).

**Kernel / initramfs / QEMU** changes still go through `build-all.sh kernel |
initramfs | qemu` (Docker) — they’re independent of the pod loop.

---

## 2. Start / deploy

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/debug/app-debug.apk   # -r keeps storage.img
$ADB shell am force-stop com.excp.podroid.debug
$ADB shell am start -n com.excp.podroid.debug/com.excp.podroid.MainActivity
```
On launch the app re-extracts assets (stamped by `lastUpdateTime`, so a reinstall
always re-extracts the new squashfs) and boots the VM. **Give the TCG VM
~2–8 min** to settle before ports/SSH respond.

**Reach the pod from the desktop** (the pod binds `127.0.0.1:9091` *on the
device* via the QMP-injected forward — `PodroidService` auto-injects 9091/9092):
```bash
$ADB forward tcp:19091 tcp:9091
curl http://127.0.0.1:19091/api/status            # NO ssh tunnel needed
```

**SSH into the Alpine guest** (QEMU hostfwd `9922→22`, root pw `podroid`):
```bash
$ADB forward tcp:19922 tcp:9922
printf '#!/bin/sh\necho podroid\n' >/tmp/askpass.sh; chmod +x /tmp/askpass.sh
SSH_ASKPASS=/tmp/askpass.sh SSH_ASKPASS_REQUIRE=force setsid -w \
  ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -o PreferredAuthentications=password -o PubkeyAuthentication=no \
      -p 19922 root@127.0.0.1 'echo hi'
```

### Shipping a NEW pod image (critical)
`storage.img` (the persistent ext4 overlay) **caches the loaded image**, and
`/etc/init.d/iris-pod` only `podman load`s the tar when `podman image exists`
is false. So after deploying a new APK, drop the cached image **once** so the
fresh boot loads the new tar:
```bash
# in the guest, then a CLEAN reboot (see Maintain → gotchas):
podman rmi -f localhost/iris-messenger:arm64
```

### Runtime federation config (no rebuild, no baked IPs)
`iris-pod-start.sh` bakes `-e IRIS_RELAY_TRANSPORT=dht` and **`--env-file
/etc/iris/runtime.env`**, so federation knobs are operator-set at runtime and
survive an APK reinstall (they live on the persistent overlay):
```sh
# guest: /etc/iris/runtime.env  (passed straight into the container)
IRIS_PEER_GATEWAYS_BOOTSTRAP=<reachable-peer-host>:8468
IRIS_FEDERATION_RELAY_URL=ws://<reachable-relay-host>:9093
IRIS_FEDERATION_SCHEME=http
# guest: /var/lib/iris/federation_peers.json  (or pair in-app via the Peers panel)
[{"gateway_id":"<peer-id>","public_key":"<peer-ecdsa-pubkey-b64>"}]
```

---

## 3. Maintain — gotchas (hard-won on the P20 / TCG)

- **Don't hand-drive `podman load` over SSH.** The heavy load starves the TCG
  VM and SSH drops mid-load (`kex_exchange_identification: Connection closed`),
  often leaving it wedged. **Reliable path:** `am force-stop` + `am start` and
  let the *normal boot* run `iris-pod start_pre`, which loads the tar itself
  (~6–10 min) while the VM stays stable. Then poll `:9091` patiently.
- **The OpenRC `podman` service lies.** `rc-service podman status` can report
  `failed to start` while `podman info` works fine and the container runs. Don't
  gate on it.
- **VM settle.** After boot, `sshd` / forwards need ~100–460 s before they
  answer; a fresh squashfs re-extract (~225 MB) adds a few minutes.
- **Screen / Doze.** Keep the device awake during long ops:
  `adb shell svc power stayon true` (Doze can throttle the CPU-bound VM — though
  a wedged VM is usually load, not Doze).
- **`asm_stamp` re-extract** is keyed on `lastUpdateTime`, so every reinstall
  re-extracts — no `versionCode` bump needed for asset changes.
- **Verify a deploy** without a phone reboot: `adb forward` to `:9091` and hit
  `/api/status` (200), `/api/federation/handshake` (401, session-gated),
  `/api/federation/relay-circuit` (503 unless `IRIS_FEDERATION_RELAY=1`).
- **Cross-host DHT (UDP 8468) through QEMU SLIRP is unreliable** — an
  inbound-unreachable phone should federate via the **circuit-relay** (outbound
  WS to `IRIS_FEDERATION_RELAY_URL`), not direct DHT-resolved delivery.
- **Unit tests:** `./gradlew :app:testDebugUnitTest` (use the `:app:` form).
  Boot smoke test: `./build-all.sh test` (polls `console.log` for `Ready!`).
