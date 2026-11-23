package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuestHardwareTest {
    private fun obj(json: String) = AppJson.parseToJsonElement(json) as JsonObject

    private fun List<HardwareLine>.value(label: String) = first { it.label == label }.value

    @Test
    fun qemuConfigParsesSyntheticGuest() {
        // Synthetic guest configuration using the PDM 1.1.7 schema.
        val lines = GuestHardware.lines(
            "qemu",
            obj(
                """{"balloon":1024,"boot":"order=scsi0;net0","cores":6,"cpu":"x86-64-v2-AES","memory":4096,
                "net0":"virtio=02:00:00:00:00:11,bridge=vmbr0,firewall=1","net1":"virtio=02:00:00:00:00:12,bridge=vmbr1,firewall=1",
                "numa":false,"onboot":true,"ostype":"l26","scsi0":"fast-pool:102/vm-102-disk-0.qcow2,iothread=1,size=32G",
                "scsihw":"virtio-scsi-single","sockets":1,"tags":"infra"}""",
            ),
        )
        assertEquals("1 × 6 cores · x86-64-v2-AES", lines.value("CPU"))
        assertEquals("4 GiB · balloon 1 GiB", lines.value("Memory"))
        assertEquals("fast-pool · 32G", lines.value("Disk scsi0"))
        assertEquals("virtio · vmbr0 · firewall", lines.value("NIC net0"))
        assertEquals("scsi0 → net0", lines.value("Boot"))
        assertEquals("Linux 2.6+", lines.value("OS"))
        assertEquals("yes", lines.value("Start at boot"))
    }

    @Test
    fun lxcConfigParsesSyntheticContainer() {
        // Synthetic guest configuration using the PDM 1.1.7 schema.
        val lines = GuestHardware.lines(
            "lxc",
            obj(
                """{"cores":2,"features":"nesting=1","hostname":"demo-monitor","memory":2048,
                "lxc":[["lxc.apparmor.profile","unconfined"]],
                "net0":"name=eth0,bridge=vmbr0,firewall=0,hwaddr=02:00:00:00:00:13,ip=192.0.2.5/24,type=veth",
                "onboot":true,"ostype":"debian","rootfs":"local-zfs:subvol-103-disk-0,size=16G","startup":"order=20","swap":512}""",
            ),
        )
        assertEquals("2 cores", lines.value("CPU"))
        assertEquals("2 GiB · swap 512 MiB", lines.value("Memory"))
        assertEquals("local-zfs · 16G", lines.value("Disk rootfs"))
        assertEquals("eth0 · vmbr0 · 192.0.2.5/24", lines.value("NIC net0"))
        assertEquals("custom lxc.* entries present", lines.value("Raw LXC"))
    }

    @Test
    fun cdromAndSecretsAreHandled() {
        val lines = GuestHardware.lines(
            "qemu",
            obj(
                """{"ide2":"iso-pool:iso/ubuntu-25.10-desktop-amd64.iso,media=cdrom","cipassword":"hunter2",
                "sshkeys":"ssh-ed25519%20AAAA","description":"root pw is hunter2","memory":"512"}""",
            ),
        )
        assertEquals("ubuntu-25.10-desktop-amd64.iso", lines.value("CD ide2"))
        val all = lines.joinToString { "${it.label}=${it.value}" }
        assertFalse(all.contains("hunter2"))
        assertFalse(all.contains("AAAA"))
    }
}
