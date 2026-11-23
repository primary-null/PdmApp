package com.pdm.app.system

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.pdm.app.PdmApp
import com.pdm.app.R
import com.pdm.app.data.system.FleetGlance

/**
 * Home-screen widget: the dashboard's counts on an OLED plate. Counts and a time only (the saved
 * copy has no host or names). Tap opens the app; the refresh button opens it and loads at once.
 */
class FleetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val glance = (context.applicationContext as PdmApp).surfaces.glance.value
        ids.forEach { manager.updateAppWidget(it, views(context, glance)) }
    }

    companion object {
        fun updateAll(context: Context, glance: FleetGlance) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, FleetWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context, glance)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun views(context: Context, glance: FleetGlance): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_fleet).apply {
                setTextViewText(R.id.widget_status, glance.status.uppercase())
                setTextColor(R.id.widget_status, context.getColor(statusColor(glance)))
                if (glance.signedIn) {
                    setTextViewText(R.id.widget_guests, "${glance.guestsRunning}/${glance.guests}")
                    setTextViewText(R.id.widget_nodes, "${glance.nodesOnline}/${glance.nodes}")
                    setTextViewText(R.id.widget_remotes, glance.remotes.toString())
                    setTextViewText(R.id.widget_tasks, glance.runningTasks.toString())
                } else {
                    listOf(R.id.widget_guests, R.id.widget_nodes, R.id.widget_remotes, R.id.widget_tasks)
                        .forEach { setTextViewText(it, "--") }
                }
                setTextViewText(R.id.widget_time, footer(context, glance))
                setOnClickPendingIntent(R.id.widget_root, LaunchAction.pending(context))
                setOnClickPendingIntent(R.id.widget_refresh, LaunchAction.pending(context, LaunchAction.REFRESH))
            }

        private fun statusColor(glance: FleetGlance): Int = when {
            !glance.signedIn -> R.color.widget_mute
            glance.faults > 0 -> R.color.widget_bad
            glance.live -> R.color.widget_good
            else -> R.color.widget_mute
        }

        private fun footer(context: Context, glance: FleetGlance): String = when {
            !glance.signedIn -> "Open PDM to sign in"
            glance.at <= 0L -> if (glance.live) "LIVE" else "No data yet"
            else -> {
                val time = DateFormat.getTimeFormat(context).format(java.util.Date(glance.at))
                if (glance.live) "LIVE · $time" else "As of $time"
            }
        }
    }
}
