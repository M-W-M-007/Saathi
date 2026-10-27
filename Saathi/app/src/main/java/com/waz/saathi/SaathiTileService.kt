package com.waz.saathi

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: start listening without leaving whatever app is open.
 * Swipe down, tap, speak.
 */
class SaathiTileService : TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, VoiceService::class.java)
            .setAction(VoiceService.ACTION_LISTEN)

        startForegroundService(intent)

        // Collapsing the shade needs an activity on Android 14+, and there is
        // none to show here, so the shade is simply left for the user to swipe
        // away rather than launching a window they did not ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SILENT, true)
            )
        }
    }
}
