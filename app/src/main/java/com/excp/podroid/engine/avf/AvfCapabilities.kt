/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure decode of VirtualMachineManager.getCapabilities() bitmask + the
 * protected-VM selection policy. Podroid runs a raw custom kernel/initrd
 * image, which AVF can only boot as a NON-protected VM (protected VMs need a
 * pvmfw-signed payload). So: prefer non-protected; if the device only supports
 * protected VMs we cannot run here and the caller must fall back to QEMU.
 * Pure (no Android deps) so it unit-tests on the JVM.
 */
package com.excp.podroid.engine.avf

object AvfCapabilities {
    const val CAPABILITY_PROTECTED_VM = 1
    const val CAPABILITY_NON_PROTECTED_VM = 2

    sealed interface ProtectedVmChoice {
        /** Apply setProtectedVm(false) and proceed - the common path. */
        data object NonProtected : ProtectedVmChoice
        /** getCapabilities() was unavailable; try non-protected and let any throw surface. */
        data object Unknown : ProtectedVmChoice
        /** Device can't run our custom image; caller must fall back to QEMU. */
        data class Unsupported(val reason: String) : ProtectedVmChoice
    }

    fun decode(capabilities: Int): String {
        if (capabilities == 0) return "none/unknown"
        val parts = mutableListOf<String>()
        if (capabilities and CAPABILITY_PROTECTED_VM != 0) parts += "PROTECTED"
        if (capabilities and CAPABILITY_NON_PROTECTED_VM != 0) parts += "NON_PROTECTED"
        return if (parts.isEmpty()) "0x${capabilities.toString(16)}" else parts.joinToString("+")
    }

    /**
     * Does this platform revision's AVF actually boot a guest with networking?
     *
     * Android 17 ships a system VM manager whose crosvm no longer accepts the
     * `--net` argument the manager itself builds. The manager creates the TAP,
     * emits `--net tap-fd=N`, and the crosvm beside it — which moved to
     * `--tap-fd=` — rejects the argv and exits:
     *
     *     E crosvm : arg parsing failed: Unrecognized argument: --net
     *     I virtmgr: crosvm(17555) exited with status exit status: 35
     *
     * The VM reaches `status=1` (booting) and is dead 24 ms later. None of that
     * is visible to this app: it does not write those argv entries, so it cannot
     * fix the disagreement — only route around it.
     *
     * Why AVF cannot simply run without a network instead: the guest's
     * `podroid-network` returns 1 when it finds no interface, and
     * `podroid-ready` carries `need podroid-network`, so a networkless boot
     * never emits `Ready!` and the VM never reaches Running.
     *
     * So on this revision AUTO must not choose AVF. The failure is
     * deterministic — it happens on every start — which makes selecting around
     * it strictly better than reacting to a 24 ms death: no pointless "try one
     * core" rung (a core count cannot fix an argv mismatch), no tap, no loop.
     *
     * Forcing AVF explicitly still honours the choice and still surfaces the
     * error, because an operator may know something this probe does not.
     */
    fun platformCanBootAvf(sdkInt: Int): Boolean = sdkInt < 37

    fun choose(capabilities: Int): ProtectedVmChoice = when {
        capabilities == 0 -> ProtectedVmChoice.Unknown
        capabilities and CAPABILITY_NON_PROTECTED_VM != 0 -> ProtectedVmChoice.NonProtected
        capabilities and CAPABILITY_PROTECTED_VM != 0 -> ProtectedVmChoice.Unsupported(
            "This device's hypervisor only supports protected VMs; AVF can't run " +
                "Podroid's custom Linux image here. Falling back to QEMU."
        )
        else -> ProtectedVmChoice.Unknown
    }
}
