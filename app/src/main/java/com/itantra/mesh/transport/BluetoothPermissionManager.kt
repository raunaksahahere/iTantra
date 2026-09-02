package com.itantra.mesh.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Handles all Bluetooth permission checking logic
 */
class BluetoothPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothPermissionManager"
    }
    
    /**
     * Check if all required Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }

        val states = permissions.associateWith {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.i(TAG, "BLE permission state (api=${Build.VERSION.SDK_INT}): " +
            states.entries.joinToString { "${it.key.substringAfterLast('.') }=${it.value}" })
        return states.values.all { it }
    }
}
