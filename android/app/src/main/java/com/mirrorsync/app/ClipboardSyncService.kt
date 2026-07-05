package com.mirrorsync.app

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// NOTE: Android 10+ restricts background clipboard reads to apps that are in the
// foreground or the default IME, so outbound sync (phone -> PC) works reliably
// while the MirrorSync app is in the foreground. Inbound sync (PC -> phone),
// i.e. setting the clipboard, works from the background.
class ClipboardSyncService : Service() {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastClip: String? = null
    private val clients = mutableListOf<Socket>()

    companion object {
        const val PORT = 5003
        const val CHANNEL_ID = "mirrorsync_clip_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        executor.execute { runServer() }
        watchClipboard()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MirrorSync Clipboard", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrorSync")
            .setContentText("Clipboard sync running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        startForeground(2003, notification)
    }

    private fun watchClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (text != null && text != lastClip) {
                    lastClip = text
                    broadcastToClients("TEXT:$text\n")
                }
            }
        }
    }

    private fun broadcastToClients(msg: String) {
        synchronized(clients) {
            clients.removeAll { it.isClosed }
            clients.forEach { sock ->
                try {
                    PrintWriter(sock.getOutputStream(), true).println(msg.trimEnd('\n'))
                } catch (_: Exception) {}
            }
        }
    }

    private fun runServer() {
        try {
            serverSocket = ServerSocket(PORT)
            running = true
            while (running) {
                val client = serverSocket!!.accept()
                synchronized(clients) { clients.add(client) }
                executor.execute { listenToClient(client) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun listenToClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("TEXT:")) {
                    val text = line.removePrefix("TEXT:")
                    mainHandler.post {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        lastClip = text
                        cm.setPrimaryClip(ClipData.newPlainText("MirrorSync", text))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
