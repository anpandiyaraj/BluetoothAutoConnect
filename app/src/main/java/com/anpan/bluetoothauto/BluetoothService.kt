package com.anpan.bluetoothauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat

class BluetoothService : Service() {

    companion object {
        const val ACTION_FINISH_ACTIVITY = "com.anpan.bluetoothauto.FINISH_ACTIVITY"
        const val ACTION_STOP_AND_FINISH = "com.anpan.bluetoothauto.ACTION_STOP_AND_FINISH"
    }

    private val CHANNEL_ID = "BluetoothServiceChannel"
    private val TAG = "BluetoothService"
    private val ACTION_STOP_SERVICE = "com.anpan.bluetoothauto.STOP_SERVICE"
    private val NOTIFICATION_ID = 1

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var connectionAttemptThread: Thread? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null
    private var retryCount = 0
    private val connectionLock = Object()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                Log.d(TAG, "A2DP Profile Proxy connected")
                bluetoothA2dp = proxy as BluetoothA2dp
                startConnectionAttempt()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                Log.d(TAG, "A2DP Profile Proxy disconnected")
                bluetoothA2dp = null
            }
        }
    }

    private val a2dpStateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                synchronized(connectionLock) {
                    connectionLock.notifyAll()
                }

                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                if (device != null && device == connectedDevice) {
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Device ${device.name} has disconnected.")
                        connectedDevice = null
                        updateNotification(getString(R.string.service_idle))
                        startConnectionAttempt()
                    }
                }
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth was turned OFF.")
                        connectionAttemptThread?.interrupt()
                        scanRunnable?.let { handler.removeCallbacks(it) }
                        connectedDevice = null
                        retryCount = 0
                        updateNotification(getString(R.string.service_idle))
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth was turned ON.")
                        retryCount = 0
                        startConnectionAttempt()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        adapter?.getProfileProxy(applicationContext, profileListener, BluetoothProfile.A2DP)

        val a2dpFilter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        val btStateFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a2dpStateReceiver, a2dpFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(bluetoothStateReceiver, btStateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(a2dpStateReceiver, a2dpFilter)
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, btStateFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(getString(R.string.service_notification_content))
        startForeground(NOTIFICATION_ID, notification)

        startConnectionAttempt()
        return START_STICKY
    }

    private fun startConnectionAttempt() {
        scanRunnable?.let { handler.removeCallbacks(it) } // Cancel any pending scans
        if (connectionAttemptThread?.isAlive == true) {
            return // Attempt already in progress
        }
        connectionAttemptThread = Thread {
            attemptConnection()
        }
        connectionAttemptThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun attemptConnection() {
        if (connectedDevice != null) {
            Log.d(TAG, "Service already managing a connection. No new attempt needed.")
            return
        }

        if (retryCount >= 3) {
            Log.d(TAG, "Reached max connection attempts. Halting until Bluetooth is toggled.")
            updateNotification(getString(R.string.service_idle))
            return // Stop trying
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.d(TAG, "Bluetooth not enabled. Waiting for it to be turned on.")
            updateNotification(getString(R.string.service_idle))
            return
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission. Stopping service.")
            stopSelf()
            return
        }

        if (bluetoothA2dp == null) {
            Log.d(TAG, "A2DP proxy not ready yet. Scheduling retry.")
            retryCount++
            scheduleRetry()
            return
        }

        val systemConnectedDevices = bluetoothA2dp?.connectedDevices
        if (systemConnectedDevices?.isNotEmpty() == true) {
            val alreadyConnectedDevice = systemConnectedDevices.first()
            Log.d(TAG, "Device ${alreadyConnectedDevice.name} is already connected. Syncing service state.")
            connectedDevice = alreadyConnectedDevice
            updateNotification(getString(R.string.connected_to, alreadyConnectedDevice.name))
            retryCount = 0 // Reset counter
            scanRunnable?.let { handler.removeCallbacks(it) } // Cancel retries
            return
        }

        val bondedDevices = adapter.bondedDevices
        if (bondedDevices.isEmpty()) {
            Log.d(TAG, "No paired devices found. Scheduling retry.")
            retryCount++
            scheduleRetry()
            return
        }

        Log.d(TAG, "Connection attempt #${retryCount + 1}. Iterating over ${bondedDevices.size} paired devices.")
        updateNotification(getString(R.string.service_notification_content))

        var isDeviceConnected = false
        for (device in bondedDevices) {
            if (tryConnect(device)) {
                isDeviceConnected = true
                break
            }
        }

        if (!isDeviceConnected) {
            Log.d(TAG, "Failed to connect to any paired devices. Scheduling retry.")
            retryCount++
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        updateNotification(getString(R.string.service_idle))
        scanRunnable = Runnable {
            if (connectedDevice == null) {
                startConnectionAttempt()
            }
        }
        handler.postDelayed(scanRunnable!!, 30000) // 30 seconds
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): Boolean {
        if (bluetoothA2dp == null || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        
        if (connectedDevice != null) return true

        if (bluetoothA2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "Device ${device.name} is already connected. Syncing service state.")
            connectedDevice = device
            updateNotification(getString(R.string.connected_to, device.name))
            retryCount = 0
            scanRunnable?.let { handler.removeCallbacks(it) }
            Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, getString(R.string.connected_to, device.name), Toast.LENGTH_LONG).show() }
            return true
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, getString(R.string.attempting_to_connect, device.name), Toast.LENGTH_SHORT).show()
        }

        var isConnected = false
        if (device.bluetoothClass.hasService(BluetoothClass.Service.AUDIO)) {
            Log.d(TAG, "Device ${device.name} supports AUDIO service. Attempting connection.")
            var connectionInitiated = false
            try {
                val connectMethod = bluetoothA2dp?.javaClass?.getMethod("connect", BluetoothDevice::class.java)
                connectionInitiated = connectMethod?.invoke(bluetoothA2dp, device) as? Boolean ?: false

                if (connectionInitiated) {
                    Log.d(TAG, "connect() method returned true for ${device.name}. Waiting for connection state change.")
                    synchronized(connectionLock) {
                        connectionLock.wait(10000) // Wait for 10 seconds for connection
                    }

                    if (bluetoothA2dp?.getConnectedDevices()?.contains(device) == true) {
                        isConnected = true
                    }
                } else {
                    Log.w(TAG, "connect() method returned false for ${device.name}. Cannot initiate connection.")
                }

                if (isConnected) {
                    Log.d(TAG, "Successfully connected to ${device.name}")
                    connectedDevice = device
                    updateNotification(getString(R.string.connected_to, device.name))
                    Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, getString(R.string.connected_to, device.name), Toast.LENGTH_LONG).show() }
                    retryCount = 0 // Reset retry counter on successful connection
                    scanRunnable?.let { handler.removeCallbacks(it) }
                } else {
                    Log.d(TAG, "Failed to connect to ${device.name} within the timeout.")
                    // If connection was initiated but failed/timed out, explicitly disconnect to cancel any pending connection attempt.
                    if (connectionInitiated) {
                        try {
                            val disconnectMethod = bluetoothA2dp?.javaClass?.getMethod("disconnect", BluetoothDevice::class.java)
                            disconnectMethod?.invoke(bluetoothA2dp, device)
                            Log.d(TAG, "Called disconnect on ${device.name} to cancel pending connection.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error trying to cancel connection to ${device.name}", e)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Connection attempt interrupted for ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception while trying to connect to ${device.name}", e)
            }
        } else {
             Log.d(TAG, "Device ${device.name} does not support AUDIO service.")
        }

        return isConnected
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP_AND_FINISH
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val stopPendingIntent = PendingIntent.getActivity(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        scanRunnable?.let { handler.removeCallbacks(it) }
        
        try {
            unregisterReceiver(a2dpStateReceiver)
        } catch (e: IllegalArgumentException) {
            // In case it was already unregistered
        }

        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            // In case it was already unregistered
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        adapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)

        if (connectedDevice != null && bluetoothA2dp != null) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val disconnectMethod = bluetoothA2dp?.javaClass?.getMethod("disconnect", BluetoothDevice::class.java)
                    disconnectMethod?.invoke(bluetoothA2dp, connectedDevice)
                    Log.d(TAG, "Disconnected from ${connectedDevice?.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error while disconnecting", e)
                }
            }
        }
        connectionAttemptThread?.interrupt()
        Log.d(TAG, "Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}
