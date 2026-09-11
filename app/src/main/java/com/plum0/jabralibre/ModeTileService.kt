package com.plum0.jabralibre

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap cycles ANC -> HearThrough -> Off.
 *
 * The point of the tile is the case the earbud button cannot cover — wearing
 * only the right bud. It works on the lock screen too, because switching a
 * mode never needs an activity.
 */
class ModeTileService : TileService() {

    private val listener: () -> Unit = { updateTile() }

    override fun onStartListening() {
        super.onStartListening()
        JabraManager.init(this)
        JabraManager.addListener(listener)
        JabraManager.refreshAvailability(this)
        updateTile()
    }

    override fun onStopListening() {
        JabraManager.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        JabraManager.init(this)
        if (!JabraManager.hasPermission(this)) { openApp(); return }
        // Connects first if the link is down; the mode is applied once it is up.
        JabraManager.cycleMode(this)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val mode = JabraManager.mode
        tile.icon = Icon.createWithResource(this, iconFor(mode))
        tile.label = getString(
            when (mode) {
                AncMode.ANC -> R.string.mode_anc
                AncMode.HEARTHROUGH -> R.string.mode_ht
                AncMode.OFF -> R.string.mode_off
                null -> R.string.tile_label
            }
        )
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !JabraManager.hasPermission(this) -> getString(R.string.state_no_permission)
                !JabraManager.isBluetoothOn(this) -> getString(R.string.state_bt_off)
                JabraManager.state == ConnState.CONNECTING -> getString(R.string.state_connecting)
                JabraManager.state == ConnState.UNAVAILABLE -> getString(R.string.state_unavailable)
                JabraManager.state == ConnState.FAILED -> getString(R.string.state_failed)
                JabraManager.isConnected -> getString(R.string.state_connected)
                else -> getString(R.string.tile_unknown)
            }
        }
        // greyed out when there is nothing to talk to — the compact tile form
        // shows no subtitle, so the state itself has to carry that information
        tile.state = when {
            !JabraManager.hasPermission(this) ||
                !JabraManager.isBluetoothOn(this) ||
                JabraManager.state == ConnState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            mode != null && mode != AncMode.OFF -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun iconFor(mode: AncMode?) = when (mode) {
        AncMode.HEARTHROUGH -> R.drawable.ic_mode_ht
        AncMode.OFF -> R.drawable.ic_mode_off
        else -> R.drawable.ic_mode_anc
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
