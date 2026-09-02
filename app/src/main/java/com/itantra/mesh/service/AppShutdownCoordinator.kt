package com.itantra.mesh.service

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import com.itantra.mesh.transport.MeshService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates a clean application shutdown for iTantra.
 */
object AppShutdownCoordinator {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val shutdownToken = AtomicLong(0L)
    @Volatile
    private var shutdownJob: Job? = null

    fun cancelPendingShutdown() {
        shutdownToken.incrementAndGet()
        shutdownJob?.cancel()
        shutdownJob = null
    }

    fun requestFullShutdownAndKill(
        app: Application,
        mesh: MeshService?,
        notificationManager: NotificationManagerCompat,
        stopForeground: () -> Unit,
        stopService: () -> Unit
    ) {
        val token = shutdownToken.incrementAndGet()
        shutdownJob?.cancel()
        val job = scope.launch {
            try {
                val intent = android.content.Intent(com.itantra.util.AppConstants.UI.ACTION_FORCE_FINISH)
                    .setPackage(app.packageName)
                app.sendBroadcast(intent, com.itantra.util.AppConstants.UI.PERMISSION_FORCE_FINISH)
            } catch (_: Exception) { }

            // Stop mesh (best-effort)
            try { mesh?.stopServices() } catch (_: Exception) { }
            try { com.itantra.mesh.transport.PowerManager.getInstance(app).shutdown() } catch (_: Exception) { }

            // Stop foreground and clear notification
            try { stopForeground() } catch (_: Exception) { }
            try { notificationManager.cancel(10001) } catch (_: Exception) { }

            delay(100)

            // Stop the service itself
            if (!isActive || shutdownToken.get() != token) return@launch
            try { stopService() } catch (_: Exception) { }
        }
        shutdownJob = job
    }
}
