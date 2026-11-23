package com.pdm.app.data.system

import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.RemoteNode

/**
 * What the app shows outside itself: the Quick Settings tile, the home-screen widget and the
 * device controls. Counts and guest names only; never a host, account, ticket or token.
 * [guests] stays in memory; only the counts are saved for the widget (see [GlanceStore]).
 */
data class FleetGlance(
    val signedIn: Boolean = false,
    /** The app is open on live data (not cached, not a demo). */
    val live: Boolean = false,
    val demo: Boolean = false,
    val remotes: Int = 0,
    val guestsRunning: Int = 0,
    val guests: Int = 0,
    val nodesOnline: Int = 0,
    val nodes: Int = 0,
    val faults: Int = 0,
    val runningTasks: Int = 0,
    /** Wall-clock ms of the data shown; 0 when there is none. */
    val at: Long = 0L,
    val guestList: List<GlanceGuest> = emptyList(),
) {
    /** One line for the tile subtitle and the widget status row. */
    val status: String
        get() = when {
            !signedIn -> "Signed out"
            demo -> "Demo"
            faults > 0 -> "$faults fault${if (faults == 1) "" else "s"}"
            live -> "All clear"
            else -> "Not live"
        }

    /** Counts only, for what is saved to disk. */
    fun withoutGuests(): FleetGlance = copy(guestList = emptyList())

    companion object {
        val SIGNED_OUT = FleetGlance()

        /**
         * [canPower] is the write permission the screen itself uses (empty while it shows cached
         * data), so outside surfaces never offer more than the app does.
         */
        fun of(
            signedIn: Boolean,
            live: Boolean,
            demo: Boolean,
            remotes: List<RemoteNode>,
            guests: List<Guest>,
            nodesOnline: Int,
            nodes: Int,
            runningTasks: Int,
            writable: Permissions?,
            at: Long,
        ): FleetGlance {
            if (!signedIn) return SIGNED_OUT
            val faults = remotes.count { !it.error.isNullOrBlank() || it.health.equals("Error", true) }
            val list = guests.filter { it.type == "qemu" || it.type == "lxc" }.map { guest ->
                GlanceGuest(
                    id = guest.id,
                    name = guest.name.ifBlank { "${guest.type} ${guest.vmid}" },
                    remoteId = guest.remoteId,
                    type = guest.type,
                    vmid = guest.vmid,
                    status = guest.status,
                    // Unknown permissions leave controls visible in the app; outside it, only a known yes counts.
                    canPower = live && writable?.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath(guest.remoteId, guest.vmid)) == true,
                )
            }
            return FleetGlance(
                signedIn = true,
                live = live,
                demo = demo,
                remotes = remotes.size,
                guestsRunning = guests.count { it.status == "running" },
                guests = guests.size,
                nodesOnline = nodesOnline,
                nodes = nodes,
                faults = faults,
                runningTasks = runningTasks,
                at = at,
                guestList = list,
            )
        }
    }
}

data class GlanceGuest(
    val id: String,
    val name: String,
    val remoteId: String,
    val type: String,
    val vmid: Int,
    val status: String,
    val canPower: Boolean,
) {
    val running: Boolean get() = status == "running"
}
