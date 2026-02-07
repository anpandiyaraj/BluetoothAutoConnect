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
import android.bluetooth.BluetoothHeadset
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
    private val NOTIFICATION_ID = 1

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothHfp: BluetoothHeadset? = null
    private var connectionAttemptThread: Thread? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null
    private var retryCount = 0
    private val connectionLock = Object()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    Log.d(TAG, "A2DP Profile Proxy connected")
                    bluetoothA2dp = proxy as BluetoothA2dp
                    startConnectionAttempt()
                }
                BluetoothProfile.HEADSET -> {
                    Log.d(TAG, "HFP Profile Proxy connected")
                    bluetoothHfp = proxy as BluetoothHeadset
                    startConnectionAttempt()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    Log.d(TAG, "A2DP Profile Proxy disconnected")
                    bluetoothA2dp = null
                }
                BluetoothProfile.HEADSET -> {
                    Log.d(TAG, "HFP Profile Proxy disconnected")
                    bluetoothHfp = null
                }
            }
        }
    }

    private val connectionStateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED || action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
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
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
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
        adapter?.getProfileProxy(applicationContext, profileListener, BluetoothProfile.HEADSET)

        val connectionFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        val btStateFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStateReceiver, connectionFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(bluetoothStateReceiver, btStateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(connectionStateReceiver, connectionFilter)
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
        scanRunnable?.let { handler.removeCallbacks(it) }
        if (connectionAttemptThread?.isAlive == true) return
        connectionAttemptThread = Thread { attemptConnection() }
        connectionAttemptThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun getCandidateDevices(bondedDevices: Set<BluetoothDevice>): List<BluetoothDevice> {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()

        return bondedDevices.filter { device ->
            val isA2dpConnected = bluetoothA2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
            val isHfpConnected = bluetoothHfp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
            if (isA2dpConnected || isHfpConnected) return@filter false

            val deviceClass = device.bluetoothClass ?: return@filter false

            when (deviceClass.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> {
                    when (deviceClass.deviceClass) {
                        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                        BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED -> true
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptConnection() {
        if (connectedDevice != null) {
            Log.d(TAG, "Service already managing a connection.")
            return
        }
        if (retryCount >= 3) {
            Log.d(TAG, "Reached max connection attempts.")
            updateNotification(getString(R.string.service_idle))
            return
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.d(TAG, "Bluetooth not enabled.")
            updateNotification(getString(R.string.service_idle))
            return
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        if (bluetoothA2dp == null && bluetoothHfp == null) {
            Log.d(TAG, "Bluetooth proxies not ready.")
            retryCount++
            scheduleRetry()
            return
        }

        val systemA2dpConnected = bluetoothA2dp?.connectedDevices?.isNotEmpty() == true
        val systemHfpConnected = bluetoothHfp?.connectedDevices?.isNotEmpty() == true
        if (systemA2dpConnected || systemHfpConnected) {
            val alreadyConnectedDevice = (bluetoothA2dp?.connectedDevices?.firstOrNull() ?: bluetoothHfp?.connectedDevices?.firstOrNull())!!
            Log.d(TAG, "Device ${alreadyConnectedDevice.name} is already connected.")
            connectedDevice = alreadyConnectedDevice
            updateNotification(getString(R.string.connected_to, alreadyConnectedDevice.name))
            retryCount = 0
            scanRunnable?.let { handler.removeCallbacks(it) }
            return
        }

        val candidateDevices = getCandidateDevices(adapter.bondedDevices)
        if (candidateDevices.isEmpty()) {
            Log.d(TAG, "No unconnected target audio devices found.")
            retryCount++
            scheduleRetry()
            return
        }

        Log.d(TAG, "Connection attempt #${retryCount + 1}. Found ${candidateDevices.size} potential devices.")
        updateNotification(getString(R.string.service_notification_content))

        if (!candidateDevices.any { tryConnect(it) }) {
            Log.d(TAG, "Failed to connect to any candidate devices.")
            retryCount++
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        updateNotification(getString(R.string.service_idle))
        scanRunnable = Runnable { if (connectedDevice == null) startConnectionAttempt() }
        handler.postDelayed(scanRunnable!!, 30000)
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): Boolean {
        if (connectedDevice != null) return false

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, getString(R.string.attempting_to_connect, device.name), Toast.LENGTH_SHORT).show()
        }

        // Try A2DP First
        if (tryProfileConnect(device, bluetoothA2dp, "A2DP")) {
            return true
        }

        // If A2DP fails, try HFP
        if (tryProfileConnect(device, bluetoothHfp, "HFP")) {
            return true
        }

        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryProfileConnect(device: BluetoothDevice, profileProxy: BluetoothProfile?, profileName: String): Boolean {
        if (profileProxy == null) return false

        var isConnected = false
        var connectionInitiated = false
        try {
            Log.d(TAG, "Attempting to connect to ${device.name} via $profileName")
            val connectMethod = profileProxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            connectionInitiated = connectMethod.invoke(profileProxy, device) as? Boolean ?: false

            if (connectionInitiated) {
                synchronized(connectionLock) { connectionLock.wait(20000) }

                val getConnectedDevices = profileProxy.javaClass.getMethod("getConnectedDevices")
                val connectedList = getConnectedDevices.invoke(profileProxy) as? List<*>
                isConnected = connectedList?.contains(device) == true
            }

            if (isConnected) {
                Log.d(TAG, "Successfully connected to ${device.name} via $profileName")
                connectedDevice = device
                updateNotification(getString(R.string.connected_to, device.name))
                Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, getString(R.string.connected_to, device.name), Toast.LENGTH_LONG).show() }
                retryCount = 0
                scanRunnable?.let { handler.removeCallbacks(it) }
            } else {
                Log.d(TAG, "Failed to connect to ${device.name} via $profileName within timeout.")
                if (connectionInitiated) {
                    val disconnectMethod = profileProxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                    disconnectMethod.invoke(profileProxy, device)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during $profileName connection to ${device.name}", e)
        }
        return isConnected
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification)
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.service_notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        scanRunnable?.let { handler.removeCallbacks(it) }
        try {
            unregisterReceiver(connectionStateReceiver)
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) { }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
        adapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHfp)

        connectionAttemptThread?.interrupt()
        Log.d(TAG, "Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? = null
}
