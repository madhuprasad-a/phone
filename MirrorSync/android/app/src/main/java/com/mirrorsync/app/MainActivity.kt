package com.mirrorsync.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.Manifest
import android.os.Build

class MainActivity : AppCompatActivity() {

    private lateinit var tvIp: TextView
    private lateinit var tvStatus: TextView
    private val CAPTURE_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvIp = findViewById(R.id.tvIp)
        tvStatus = findViewById(R.id.tvStatus)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        tvIp.text = "Device IP (use this on PC): $ip"

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        findViewById<Button>(R.id.btnMirror).setOnClickListener {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), CAPTURE_REQUEST_CODE)
        }

        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            startForegroundService(Intent(this, FileServerService::class.java))
            tvStatus.text = "Status: File server running on port 5002"
        }

        findViewById<Button>(R.id.btnClipboard).setOnClickListener {
            startForegroundService(Intent(this, ClipboardSyncService::class.java))
            tvStatus.text = "Status: Clipboard sync running on port 5003"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startForegroundService(serviceIntent)
            tvStatus.text = "Status: Mirror server running on port 5001"
        }
    }
}
