/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure policy: given the current vCPU setting, what should we advise the user
 * when an AVF guest fails to boot? AVF has no vCPU-count API (only ONE_CPU or
 * MATCH_HOST), and some hypervisors (e.g. Tensor G3, issue #29) crash under
 * MATCH_HOST during secondary-vCPU bringup but boot fine with one vCPU.
 * No Android deps so it unit-tests on the JVM.
 */
package com.excp.podroid.engine.avf

object AvfFailureGuidance {
    enum class Advice { TRY_ONE_CORE, SWITCH_TO_QEMU }

    /** cpus > 1 -> the MATCH_HOST topology may be the cause; suggest one core
     *  first. cpus == 1 -> one core already failed, AVF can't run here.
     *
     *  On a platform whose AVF cannot boot at all (see
     *  AvfCapabilities.platformCanBootAvf) the one-core rung is not a smaller
     *  guess, it is a wrong one: the failure is the platform's VM manager and
     *  crosvm disagreeing about a command-line argument, which no core count
     *  touches. Sending somebody to try one core there costs a boot, a wait,
     *  and the same error — so go straight to the thing that works. */
    fun advise(cpus: Int, sdkInt: Int): Advice = when {
        !AvfCapabilities.platformCanBootAvf(sdkInt) -> Advice.SWITCH_TO_QEMU
        cpus > 1 -> Advice.TRY_ONE_CORE
        else -> Advice.SWITCH_TO_QEMU
    }
}
