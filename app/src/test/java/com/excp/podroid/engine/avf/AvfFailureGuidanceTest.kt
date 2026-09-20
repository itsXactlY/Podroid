package com.excp.podroid.engine.avf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvfFailureGuidanceTest {
    /** A revision whose AVF boots — the advice ladder applies in full. */
    private val workingSdk = 36

    @Test fun `multi core suggests trying one core`() {
        assertEquals(
            AvfFailureGuidance.Advice.TRY_ONE_CORE,
            AvfFailureGuidance.advise(cpus = 8, sdkInt = workingSdk)
        )
        assertEquals(
            AvfFailureGuidance.Advice.TRY_ONE_CORE,
            AvfFailureGuidance.advise(cpus = 2, sdkInt = workingSdk)
        )
    }

    @Test fun `single core suggests switching to qemu`() {
        assertEquals(
            AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
            AvfFailureGuidance.advise(cpus = 1, sdkInt = workingSdk)
        )
    }

    /**
     * On a revision whose AVF cannot boot at all, the one-core rung is not a
     * smaller guess — it is a wrong one. The failure is the platform's VM
     * manager and its crosvm disagreeing about a command-line argument, which
     * no core count touches. Advice must skip straight to QEMU even with many
     * cores configured.
     */
    @Test fun `a platform that cannot boot AVF skips the one-core rung`() {
        val broken = 37
        assertFalse(AvfCapabilities.platformCanBootAvf(broken))
        assertEquals(
            AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
            AvfFailureGuidance.advise(cpus = 8, sdkInt = broken)
        )
        assertEquals(
            AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
            AvfFailureGuidance.advise(cpus = 1, sdkInt = broken)
        )
    }

    @Test fun `platformCanBootAvf is true below the broken revision`() {
        assertTrue(AvfCapabilities.platformCanBootAvf(26))
        assertTrue(AvfCapabilities.platformCanBootAvf(36))
    }
}
