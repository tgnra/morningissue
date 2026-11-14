package net.tgnra.morningissue.edition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.tgnra.morningissue.appContainer

/** Reschedules the early build when the device's clock or time zone changes. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                context.appContainer.editionScheduler.scheduleEarlyBuild()
        }
    }
}
