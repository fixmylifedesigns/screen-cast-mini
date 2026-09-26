package com.fixmylife.screencastmini

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Captures the screen, crops it to the overlay box, and answers the monitor's
 * frame requests with JPEGs over BLE.
 */
@SuppressLint("MissingPermission")
class CastService : Service(), BleLink.Listener {

    companion object {
        @Volatile var instance: CastService? = null
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MAC = "mac"
        const val ACTION_STOP = "com.fixmylife.screencastmini.STOP"
        private const val CHANNEL = "cast"
        private const val TAG = "CastService"
    }

    private lateinit var ble: BleLink
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: CropOverlay? = null
    private var captureThread: HandlerThread? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val sending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var latest: Bitmap? = null
    @Volatile private var lastFrameAt = 0L
    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0

    @Volatile private var devW = 0
    @Volatile private var devH = 0
    @Volatile private var devRot = 0
    @Volatile private var devBuf = 0

    @Volatile var status: String = "Idle"
        private set
    val connected: Boolean get() = ::ble.isInitialized && ble.ready

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ble = BleLink(applicationContext, this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification("Casting to mini monitor"))

        intent?.getStringExtra(EXTRA_MAC)?.let { mac ->
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            val device = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            if (device != null) ble.connect(device) else setStatus("Bad device address")
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (projection == null && data != null) startCapture(code, data)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlay?.hide()
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        captureThread?.quitSafely()
        ble.disconnect()
        worker.shutdown()
    }

    // ------------------------------------------------------------- capture

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: run {
            setStatus("Screen capture denied")
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        // Capture at half resolution: plenty for a ~240px screen, much cheaper.
        capW = (screenW / 2).coerceAtLeast(320)
        capH = (screenH / 2).coerceAtLeast(320)

        val thread = HandlerThread("capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ src ->
            val image = src.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt > 80) {
                    lastFrameAt = now
                    val plane = image.planes[0]
                    val buffer: ByteBuffer = plane.buffer
                    val stride = plane.rowStride / plane.pixelStride
                    val bmp = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    latest = if (stride != image.width)
                        Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp
                }
            } catch (e: Exception) {
                Log.w(TAG, "capture failed", e)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = p.createVirtualDisplay(
            "screen-cast-mini", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )

        main.post {
            overlay = CropOverlay(this).also { it.show() }
        }
        setStatus("Capturing")
    }

    fun stopEverything() {
        stopSelf()
    }

    // ----------------------------------------------------------------- BLE

    private fun setStatus(text: String) {
        status = text
        Log.d(TAG, text)
    }

    override fun onStatus(text: String) = setStatus(text)

    override fun onReady() = setStatus("Connected (MTU ${ble.mtu})")

    override fun onDisconnected() {
        devW = 0
        setStatus("Disconnected")
    }

    override fun onCommand(cmd: ByteArray) {
        when (cmd[0].toInt() and 0xFF) {
            0x01 -> if (cmd.size >= 10) {
                val b = ByteBuffer.wrap(cmd, 1, 9).order(ByteOrder.LITTLE_ENDIAN)
                devW = b.short.toInt() and 0xFFFF
                devH = b.short.toInt() and 0xFFFF
                devRot = when (b.get().toInt()) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
                devBuf = b.int
                if (ble.mtu < 300) devBuf = minOf(devBuf, 5120)
                setStatus("Streaming ${devW}x$devH")
                ble.sendCommand(0x01, 1)
            }
            0x03 -> sendFrame()
            0x00 -> {
                ble.sendCommand(0x00, 1)
                setStatus("Screen closed")
            }
            else -> Log.d(TAG, "device cmd ${cmd.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    private fun sendFrame() {
        if (devW <= 0 || devBuf <= 0) return
        if (!sending.compareAndSet(false, true)) return
        worker.execute {
            try {
                val src = latest ?: return@execute
                if (ble.pendingPackets > 0) return@execute
                val jpeg = encode(src) ?: return@execute
                ble.send(jpeg)
            } catch (e: Exception) {
                Log.w(TAG, "encode failed", e)
            } finally {
                sending.set(false)
            }
        }
    }

    /** Crop to the overlay box (screen coords scaled to capture size), then cover-fit the device. */
    private fun encode(src: Bitmap): ByteArray? {
        val box = overlay?.rect()
        var region = src
        if (box != null && screenW > 0) {
            val sx = src.width.toFloat() / screenW
            val sy = src.height.toFloat() / screenH
            val r = Rect(
                (box.left * sx).toInt().coerceIn(0, src.width - 1),
                (box.top * sy).toInt().coerceIn(0, src.height - 1),
                (box.right * sx).toInt().coerceIn(1, src.width),
                (box.bottom * sy).toInt().coerceIn(1, src.height)
            )
            if (r.width() > 8 && r.height() > 8) {
                region = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
            }
        }
        val oriented = if (devRot == 0) region else Bitmap.createBitmap(
            region, 0, 0, region.width, region.height,
            Matrix().apply { postRotate(devRot.toFloat()) }, true
        )

        val out = Bitmap.createBitmap(devW, devH, Bitmap.Config.RGB_565)
        val scale = max(devW / oriented.width.toFloat(), devH / oriented.height.toFloat())
        val place = Matrix().apply {
            setScale(scale, scale)
            postTranslate((devW - oriented.width * scale) / 2f, (devH - oriented.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(oriented, place, Paint(Paint.FILTER_BITMAP_FLAG))

        val limit = devBuf - 100
        var quality = 80
        var bytes: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            if (bytes.size <= limit) return bytes
            quality -= 10
        } while (quality > 0)
        return if (bytes.size <= devBuf) bytes else null
    }

    // -------------------------------------------------------- notification

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Screen Cast Mini")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}
