package com.pdm.app.data.net

/**
 * Probe classification for LAN discovery. PDM listens on 8443; PVE is 8006.
 * A listener on 8443 that looks like a hypervisor or PBS is not a PDM hit.
 */
sealed class ProbeResult {
    data class Verified(val version: String) : ProbeResult()
    data object PdmDetected : ProbeResult()
    data object NotPdm : ProbeResult()
    data object Unreachable : ProbeResult()
}

object PdmFingerprint {
    fun pingIsPong(body: String?): Boolean {
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (trimmed.equals("pong", ignoreCase = true)) return true
        return Regex(""""data"\s*:\s*"pong"""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    }

    fun isPveSurface(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("proxmox virtual environment") ||
            t.contains("pvemanagerlib.js") ||
            t.contains("ext6-pve.css") ||
            t.contains("pve-api-daemon")
    }

    fun isPbsSurface(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("proxmox backup server") ||
            t.contains("proxmox-backup-server")
    }

    fun isPdmSurface(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("proxmox datacenter manager") ||
            t.contains("proxmox-datacenter-manager")
    }

    /**
     * @param remotesCode HTTP status of GET /api2/json/remotes/remote, or null if not fetched
     */
    fun classify(
        serverHeader: String? = null,
        html: String? = null,
        pingBody: String? = null,
        remotesCode: Int? = null,
    ): ProbeResult {
        val header = serverHeader.orEmpty()
        val page = html.orEmpty()
        val combined = "$header\n$page"
        if (isPveSurface(combined) || isPbsSurface(combined)) return ProbeResult.NotPdm
        if (isPdmSurface(combined)) {
            return ProbeResult.Verified("PDM")
        }
        val pong = pingIsPong(pingBody)
        if (pong && remotesCode != null) {
            return when (remotesCode) {
                401, 403 -> ProbeResult.PdmDetected
                in 200..299 -> ProbeResult.Verified("PDM")
                404 -> ProbeResult.NotPdm
                else -> ProbeResult.PdmDetected
            }
        }
        if (pong) return ProbeResult.PdmDetected
        return ProbeResult.Unreachable
    }
}
