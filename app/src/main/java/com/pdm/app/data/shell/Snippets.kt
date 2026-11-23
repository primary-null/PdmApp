package com.pdm.app.data.shell

import kotlinx.serialization.Serializable

/** A command the terminal can type for you. Never sent with Enter: you run it. */
@Serializable
data class Snippet(val id: String, val title: String, val command: String)

/**
 * Snippets for Debian-based terminals: a Proxmox
 * VE node, or the PDM host. So the list is fixed instead of guessed per distro, and the Proxmox
 * commands appear only where they exist (on nodes, not on the PDM host).
 */
object Snippets {
    private val debian = listOf(
        Snippet("deb_upgradable", "Upgradable packages", "apt list --upgradable"),
        Snippet("deb_df", "Disk usage", "df -h"),
        Snippet("deb_free", "Memory", "free -h"),
        Snippet("deb_failed", "Failed services", "systemctl --failed"),
        Snippet("deb_journal", "Recent journal", "journalctl -n 50 --no-pager"),
        Snippet("deb_ip", "IP addresses", "ip -br addr"),
        Snippet("deb_uptime", "Uptime & load", "uptime"),
    )

    private val proxmox = listOf(
        Snippet("pve_version", "PVE version", "pveversion -v"),
        Snippet("pve_qm_list", "List VMs", "qm list"),
        Snippet("pve_pct_list", "List containers", "pct list"),
        Snippet("pve_storage", "Storage status", "pvesm status"),
        Snippet("pve_cluster", "Cluster status", "pvecm status"),
    )

    fun builtins(target: ShellTarget): List<Snippet> = when (target) {
        is ShellTarget.RemoteNode -> proxmox + debian
        is ShellTarget.Host -> debian
    }

    /**
     * What actually gets typed: one line, no trailing newline, so a snippet can never run by itself.
     * Line breaks inside a saved command become spaces.
     */
    fun typed(command: String): String {
        val oneLine = command.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val stripped = buildString(oneLine.length) {
            for (ch in oneLine) {
                when (Character.getType(ch)) {
                    Character.CONTROL.toInt(),
                    Character.FORMAT.toInt(),
                    Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(),
                    -> append(' ')
                    else -> append(ch)
                }
            }
        }
        return stripped.replace(Regex("\\s+"), " ").trim()
    }

    /** A saved snippet as entered: blank names fall back to the command itself. */
    fun saved(id: String, title: String, command: String): Snippet? {
        val line = typed(command).takeIf { it.isNotEmpty() } ?: return null
        val name = title.trim().ifEmpty { line.take(32) }
        return Snippet(id, name.take(40), line)
    }
}
