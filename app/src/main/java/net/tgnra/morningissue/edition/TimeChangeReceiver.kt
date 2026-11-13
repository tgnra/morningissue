package net.tgnra.morningissue.edition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import net.tgnra.morningissue.appContainer

/**
 * The device's time zone changed: when the account follows the phone's time zone, the server
 * is told (`PATCH /settings`), so editions are released at the reader's local times. A clock
 * change needs nothing any more: the server keeps the schedule.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val container = context.appContainer
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.settings.followDeviceTimeZone()
            } finally {
                pending.finish()
            }
        }
    }
}
