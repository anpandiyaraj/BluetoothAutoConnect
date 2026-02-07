package com.anpan.bluetoothauto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BluetoothStateReceiver : BroadcastReceiver() {

    private val PERMISSION_CHANNEL_ID = "PermissionRequestChannel"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (bluetoothAdapter?.isEnabled == true && hasRequiredPermissions(context)) {
                    Log.d("BluetoothStateReceiver", "Device booted and Bluetooth is ON. Starting service.")
                    startService(context)
                } else {
                    Log.d("BluetoothStateReceiver", "Device booted, but Bluetooth is OFF or permissions are missing.")
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d("BluetoothStateReceiver", "Bluetooth turned ON")
                        if (hasRequiredPermissions(context)) {
                            startService(context)
                        } else {
                            Log.d("BluetoothStateReceiver", "Permissions missing, showing notification.")
                            showPermissionNotification(context)
                        }
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d("BluetoothStateReceiver", "Bluetooth turned OFF")
                    }
                }
            }
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

        return (bluetoothPermissions + notificationPermission).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, BluetoothService::class.java)
        context.startForegroundService(serviceIntent)
    }

    private fun showPermissionNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PERMISSION_CHANNEL_ID,
                context.getString(R.string.permission_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for permission request notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0, 
            mainActivityIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PERMISSION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.permission_notification_title))
            .setContentText(context.getString(R.string.permission_notification_content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification) // Use a different ID than the service notification
    }
}
