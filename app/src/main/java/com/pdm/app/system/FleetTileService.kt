package com.pdm.app.system

import android.annotation.SuppressLint
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pdm.app.PdmApp
import com.pdm.app.R

/**
 * Quick Settings tile: fleet state in the subtitle, active while the app is live. Tapping opens
 * the app (on faults when there are any). It never signs in or sends anything by itself.
 */
class FleetTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val glance = (application as PdmApp).surfaces.glance.value
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_fleet)
        tile.state = if (glance.live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val sub = glance.status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = sub
        tile.contentDescription = "${getString(R.string.tile_label)}, $sub"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val glance = (application as PdmApp).surfaces.glance.value
        val action = if (glance.signedIn && glance.faults > 0) LaunchAction.FAULTS else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(LaunchAction.pending(this, action))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(LaunchAction.intent(this, action))
        }
    }
}
