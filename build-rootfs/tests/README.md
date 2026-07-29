# build-rootfs tests

Guest-side shell logic that is otherwise only exercised on a phone.

## test-iris-pod-image-reload.sh

Covers `start_pre()` in `files/etc/init.d/iris-pod` — the decision whether to
`podman load` the vendored iris pod tarball.

This is worth a test because the failure mode is silent: the original code
loaded the tarball only when `podman image exists` was false, so on every
Podroid upgrade the freshly shipped image was skipped (the container store
lives on the persistent ext4 overlay and still held the previous one) and the
phone kept running the old pod. Nothing logged, nothing failed — the new
gateway simply never ran.

Run it against a stub `podman` in Alpine:

```sh
podman run --rm \
  -v "$PWD/build-rootfs/files/etc/init.d/iris-pod:/s:ro" \
  -v "$PWD/build-rootfs/tests/test-iris-pod-image-reload.sh:/test.sh:ro" \
  alpine:3.23 sh /test.sh
```

Asserts: load when the image is missing, no load when the tarball is unchanged,
reload **plus** container removal when the tarball's sha256 changed, and
idempotence on the boot after a reload.
