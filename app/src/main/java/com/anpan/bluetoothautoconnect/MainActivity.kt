package com.anpan.bluetoothautoconnect

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the BluetoothService
        val serviceIntent = Intent(this, BluetoothService::class.java)
        startService(serviceIntent)
        // Finish the activity immediately
        finish()
    }
}