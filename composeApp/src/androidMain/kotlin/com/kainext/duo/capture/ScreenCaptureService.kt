package com.kainext.duo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.kainext.duo.shared.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "KaiScreenCapture"

/**
 * Owns a MediaProjection and keeps a VirtualDisplay mirroring the real screen, so screen frames
 * can be read on demand — including while the agent is driving some other app and Kai's own
 * activity is in the background.
 *
 * Why this exists next to the accessibility screenshot API: `AccessibilityService.takeScreenshot`
 * returns a single composited frame, needs the accessibility service to be enabled, and the
 * platform throttles it. A projection is the only way to keep capturing without that service, and
 * the only way to capture at a steady rate for a running UI-automation loop.
 *
 * Cost and honesty about it: showing the system consent dialog is unavoidable (the platform, not
 * the app, decides), and on Android 14+ the projection must be held by a foreground service of
 * type `mediaProjection`, which is why this is a service with a persistent notification rather
 * than a quiet background object. The notification states plainly that the screen is being
 * captured; there is no way to capture without it and there should not be.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    /** The system (or the user, from the status bar) can end the projection at any moment. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped")
            release()
            markActive(false)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                release()
                markActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> start(intent)

            else -> {
                // Restarted by the system with no intent: there is no consent token to reuse, and
                // silently capturing without one is exactly what the platform forbids.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // The foreground service has to be up before the projection exists: Android 14 refuses
        // getMediaProjection from a service that is not already running as mediaProjection.
        val promoted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground failed", e)
            false
        }
        if (!promoted) {
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Log.w(TAG, "no projection token; the consent dialog was not completed")
            stopSelf()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val created = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Throwable) {
            Log.w(TAG, "getMediaProjection failed", e)
            null
        }
        if (created == null) {
            stopSelf()
            return
        }

        // Android 14 rejects createVirtualDisplay on a projection that has no callback
        // registered, and the callback is how a user-initiated stop gets back to us.
        try {
            created.registerCallback(projectionCallback, Handler(mainLooper))
        } catch (e: Throwable) {
            Log.w(TAG, "registerCallback failed", e)
        }
        projection = created
        if (!startVirtualDisplay()) {
            release()
            stopSelf()
            return
        }
        markActive(true)
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(metrics)
        return metrics
    }

    private fun startVirtualDisplay(): Boolean {
        val metrics = screenMetrics()
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        val thread = HandlerThread("kai-screen-capture").also { it.start() }
        captureThread = thread
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = try {
            projection?.createVirtualDisplay(
                "kai-screen-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(thread.looper),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "createVirtualDisplay failed", e)
            null
        }
        return virtualDisplay != null
    }

    /**
     * Latest composited frame, at the display's native resolution.
     *
     * Returns null when no frame arrives inside [timeoutMs] — the caller has to read that as "no
     * image", never as "the screen was blank". Rotation is handled by rebuilding the virtual
     * display when the real screen's size changed, because a display created for the old
     * geometry keeps mirroring into a stale rectangle.
     */
    suspend fun captureFrame(timeoutMs: Long = 2_000): Bitmap? {
        if (!running) return null
        val current = screenMetrics()
        if (current.widthPixels != width || current.heightPixels != height) {
            releaseVirtualDisplay()
            if (!startVirtualDisplay()) return null
        }
        val reader = imageReader ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Throwable) {
                Log.w(TAG, "acquireLatestImage failed", e)
                null
            }
            if (image != null) {
                return try {
                    toBitmap(image)
                } finally {
                    image.close()
                }
            }
            delay(40)
        }
        return null
    }

    private fun toBitmap(image: android.media.Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        // Rows are padded to the hardware's stride, so the buffer is wider than the image. The
        // padding has to be copied and then cropped away; writing the raw buffer straight into a
        // width-sized bitmap shears every row.
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun releaseVirtualDisplay() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
    }

    private fun release() {
        releaseVirtualDisplay()
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        projection = null
    }

    override fun onDestroy() {
        release()
        markActive(false)
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Ends the projection and the notification. Hopped onto the main thread because a Service is
     * not safe to touch from the dispatcher a caller happens to be on.
     */
    internal fun stopCapture() {
        Handler(mainLooper).post {
            release()
            markActive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.screen_capture_channel_description)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
        // PendingIntent needs a real Intent; tapping a notification that cannot open anything is
        // worse than a notification without a tap target, so the intent is only attached when the
        // package actually has a launcher entry.
        if (launch != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.kainext.duo.capture.START"
        const val ACTION_STOP = "com.kainext.duo.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "kai_screen_capture"
        private const val NOTIFICATION_ID = 9002

        private val _active = MutableStateFlow(false)

        /**
         * Live projection state as a flow. The settings switch and anything else that shows
         * "capturing" watches this: a stop the user makes from the system UI changes it too, so
         * the UI cannot keep claiming capture is on after it ended.
         */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        private fun markActive(value: Boolean) {
            running = value
            _active.value = value
        }

        @Volatile
        private var instance: ScreenCaptureService? = null

        /** True while a projection is live and frames are available. */
        @Volatile
        var running: Boolean = false
            private set

        fun current(): ScreenCaptureService? = instance

        /**
         * Stops the capture and drops the notification. Safe to call when nothing is running.
         *
         * Reaches the live instance instead of starting the service with a stop action: from the
         * background the platform refuses that start, which used to leave a projection running
         * while every reader had already been told it stopped.
         */
        fun stop(context: Context) {
            val live = instance
            if (live != null) {
                live.stopCapture()
                return
            }
            markActive(false)
            runCatching {
                context.startService(
                    Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
