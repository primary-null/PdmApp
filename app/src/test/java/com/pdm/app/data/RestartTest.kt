package com.pdm.app.data

import com.pdm.app.data.model.AptUpdateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartTest {
    private fun pkg(name: String) = AptUpdateDto(name = name, version = "1")

    @Test
    fun aNewerInstalledKernelMeansARestartIsNeeded() {
        // What PDM had after today's upgrade: running 7.0.14-20, 7.0.14-25 installed.
        val installed = listOf(
            pkg("proxmox-datacenter-manager"),
            pkg("proxmox-kernel-7.0"),
            pkg("proxmox-kernel-7.0.14-20-pve-signed"),
            pkg("proxmox-kernel-7.0.14-25-pve-signed"),
        )
        assertEquals("7.0.14-25", KernelCheck.pendingKernel("7.0.14-20-pve", installed))
    }

    @Test
    fun runningTheNewestKernelNeedsNothing() {
        val installed = listOf(pkg("proxmox-kernel-7.0.14-20-pve-signed"), pkg("proxmox-kernel-7.0.14-25-pve-signed"))
        assertNull(KernelCheck.pendingKernel("7.0.14-25-pve", installed))
        // An old kernel kept around is not a reason to restart.
        assertNull(KernelCheck.pendingKernel("7.0.14-25-pve", listOf(pkg("pve-kernel-6.8.12-4-pve"))))
        assertNull(KernelCheck.pendingKernel(null, installed))
        assertNull(KernelCheck.pendingKernel("7.0.14-20-pve", null))
    }

    @Test
    fun buildsCompareNumberByNumber() {
        assertEquals(1, KernelCheck.compareBuilds("7.0.14-25", "7.0.14-20").coerceIn(-1, 1))
        assertEquals(1, KernelCheck.compareBuilds("7.0.14-10", "7.0.14-9").coerceIn(-1, 1))
        assertEquals(-1, KernelCheck.compareBuilds("6.8.12-4", "7.0.1-1").coerceIn(-1, 1))
        assertEquals(0, KernelCheck.compareBuilds("7.0.14-25", "7.0.14-25"))
        assertEquals("7.0.14-20", KernelCheck.build("7.0.14-20-pve"))
    }

    @Test
    fun backOnlyAfterReallyGoingDown() {
        val w = RestartWatch(uptimeBefore = 190_000)
        // Still answering with the old uptime right after the restart was asked for: not back.
        assertEquals(RestartWatch.Phase.WAITING, w.observe(HostProbe.Up(190_003)))
        assertEquals(RestartWatch.Phase.DOWN, w.observe(HostProbe.Silent))
        assertEquals(RestartWatch.Phase.DOWN, w.observe(HostProbe.Silent))
        assertEquals(RestartWatch.Phase.BACK, w.observe(HostProbe.Up(12)))
    }

    @Test
    fun missingBaselineRequiresAnOutageAndHealthyStatus() {
        val w = RestartWatch(null)
        assertEquals(RestartWatch.Phase.WAITING, w.observe(HostProbe.Up(50)))
        assertEquals(RestartWatch.Phase.DOWN, w.observe(HostProbe.Silent))
        assertEquals(RestartWatch.Phase.DOWN, w.observe(HostProbe.Answering))
        assertEquals(RestartWatch.Phase.BACK, w.observe(HostProbe.Up(12)))
    }

    @Test
    fun aRestartBetweenTwoLooksStillCounts() {
        // Fast reboot: never seen down, but the uptime started over.
        assertEquals(RestartWatch.Phase.BACK, RestartWatch(190_000).observe(HostProbe.Up(20)))
        // An HTTP error or incomplete response never proves recovery.
        val w = RestartWatch(190_000)
        assertEquals(RestartWatch.Phase.WAITING, w.observe(HostProbe.Answering))
        w.observe(HostProbe.Silent)
        assertEquals(RestartWatch.Phase.DOWN, w.observe(HostProbe.Answering))
        assertEquals(RestartWatch.Phase.DOWN, w.observe(HostProbe.Up(190_010)))
        assertEquals(RestartWatch.Phase.BACK, w.observe(HostProbe.Up(12)))
    }
}
