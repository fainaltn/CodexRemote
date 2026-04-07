package dev.codexremote.android

import android.app.Application

/**
 * Application-level setup for CodexRemote.
 * Initializes singletons (ApiClient, ServerRepository) that screens depend on.
 */
class CodexRemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: initialize crash reporting, analytics, or DI framework.
        // Phase 0 keeps it thin — dependencies are constructed lazily where needed.
    }
}
