package com.kainext.duo.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log

private const val TAG = "KaiScreenCapture"

/**
 * Starts and stops the screen projection, and answers "can I capture right now?" without touching
 * a real frame.
 *
 * The consent dialog can only be shown by an activity, and the app has exactly one activity, so
 * the launcher is handed in from there once at startup instead of being looked up per call. That
 * keeps every caller (settings UI, the agent's screenshot action) free of Android activity
 * plumbing: they ask [request] and read [isActive].
 */
object ScreenCaptureController {

    private var launch: ((Intent) -> Unit)? = null

    /** Installed by the activity; see [install]. */
    fun install(launcher: (Intent) -> Unit) {
        launch = launcher
    }

    /** Drops the launcher when the activity goes away, so a dead activity is never launched into. */
    fun uninstall() {
        launch = null
    }

    /**
     * MediaProjection exists since API 21 and this app installs on 26 and up, so every supported
     * device can capture. Only the foreground-service type is newer (API 29), and the service
     * branches on that itself.
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /** True while frames are actually available — not merely "the user once said yes". */
    fun isActive(): Boolean = ScreenCaptureService.running

    /**
     * Shows the system consent dialog. Returns false when there is nothing to show it from (the
     * app is not in the foreground) or when the platform is too old, so the caller can report a
     * reason instead of hanging.
     */
    fun request(context: Context): Boolean {
        if (!isSupported()) return false
        val launcher = launch ?: return false
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return false
        val consent = try {
            manager.createScreenCaptureIntent()
        } catch (e: Throwable) {
            Log.w(TAG, "createScreenCaptureIntent failed", e)
            return false
        }
        return try {
            launcher(consent)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "launching the consent dialog failed", e)
            false
        }
    }

    /**
     * Result handler for the consent dialog. A refusal is not an error worth a dialog of its own:
     * the screenshot action keeps working through the shell or the accessibility service, and the
     * settings row simply keeps saying capture is off.
     */
    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "screen capture consent declined (resultCode=$resultCode)")
            return
        }
        val service = Intent(context, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        try {
            context.startForegroundService(service)
        } catch (e: Throwable) {
            // Background-start restrictions refuse this when the app is not visible; the user has
            // to be looking at the settings switch for the dialog to have appeared at all, so this
            // is a rare race rather than the normal path.
            Log.w(TAG, "startForegroundService failed", e)
        }
    }

    fun stop(context: Context) {
        ScreenCaptureService.stop(context)
    }

    /**
     * Latest frame from the live projection as PNG bytes, or null when nothing is capturing.
     *
     * Encoded here rather than handing the bitmap out: the frame crosses into commonMain, and a
     * platform image class has no business in the shared module.
     */
    suspend fun capture(): ByteArray? {
        val bitmap = ScreenCaptureService.current()?.captureFrame() ?: return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (encoded) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }
}
