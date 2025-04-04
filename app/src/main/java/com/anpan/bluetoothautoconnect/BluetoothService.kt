package com.anpan.bluetoothautoconnect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class BluetoothService : Service() {

    private val TAG = "BluetoothService"
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val interval = 5000L // 5 seconds

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!hasPermissions()) {
                requestPermissions()
                return
            }

            val pairedDevices = bluetoothAdapter.bondedDevices
            for (device in pairedDevices) {
                bluetoothAdapter.getProfileProxy(
                    applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            val connectedDevices = proxy.connectedDevices
                            if (!connectedDevices.contains(device)) {
                                Log.d(TAG, "Device not connected: ${device.name}")
                                try {
                                    connectDevice(device)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Connection failed: ${device.name}", e)
                                }
                            }
                            bluetoothAdapter.closeProfileProxy(profile, proxy)
                        }

                        override fun onServiceDisconnected(profile: Int) {}
                    },
                    BluetoothProfile.HEADSET
                )
            }

            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        handler.post(checkRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        return START_STICKY
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundService() {
        val notification = android.app.Notification.Builder(this, "bt_channel")
            .setContentTitle("Bluetooth Auto-Connect")
            .setContentText("Monitoring paired Bluetooth devices")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "bt_channel",
                "Bluetooth Auto-Connect",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectDevice(device: BluetoothDevice) {
        try {
            val method = device.javaClass.getMethod("connect")
            method.invoke(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device: ${device.name}", e)
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val intent = Intent(this, PermissionRequestActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}