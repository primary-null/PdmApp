package com.pdm.app.data.system

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.TileService
import androidx.core.content.edit
import com.pdm.app.data.model.GuestAction
import com.pdm.app.system.FleetTileService
import com.pdm.app.system.FleetWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an outside surface may ask the open app to do. Set while the fleet screen exists. */
interface FleetCommands {
    fun refresh()

    /** Same path as the in-app buttons (receipts, permissions, read-only checks). False: refused. */
    fun power(guestId: String, action: GuestAction): Boolean
}

/**
 * The bridge between the app and Android's own surfaces (tile, widget, device controls). The view
 * model publishes a [FleetGlance]; the surfaces read it and send [FleetCommands] back. Nothing
 * here can sign in or reach the server by itself: with the app closed the surfaces show the last
 * saved counts and open the app when tapped.
 */
class SystemSurfaces(private val context: Context) {
    private val store = GlanceStore(context.getSharedPreferences(GlanceStore.NAME, Context.MODE_PRIVATE))
    private val _glance = MutableStateFlow(store.load())
    val glance: StateFlow<FleetGlance> = _glance.asStateFlow()

    @Volatile
    var commands: FleetCommands? = null

    fun publish(next: FleetGlance) {
        val previous = _glance.value
        if (next == previous) return
        _glance.value = next
        // Disk and the launcher only hear about changes a person would see: counts, state, the minute.
        if (GlanceStore.visible(next) != GlanceStore.visible(previous)) {
            store.save(next)
            notifySurfaces(next)
        }
    }

    /** Clean Slate: blank the launcher's copy before the data goes. */
    fun reset() {
        _glance.value = FleetGlance.SIGNED_OUT
        store.save(FleetGlance.SIGNED_OUT)
        notifySurfaces(FleetGlance.SIGNED_OUT)
    }

    private fun notifySurfaces(glance: FleetGlance) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, FleetTileService::class.java)) }
        runCatching { FleetWidget.updateAll(context, glance) }
    }
}

/** The widget's copy: counts and a time in plain prefs. Never a host, account or guest name. */
internal class GlanceStore(private val prefs: SharedPreferences) {
    fun load(): FleetGlance {
        if (!prefs.getBoolean("signed_in", false)) return FleetGlance.SIGNED_OUT
        return FleetGlance(
            signedIn = true,
            live = false,
            demo = prefs.getBoolean("demo", false),
            remotes = prefs.getInt("remotes", 0),
            guestsRunning = prefs.getInt("guests_running", 0),
            guests = prefs.getInt("guests", 0),
            nodesOnline = prefs.getInt("nodes_online", 0),
            nodes = prefs.getInt("nodes", 0),
            faults = prefs.getInt("faults", 0),
            runningTasks = prefs.getInt("running_tasks", 0),
            at = prefs.getLong("at", 0L),
        )
    }

    fun save(glance: FleetGlance) {
        prefs.edit {
            putBoolean("signed_in", glance.signedIn)
            putBoolean("demo", glance.demo)
            putInt("remotes", glance.remotes)
            putInt("guests_running", glance.guestsRunning)
            putInt("guests", glance.guests)
            putInt("nodes_online", glance.nodesOnline)
            putInt("nodes", glance.nodes)
            putInt("faults", glance.faults)
            putInt("running_tasks", glance.runningTasks)
            putLong("at", glance.at)
        }
    }

    companion object {
        const val NAME = "pdm_glance"

        /** The part of a glance the tile and widget draw; the time to the minute. */
        fun visible(glance: FleetGlance): FleetGlance = glance.withoutGuests().copy(at = glance.at / 60_000L)
    }
}
