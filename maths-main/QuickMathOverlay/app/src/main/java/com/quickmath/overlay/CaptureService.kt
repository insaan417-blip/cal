package com.quickmath.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Foreground service: MediaProjection screen capture -> ML Kit OCR (on-device) ->
 * MathParser -> floating bubble. Nothing leaves the phone.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.quickmath.overlay.action.START"
        const val ACTION_STOP = "com.quickmath.overlay.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "quickmath_monitor"
        private const val NOTIF_ID = 1001
        private const val MAX_SIDE = 1920 // capture is scaled down if the screen is bigger
        private const val MISS_RESET = 2  // frames without any question before a repeat counts as new
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: AppPrefs

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    private var overlay: OverlayBubble? = null
    private var loopJob: Job? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var active = false
    private var lastDisplayW = 0
    private var lastDisplayH = 0
    private var lastExpression: String? = null
    private var missStreak = 0

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        val channel = NotificationChannel(CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring(intent)
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        MonitorState.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun startMonitoring(intent: Intent) {
        // Android 14+: the foreground service (type mediaProjection) must be running
        // BEFORE the MediaProjection is created.
        enterForeground()
        teardown()

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = readProjectionData(intent)
        if (code != Activity.RESULT_OK || data == null) {
            MonitorState.setMessage("Screen capture was not allowed.")
            stopMonitoring()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            manager.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            MonitorState.setMessage("Could not start screen capture. Try again.")
            stopMonitoring()
            return
        }

        projection = proj
        // Android 14+: a callback must be registered before createVirtualDisplay().
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { if (active) stopMonitoring() }
            }
        }
        projectionCallback = cb
        proj.registerCallback(cb, mainHandler)

        active = true
        try {
            configureCapture()
        } catch (e: Exception) {
            MonitorState.setMessage("Capture failed: " + (e.message ?: e.javaClass.simpleName))
            stopMonitoring()
            return
        }

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        overlay = OverlayBubble(this, prefs).also { it.show() }
        registerPrefListener()
        MonitorState.start()
        loopJob = scope.launch { loop() }
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun enterForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent, flags)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickMath Overlay")
            .setContentText("Watching the screen for arithmetic questions")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private fun registerPrefListener() {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { overlay?.applyStyle() }
        }
        prefListener = l // keep a strong reference, SharedPreferences only holds a weak one
        prefs.sp.registerOnSharedPreferenceChangeListener(l)
    }

    // ------------------------------------------------------------- stopping

    private fun stopMonitoring() {
        teardown()
        MonitorState.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        active = false
        loopJob?.cancel()
        loopJob = null
        prefListener?.let { prefs.sp.unregisterOnSharedPreferenceChangeListener(it) }
        prefListener = null
        try { virtualDisplay?.release() } catch (e: Exception) { /* ignore */ }
        virtualDisplay = null
        try { imageReader?.close() } catch (e: Exception) { /* ignore */ }
        imageReader = null
        val p = projection
        val cb = projectionCallback
        if (p != null) {
            try { if (cb != null) p.unregisterCallback(cb) } catch (e: Exception) { /* ignore */ }
            try { p.stop() } catch (e: Exception) { /* ignore */ }
        }
        projection = null
        projectionCallback = null
        overlay?.remove()
        overlay = null
        try { recognizer?.close() } catch (e: Exception) { /* ignore */ }
        recognizer = null
        lastExpression = null
        missStreak = 0
        lastDisplayW = 0
        lastDisplayH = 0
    }

    // -------------------------------------------------------------- capture

    @Suppress("DEPRECATION")
    private fun readDisplay(): IntArray {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        display.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    /** Creates (or, after rotation, resizes) the virtual display and its ImageReader. */
    private fun configureCapture() {
        val proj = projection ?: return
        val (w, h, dpi) = readDisplay()
        val longSide = maxOf(w, h)
        val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        val cw = maxOf(2, (w * scale).toInt())
        val ch = maxOf(2, (h * scale).toInt())

        val reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        val existing = virtualDisplay
        if (existing == null) {
            virtualDisplay = proj.createVirtualDisplay(
                "QuickMathCapture", cw, ch, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } else {
            existing.resize(cw, ch, dpi)
            existing.surface = reader.surface
        }
        val old = imageReader
        imageReader = reader
        try { old?.close() } catch (e: Exception) { /* ignore */ }
        lastDisplayW = w
        lastDisplayH = h
    }

    /** Newest frame as a Bitmap, or null when the screen has not changed since the last grab. */
    private fun grabBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) return padded
            val exact = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            return exact
        } finally {
            image.close()
        }
    }

    // ----------------------------------------------------------- OCR loop

    private suspend fun loop() {
        while (active) {
            val started = SystemClock.elapsedRealtime()
            try {
                processOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) MonitorState.setOcr("Error: " + e.javaClass.simpleName)
            }
            val wait = prefs.intervalMs - (SystemClock.elapsedRealtime() - started)
            delay(maxOf(30L, wait))
        }
    }

    private suspend fun processOnce() {
        // Screen rotated or resized? Rebuild the capture surface.
        val d = readDisplay()
        if (d[0] != lastDisplayW || d[1] != lastDisplayH) configureCapture()

        val t0 = SystemClock.elapsedRealtime()
        val bitmap = grabBitmap() ?: return // no new frame = nothing changed = nothing to do

        // Fix.buildLines needs the bitmap, so it is recycled only after the lines are built.
        // It re-reads the + - x / signs from the pixels (ML Kit often reports the division
        // sign as "+") and never throws.
        val lines: List<OcrLine> = try {
            val text = recognize(bitmap)
            Fix.buildLines(text, bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (active) MonitorState.setOcr("OCR error")
            return
        } finally {
            bitmap.recycle()
        }

        val result = MathParser.scan(lines, prefs.allowedOps())
        val found = result.detection
        if (!active) return

        if (found == null) {
            if (result.rejected > 0) {
                missStreak = 0
                MonitorState.skippedUnclear()
            } else {
                missStreak++
                // Question gone for a moment: the same question showing up again counts as new.
                if (missStreak >= MISS_RESET) lastExpression = null
                MonitorState.frameScanned("No question on screen")
            }
            return
        }

        missStreak = 0
        if (found.expression == lastExpression) {
            MonitorState.frameScanned("Same question")
            return
        }

        lastExpression = found.expression
        val ms = SystemClock.elapsedRealtime() - t0
        mainHandler.post { overlay?.setAnswer(found.answer) }
        if (prefs.haptic) vibrate()
        MonitorState.frameScanned("Question found")
        MonitorState.solved(found.expression, found.answer, ms)
    }

    private suspend fun recognize(bitmap: Bitmap): Text {
        val rec = recognizer ?: throw IllegalStateException("OCR not ready")
        return suspendCancellableCoroutine<Text> { cont ->
            rec.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            // haptics are optional
        }
    }
}
