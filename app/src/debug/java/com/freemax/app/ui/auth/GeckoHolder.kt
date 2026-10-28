package com.freemax.app.ui.auth

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime

/** App-wide Gecko runtime. Created once in Application.onCreate so the
 *  Gecko thread and app shell are fully ready before any session exists. */
object GeckoHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun get(context: Context): GeckoRuntime {
        return runtime ?: GeckoRuntime.create(context.applicationContext).also {
            runtime = it
        }
    }

    fun peek(): GeckoRuntime? = runtime
}
