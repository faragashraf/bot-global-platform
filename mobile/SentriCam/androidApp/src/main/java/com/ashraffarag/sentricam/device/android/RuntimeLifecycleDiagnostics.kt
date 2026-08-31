package com.ashraffarag.sentricam.device.android

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Development correlation only; it never controls service ownership. */
object RuntimeLifecycleDiagnostics : Application.ActivityLifecycleCallbacks {
    @Volatile var appState: String = "background"
        private set
    @Volatile var serviceState: String = "not_created"
        private set
    private var startedActivities = 0

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    fun service(state: String) {
        serviceState = state
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        appState = "foreground"
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) appState = "background"
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
