package com.pdm.app.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.pdm.app.MainActivity
import com.pdm.app.R

/**
 * Where an outside entry point (launcher shortcut, tile, widget, device control) asks the app to
 * go. Carried as the intent action, so Samsung Routines and other launchers can fire a shortcut.
 * Applied once the dashboard is signed in; signed out, the app just opens at the login.
 */
sealed class LaunchAction {
    data object Refresh : LaunchAction()
    data object Tasks : LaunchAction()
    data object Updates : LaunchAction()
    data object Faults : LaunchAction()
    data class Guest(val id: String) : LaunchAction()

    companion object {
        const val REFRESH = "com.pdm.app.action.REFRESH"
        const val TASKS = "com.pdm.app.action.TASKS"
        const val UPDATES = "com.pdm.app.action.UPDATES"
        const val FAULTS = "com.pdm.app.action.FAULTS"
        const val GUEST = "com.pdm.app.action.GUEST"
        const val EXTRA_GUEST = "guest"

        fun parse(action: String?, guestId: String?): LaunchAction? = when (action) {
            REFRESH -> Refresh
            TASKS -> Tasks
            UPDATES -> Updates
            FAULTS -> Faults
            GUEST -> guestId?.takeIf { it.isNotBlank() }?.let(::Guest)
            else -> null
        }

        fun from(intent: Intent?): LaunchAction? =
            intent?.let { parse(it.action, it.getStringExtra(EXTRA_GUEST)) }

        fun intent(context: Context, action: String? = null, guestId: String? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                this.action = action ?: Intent.ACTION_MAIN
                guestId?.let { putExtra(EXTRA_GUEST, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** Distinct request codes keep one PendingIntent per destination. */
        fun pending(context: Context, action: String? = null, guestId: String? = null): PendingIntent =
            PendingIntent.getActivity(
                context,
                (action + guestId).hashCode(),
                intent(context, action, guestId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /**
         * Long-press launcher shortcuts. Dynamic, not static: Android clears the task before a static
         * shortcut opens, which would drop the in-memory session.
         */
        fun publishShortcuts(context: Context) {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            fun shortcut(id: String, label: String, long: String, action: String, rank: Int) =
                ShortcutInfo.Builder(context, id)
                    .setShortLabel(label)
                    .setLongLabel(long)
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(intent(context, action))
                    .setRank(rank)
                    .build()
            val list = listOf(
                shortcut("refresh", "Refresh", "Refresh the fleet", REFRESH, 0),
                shortcut("tasks", "Tasks", "Open tasks", TASKS, 1),
                shortcut("updates", "Updates", "Open updates", UPDATES, 2),
                shortcut("faults", "Faults", "Open faults", FAULTS, 3),
            ).take(manager.maxShortcutCountPerActivity.coerceAtLeast(1))
            runCatching { manager.setDynamicShortcuts(list) }
        }
    }
}
