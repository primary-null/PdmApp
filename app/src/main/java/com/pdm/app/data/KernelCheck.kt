package com.pdm.app.data

import com.pdm.app.data.model.AptUpdateDto

/**
 * Whether a newer kernel is installed than the one running, which only a restart picks up.
 * Installing packages never restarts a Proxmox machine; this says when one is due.
 */
object KernelCheck {
    // proxmox-kernel-7.0.14-25-pve-signed, pve-kernel-6.8.12-4-pve. Meta packages
    // (proxmox-kernel-7.0, proxmox-default-kernel) carry no build and are skipped.
    private val KERNEL_PACKAGE = Regex("""^(?:proxmox|pve)-kernel-(\d+\.\d+\.\d+-\d+)-pve(?:-signed)?$""")
    private val RUNNING = Regex("""^(\d+\.\d+\.\d+-\d+)""")

    /** The newest installed kernel build when it is newer than [runningRelease]; else null. */
    fun pendingKernel(runningRelease: String?, versions: List<AptUpdateDto>?): String? {
        val running = runningRelease?.let { RUNNING.find(it.trim())?.groupValues?.get(1) } ?: return null
        val newest = versions.orEmpty()
            .mapNotNull { KERNEL_PACKAGE.matchEntire(it.name)?.groupValues?.get(1) }
            .maxWithOrNull(::compareBuilds)
            ?: return null
        return newest.takeIf { compareBuilds(it, running) > 0 }
    }

    /** "7.0.14-25" vs "7.0.14-20", number by number. */
    fun compareBuilds(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** "7.0.14-20-pve" -> "7.0.14-20" for display. */
    fun build(release: String?): String? = release?.let { RUNNING.find(it.trim())?.groupValues?.get(1) ?: it }
}
