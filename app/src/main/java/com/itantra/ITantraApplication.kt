package com.itantra

import android.app.Application
import android.util.Log
import com.itantra.conversation.ConversationRepository
import com.itantra.conversation.VoiceEngines
import com.itantra.identity.IdentityManager
import com.itantra.mesh.service.MeshForegroundService
import com.itantra.mesh.service.MeshServicePreferences
import com.itantra.mesh.transport.PowerManager

/**
 * Main application class for iTantra.
 *
 * Responsibilities:
 * - Initializes process-wide power policy for BLE mesh operations.
 * - Pre-warms the local cryptographic identity.
 * - Starts the conversation layer, so messages are kept (and alerts spoken) with no
 *   screen open.
 * - Starts the persistent foreground service to maintain offline mesh in background.
 */
class ITantraApplication : Application() {

    companion object {
        private const val TAG = "ITantraApplication"
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "Initializing iTantra Application...")

        // Must be ready before MeshForegroundService.start() reads its settings.
        MeshServicePreferences.init(this)

        // Start process-wide power manager policy for BLE mesh
        try {
            PowerManager.getInstance(this).start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PowerManager: ${e.message}", e)
        }

        // Initialize local cryptographic identity
        try {
            IdentityManager.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize IdentityManager: ${e.message}", e)
        }

        // Conversations are process-wide: they must be listening before any screen is.
        try {
            VoiceEngines.getInstance(this).registerTrimCallbacks(this)
            ConversationRepository.getInstance(this).start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start conversations: ${e.message}", e)
        }

        // Start foreground service to keep mesh alive
        try {
            MeshForegroundService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MeshForegroundService: ${e.message}", e)
        }

        Log.i(TAG, "iTantra Application initialized successfully")
    }
}
