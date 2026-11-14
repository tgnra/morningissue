package net.tgnra.morningissue.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the app is in the foreground: at least one of its activities is started. AICore only
 * serves an app in the foreground, so the rater checks this before every call. Registered by
 * `MorningIssueApp`; counts from the moment it is.
 */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger()

    val isForeground: Boolean get() = started.get() > 0

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
