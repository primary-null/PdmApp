package com.pdm.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One labelled line of the hardware panel ("CPU" -> "1 × 6 cores · x86-64-v2-AES"). */
data class HardwareLine(val label: String, val value: String)

/**
 * Reads a PVE guest config (`.../{qemu|lxc}/{vmid}/config`) into the lines an operator scans first.
 * Never surfaces secrets: cloud-init passwords, SSH keys and notes are skipped by design.
 */
object GuestHardware {
    private val DISK_KEY = Regex("^(scsi|virtio|sata|ide|efidisk|tpmstate)\\d+$")
    private val MOUNT_KEY = Regex("^mp\\d+$")
    private val NET_KEY = Regex("^net\\d+$")

    fun lines(type: String, config: JsonObject): List<HardwareLine> {
        fun str(key: String): String? = (config[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun flag(key: String): Boolean? = (config[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.let { c -> c == "1" } }
        val out = mutableListOf<HardwareLine>()
        val qemu = type == "qemu"

        val cores = str("cores")?.toIntOrNull() ?: 1
        if (qemu) {
            val sockets = str("sockets")?.toIntOrNull() ?: 1
            out += HardwareLine("CPU", listOfNotNull("$sockets × $cores cores", str("cpu")).joinToString(" · "))
            out += HardwareLine("Memory", listOfNotNull(str("memory")?.let { mib(it) }, str("balloon")?.takeIf { it != "0" }?.let { "balloon ${mib(it)}" }).joinToString(" · ").ifBlank { "—" })
            str("bios")?.let { out += HardwareLine("Firmware", if (it == "ovmf") "UEFI (OVMF)" else "BIOS ($it)") }
        } else {
            out += HardwareLine("CPU", "$cores cores")
            out += HardwareLine("Memory", listOfNotNull(str("memory")?.let { mib(it) }, str("swap")?.let { "swap ${mib(it)}" }).joinToString(" · "))
        }

        val diskKeys = config.keys.filter { DISK_KEY.matches(it) || MOUNT_KEY.matches(it) || it == "rootfs" }.sortedWith(compareBy({ it != "rootfs" }, { it }))
        diskKeys.forEach { key ->
            val props = parseProps(str(key) ?: return@forEach)
            val volume = props.first
            val media = props.second["media"]
            val storage = volume.substringBefore(':', missingDelimiterValue = volume)
            when {
                media == "cdrom" -> out += HardwareLine("CD $key", if (volume == "none") "empty" else volume.substringAfter(':').substringAfterLast('/'))
                else -> out += HardwareLine(
                    "Disk $key",
                    listOfNotNull(storage, props.second["size"], props.second["mp"]?.let { "at $it" }).joinToString(" · "),
                )
            }
        }

        config.keys.filter { NET_KEY.matches(it) }.sorted().forEach { key ->
            val (first, props) = parseProps(str(key) ?: return@forEach)
            val model = if (qemu) first.substringBefore('=') else props["name"] ?: key
            out += HardwareLine(
                "NIC $key",
                listOfNotNull(
                    model,
                    props["bridge"],
                    props["tag"]?.let { "vlan $it" },
                    props["ip"]?.takeIf { !qemu },
                    "firewall".takeIf { props["firewall"] == "1" },
                ).joinToString(" · "),
            )
        }

        str("boot")?.let { out += HardwareLine("Boot", it.removePrefix("order=").replace(";", " → ")) }
        str("ostype")?.let { out += HardwareLine("OS", osLabel(it)) }
        flag("onboot")?.let { out += HardwareLine("Start at boot", if (it) "yes" else "no") }
        str("startup")?.let { out += HardwareLine("Startup", it.replace(",", " · ")) }
        if (qemu) {
            str("agent")?.let { out += HardwareLine("Guest agent", if (it.startsWith("1") || it.contains("enabled=1")) "enabled" else "off") }
        } else {
            str("features")?.let { out += HardwareLine("Features", it.replace(",", " · ")) }
            if ((config["lxc"] as? JsonArray)?.isNotEmpty() == true) out += HardwareLine("Raw LXC", "custom lxc.* entries present")
        }
        return out
    }

    /** `vol,key=v,...` -> (vol, map). A leading `k=v` (QEMU NIC `virtio=MAC`) is kept as the first token. */
    private fun parseProps(raw: String): Pair<String, Map<String, String>> {
        val parts = raw.split(',')
        val map = parts.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val firstKv = parts.first().split('=', limit = 2)
        val withFirst = if (firstKv.size == 2) map + (firstKv[0] to firstKv[1]) else map
        return parts.first() to withFirst
    }

    private fun mib(raw: String): String {
        val v = raw.toLongOrNull() ?: return raw
        return if (v >= 1024 && v % 1024 == 0L) "${v / 1024} GiB" else "$v MiB"
    }

    private fun osLabel(raw: String) = when (raw) {
        "l26" -> "Linux 2.6+"
        "l24" -> "Linux 2.4"
        "win11" -> "Windows 11 / 2022+"
        "win10" -> "Windows 10 / 2016-2019"
        "other" -> "other"
        else -> raw
    }
}
