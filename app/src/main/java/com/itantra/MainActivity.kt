package com.itantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.itantra.identity.IdentityManager
import com.itantra.mesh.ITantraMeshManager
import com.itantra.mesh.service.MeshForegroundService
import com.itantra.ui.languages.LanguagePacksScreen
import com.itantra.ui.onboarding.OnboardingScreen
import com.itantra.ui.theme.SurfaceBg
import com.itantra.ui.theme.ITantraTheme
import com.itantra.ui.transceiver.TransceiverScreen

enum class AppScreen {
    ONBOARDING,
    TRANSCEIVER,
    LANGUAGES
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ITantraPermissions"
    }

    private lateinit var identityManager: IdentityManager
    private lateinit var meshManager: ITantraMeshManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.i(TAG, "Runtime permission result: " + permissions.entries.joinToString {
            "${it.key.substringAfterLast('.')}=${it.value}"
        })
        if (hasMeshPermissions()) {
            MeshForegroundService.start(this)
            meshManager.startMesh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        identityManager = IdentityManager.getInstance(this)
        meshManager = ITantraMeshManager.getInstance(this)

        requestRequiredPermissions()

        setContent {
            ITantraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceBg
                ) {
                    var currentScreen by remember {
                        mutableStateOf(
                            if (identityManager.hasIdentity()) AppScreen.TRANSCEIVER else AppScreen.ONBOARDING
                        )
                    }

                    when (currentScreen) {
                        AppScreen.ONBOARDING -> {
                            OnboardingScreen(
                                identityManager = identityManager,
                                onComplete = {
                                    MeshForegroundService.start(this)
                                    meshManager.startMesh()
                                    currentScreen = AppScreen.TRANSCEIVER
                                }
                            )
                        }

                        AppScreen.TRANSCEIVER -> {
                            TransceiverScreen(
                                meshManager = meshManager,
                                identityManager = identityManager,
                                onOpenLanguages = {
                                    currentScreen = AppScreen.LANGUAGES
                                }
                            )
                        }

                        AppScreen.LANGUAGES -> {
                            LanguagePacksScreen(
                                onBack = {
                                    currentScreen = AppScreen.TRANSCEIVER
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (identityManager.hasIdentity() && hasMeshPermissions()) {
            MeshForegroundService.start(this)
            meshManager.startMesh()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = getNeededPermissions()
        if (permissions.isNotEmpty()) {
            Log.i(TAG, "Requesting runtime permissions: ${permissions.joinToString()}")
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            if (identityManager.hasIdentity() && hasMeshPermissions()) {
                MeshForegroundService.start(this)
                meshManager.startMesh()
            }
        }
    }

    private fun getNeededPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        permissions.add(Manifest.permission.RECORD_AUDIO)

        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasMeshPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.i(TAG, "BLE runtime permission check: ${if (missing.isEmpty()) "all granted" else "missing $missing"}")
        return missing.isEmpty()
    }
}
