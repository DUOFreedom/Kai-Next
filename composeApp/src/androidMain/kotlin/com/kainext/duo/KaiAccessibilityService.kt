package com.kainext.duo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "KaiA11y"

/**
 * How long to wait between screen captures. The platform rate-limits accessibility screenshots at
 * roughly three per second and answers a faster call with an error rather than an image, so a
 * burst of agent actions (screenshot, tap, screenshot) would lose the second frame.
 */
private const val MIN_SCREENSHOT_INTERVAL_MS = 400L

/**
 * The accessibility service behind the "accessibility" privilege mode.
 *
 * Why this exists next to root and Shizuku: both of those can inject input via `input tap`,
 * but that path is blind — it has no idea what is on screen, so the agent has to screenshot
 * and guess coordinates. An accessibility service can read the live node tree (with exact
 * bounds and labels) and dispatch gestures through the same pipeline the system uses for
 * TalkBack, which makes taps land on the intended control even when the layout shifted.
 *
 * It also keeps working when the device is locked down or `input` is restricted, and it needs
 * no root — so it is the fallback for users who will not or cannot root.
 *
 * The service holds no state of its own beyond the node-tree snapshot it exposes statically;
 * the tool layer drives it.
 */
class KaiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The tool layer pulls the tree on demand; events are not needed for that, and
        // reacting to every window change would burn battery for no benefit.
    }

    override fun onInterrupt() {
        // Nothing to interrupt: gestures are one-shot and awaited by the caller.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _connected.value = false
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    /** The currently attached service, or null when the user has not enabled it. */
    companion object {
        @Volatile
        private var instance: KaiAccessibilityService? = null

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun current(): KaiAccessibilityService? = instance
    }

    // ---- actions used by AndroidUiTool ----

    /** Root node of the active window, or null when no window is available. */
    fun activeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Throwable) {
        Log.w(TAG, "rootInActiveWindow failed", e)
        null
    }

    /**
     * Root of the window that belongs to the app the user is actually looking at.
     *
     * [activeRoot] is not that window whenever Kai itself draws something on top: the chat
     * overlay is a TYPE_APPLICATION_OVERLAY window, it takes input focus, and
     * `rootInActiveWindow` then reports Kai's own package. Every caller that asks "did the app
     * come to the front?" or "what is on screen?" would answer "Kai" and then hand the user a
     * screen that was never looked at — and UI snapshots would capture the agent's own
     * narration instead of the target app.
     *
     * This walks the accessibility window stack instead of trusting the focused window: it
     * keeps only real application windows (which excludes Kai's overlay, system dialogs and the
     * IME), prefers the focused then the active one, and prefers a window that is not ours.
     * When nothing else qualifies — the user genuinely is inside Kai — it falls back to
     * [activeRoot] so the answer stays "Kai" rather than null.
     */
    fun foregroundAppRoot(): AccessibilityNodeInfo? {
        val appWindows = try {
            windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (e: Throwable) {
            Log.w(TAG, "windows failed", e)
            emptyList()
        }
        val ordered = appWindows.sortedByDescending { window ->
            when {
                window.isFocused -> 2
                window.isActive -> 1
                else -> 0
            }
        }
        val own = packageName
        val foreign = ordered.firstOrNull { window ->
            try {
                window.root?.packageName?.toString() != own
            } catch (e: Throwable) {
                Log.w(TAG, "window.root failed", e)
                false
            }
        }
        return foreign?.root ?: activeRoot()
    }

    /** Package that owns [foregroundAppRoot], for callers that only need the name. */
    fun foregroundAppPackage(): String? = try {
        foregroundAppRoot()?.packageName?.toString()
    } catch (e: Throwable) {
        Log.w(TAG, "foregroundAppPackage failed", e)
        null
    }

    /**
     * Taps the centre of [node] via ACTION_CLICK. Returns false when the node is not
     * clickable, so the caller can fall back to a coordinate gesture on its bounds.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean = try {
        if (!node.isClickable) {
            false
        } else {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "clickNode failed", e)
        false
    }

    /** Types [text] into [node] (or the focused field when null). */
    fun setText(node: AccessibilityNodeInfo?, text: String): Boolean = try {
        val target = node ?: findFocusedEditable()
        if (target == null) {
            false
        } else {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "setText failed", e)
        false
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (_: Throwable) {
        null
    }

    /**
     * Dispatches a tap gesture at absolute screen coordinates. Needed for views that expose
     * bounds but are not marked clickable (custom-drawn lists, canvas apps, games).
     */
    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): Boolean = dispatchPath { path ->
        path.moveTo(x, y)
        GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
    }

    /** Swipes from (x1,y1) to (x2,y2) over [durationMs]. */
    fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean = dispatchPath { path ->
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
    }

    private fun dispatchPath(build: (Path) -> GestureDescription.StrokeDescription): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            false
        } else {
            val path = Path()
            val stroke = build(path)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "dispatchGesture failed", e)
        false
    }

    // ---- screen capture ----

    /** When the last capture was requested, so the platform's rate limit is respected here. */
    private var lastCaptureAtMs = 0L

    /**
     * Captures the screen through the accessibility screenshot API (Android 11 and up).
     *
     * Root and Shizuku can run `screencap`, but the accessibility mode is the one that needs no
     * elevated privilege at all — and a screenshot was the one action it could not serve, so a
     * user who granted only accessibility got an error every time the agent asked to look at the
     * screen. This closes that gap: the platform returns the composited pixels at native
     * resolution, which is exactly what the agent is allowed to reason about.
     *
     * Returns null when the platform refuses — below Android 11, when screen capture is disabled
     * for accessibility, or when a second request arrives inside the minimum interval. The caller
     * must read null as "no image" and fall back, never as "the screen was blank".
     */
    suspend fun captureScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // The platform answers a too-early request with an error code instead of an image, so a
        // tight agent loop (screenshot, tap, screenshot) would silently lose frames. Waiting the
        // remainder out is cheaper than the failure the caller would have to retry.
        val sinceLast = SystemClock.elapsedRealtime() - lastCaptureAtMs
        if (sinceLast < MIN_SCREENSHOT_INTERVAL_MS) {
            delay(MIN_SCREENSHOT_INTERVAL_MS - sinceLast)
        }
        lastCaptureAtMs = SystemClock.elapsedRealtime()

        val shot: ScreenshotResult = try {
            suspendCancellableCoroutine<ScreenshotResult?> { continuation ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            if (continuation.isActive) continuation.resume(screenshot)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed with error $errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "takeScreenshot threw", e)
            null
        } ?: return null

        return try {
            // wrapHardwareBuffer hands back a Bitmap that still points at the GPU buffer, so it
            // has to be copied before the buffer is closed — otherwise every capture would keep a
            // full-screen allocation alive until the service died.
            Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Throwable) {
            Log.w(TAG, "screenshot buffer to bitmap failed", e)
            null
        } finally {
            shot.hardwareBuffer.close()
        }
    }

    /** Presses a global navigation key: back, home or recents. */
    fun performGlobal(action: GlobalAction): Boolean = try {
        val id = when (action) {
            GlobalAction.BACK -> GLOBAL_ACTION_BACK
            GlobalAction.HOME -> GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        }
        performGlobalAction(id)
    } catch (e: Throwable) {
        Log.w(TAG, "performGlobalAction failed", e)
        false
    }

    enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }
}
