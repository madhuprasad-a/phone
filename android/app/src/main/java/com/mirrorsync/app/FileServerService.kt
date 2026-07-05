package com.mirrorsync.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// Simple file-transfer server. Files live in the app's external files dir:
//   /storage/emulated/0/Android/data/com.mirrorsync.app/files/
// Protocol (line-based over TCP, one command per connection):
//   LIST                     -> server replies with a JSON array of filenames + sizes
//   GET <filename>           -> server replies with 8-byte size header then raw bytes
//   PUT <filename> <size>    -> server reads <size> raw bytes and saves them
class FileServerService : Service() {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var running = false

    companion object {
        const val PORT = 5002
        const val CHANNEL_ID = "mirrorsync_files_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        executor.execute { runServer() }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MirrorSync Files", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrorSync")
            .setContentText("File server running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()
        startForeground(2002, notification)
    }

    private fun runServer() {
        val dir = getExternalFilesDir(null) ?: filesDir
        dir.mkdirs()
        try {
            serverSocket = ServerSocket(PORT)
            running = true
            while (running) {
                val client = serverSocket!!.accept()
                executor.execute { handleClient(client, dir) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleClient(client: Socket, dir: File) {
        try {
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()
            val line = input.readLine() ?: return
            val parts = line.trim().split(" ")

            when (parts[0]) {
                "LIST" -> {
                    val arr = JSONArray()
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            arr.put(mapOf("name" to f.name, "size" to f.length()).let {
                                org.json.JSONObject(it)
                            })
                        }
                    }
                    val bytes = (arr.toString() + "\n").toByteArray()
                    output.write(bytes)
                    output.flush()
                }
                "GET" -> {
                    val file = File(dir, parts[1])
                    val dataOut = DataOutputStream(output)
                    if (!file.exists()) {
                        dataOut.writeLong(-1L)
                        dataOut.flush()
                    } else {
                        dataOut.writeLong(file.length())
                        file.inputStream().use { it.copyTo(output) }
                        output.flush()
                    }
                }
                "PUT" -> {
                    val name = parts[1]
                    val size = parts[2].toLong()
                    val file = File(dir, name)
                    val rawIn = client.getInputStream()
                    file.outputStream().use { fos ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val n = rawIn.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
        }
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
