package com.mirrorsync.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

// Streams the phone screen as a raw H264 Annex-B byte stream over TCP.
// On the PC, view it with:  ffplay -fflags nobuffer -flags low_delay -i tcp://<PHONE_IP>:5001
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    companion object {
        const val PORT = 5001
        const val CHANNEL_ID = "mirrorsync_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity_RESULT_CANCELED()) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        executor.execute { runServer() }
        return START_STICKY
    }

    private fun Activity_RESULT_CANCELED() = 0

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MirrorSync", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrorSync")
            .setContentText("Screen mirror server running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(2001, notification)
    }

    private fun runServer() {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        try {
            serverSocket = ServerSocket(PORT)
            running = true
            while (running) {
                val client = serverSocket!!.accept()
                handleClient(client, width, height, density)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleClient(client: Socket, width: Int, height: Int, density: Int) {
        val out = client.getOutputStream()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirrorSync",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        try {
            val bufferInfo = MediaCodec.BufferInfo()
            while (running) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    val encodedData: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    encodedData.get(chunk)
                    out.write(chunk)
                    out.flush()
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { codec.stop(); codec.release() } catch (_: Exception) {}
            virtualDisplay?.release()
            client.close()
        }
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        virtualDisplay?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }
}

