package com.pdm.app.system

import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.ToggleTemplate
import androidx.annotation.RequiresApi
import com.pdm.app.PdmApp
import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.system.GlanceGuest
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Android 11+ device controls: each guest the signed-in user may power is a toggle. On starts it
 * (as the app's Start does); off asks the system to confirm, then sends Shutdown (never a hard
 * Stop). Every action goes through the open app's own power path; with the app closed or showing
 * cached data, controls only open it. Android asks for the screen lock before any control acts.
 */
@RequiresApi(Build.VERSION_CODES.R)
class FleetControlsService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val surfaces get() = (application as PdmApp).surfaces

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        val glance = surfaces.glance.value
        val controls = glance.guestList.filter { it.canPower }.map { guest ->
            Control.StatelessBuilder(guest.id, LaunchAction.pending(this, LaunchAction.GUEST, guest.id))
                .setTitle(guest.name)
                .setSubtitle(subtitle(guest))
                .setStructure(STRUCTURE)
                .setZone(guest.remoteId)
                .setDeviceType(DeviceTypes.TYPE_GENERIC_ON_OFF)
                .build()
        }
        return CoroutinePublisher(scope, flowOf(*controls.toTypedArray()))
    }

    override fun createPublisherFor(controlIds: List<String>): Flow.Publisher<Control> {
        val source = surfaces.glance
            .map { glance -> Snapshot(glance.signedIn, glance.live, controlIds.map { id -> glance.guestList.firstOrNull { it.id == id } }) }
            .distinctUntilChanged()
            .transform { snapshot -> controlIds.forEachIndexed { i, id -> emit(stateful(id, snapshot, snapshot.guests[i])) } }
        return CoroutinePublisher(scope, source)
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val guest = surfaces.glance.value.guestList.firstOrNull { it.id == controlId }
        val commands = surfaces.commands
        if (guest == null || commands == null || !guest.canPower || action !is BooleanAction) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        if (!action.newState && action.challengeValue == null) {
            // Off is a shutdown of a live guest: the system asks "are you sure" and sends it again.
            consumer.accept(ControlAction.RESPONSE_CHALLENGE_ACK)
            return
        }
        val power = if (action.newState) GuestAction.START else GuestAction.SHUTDOWN
        consumer.accept(if (commands.power(guest.id, power)) ControlAction.RESPONSE_OK else ControlAction.RESPONSE_FAIL)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class Snapshot(val signedIn: Boolean, val live: Boolean, val guests: List<GlanceGuest?>)

    private fun stateful(id: String, snapshot: Snapshot, guest: GlanceGuest?): Control {
        val builder = Control.StatefulBuilder(id, LaunchAction.pending(this, LaunchAction.GUEST, id))
            .setStructure(STRUCTURE)
            .setDeviceType(DeviceTypes.TYPE_GENERIC_ON_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) builder.setAuthRequired(true)
        if (guest == null) {
            // The id is "remote/type/vmid"; enough for a title while the app is closed.
            val parts = id.split('/')
            builder.setTitle(parts.drop(1).joinToString(" ").ifBlank { id })
                .setSubtitle(parts.firstOrNull().orEmpty())
                .setZone(parts.firstOrNull().orEmpty())
            return if (snapshot.signedIn && snapshot.live) {
                builder.setStatus(Control.STATUS_NOT_FOUND).setStatusText("Not in this fleet").build()
            } else {
                builder.setStatus(Control.STATUS_DISABLED).setStatusText(OPEN_APP).build()
            }
        }
        builder.setTitle(guest.name).setSubtitle(subtitle(guest)).setZone(guest.remoteId).setStatus(Control.STATUS_OK)
        return if (guest.canPower) {
            builder.setStatusText(guest.status)
                .setControlTemplate(ToggleTemplate(id, ControlButton(guest.running, if (guest.running) "Shutdown" else "Start")))
                .build()
        } else {
            builder.setStatusText(if (snapshot.live) "${guest.status} · read only" else "${guest.status} · $OPEN_APP")
                .setControlTemplate(ControlTemplate.getNoTemplateObject())
                .build()
        }
    }

    private fun subtitle(guest: GlanceGuest) = "${guest.remoteId} · ${guest.type} ${guest.vmid}"

    private companion object {
        const val STRUCTURE = "PDM"
        const val OPEN_APP = "open PDM"
    }
}

/**
 * Reactive-streams publisher over a coroutine flow, honouring the subscriber's demand. One per
 * subscription; cancelled with it or with the service.
 */
@RequiresApi(Build.VERSION_CODES.R)
private class CoroutinePublisher<T>(
    private val scope: CoroutineScope,
    private val source: kotlinx.coroutines.flow.Flow<T>,
) : Flow.Publisher<T> {
    override fun subscribe(subscriber: Flow.Subscriber<in T>) {
        val demand = Channel<Long>(Channel.UNLIMITED)
        // Lazy: nothing (not even onComplete) may reach the subscriber before onSubscribe.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var credit = 0L
            try {
                source.collect { item ->
                    while (credit <= 0L) {
                        val more = demand.receive()
                        credit = if (Long.MAX_VALUE - credit < more) Long.MAX_VALUE else credit + more
                    }
                    credit--
                    subscriber.onNext(item)
                }
                subscriber.onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                subscriber.onError(e)
            }
        }
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) {
                if (n > 0) demand.trySend(n)
            }

            override fun cancel() {
                job.cancel()
            }
        })
        job.start()
    }
}
