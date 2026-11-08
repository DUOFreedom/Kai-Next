package com.kainext.duo.tools

import com.kainext.duo.KaiAccessibilityService
import com.kainext.duo.tools.uitree.UiTreeShellFallback
import com.kainext.duo.tools.uitree.UiTreePruner
import com.kainext.duo.tools.uitree.UiActionExecutor

import android.util.Base64
import com.kainext.duo.network.tools.ParameterSchema
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.network.tools.ToolInfo
import com.kainext.duo.network.tools.ToolSchema
import kotlin.time.Duration.Companion.seconds

private const val MAX_FILE_BYTES = 200_000
private const val MAX_UI_NODES = 80

/** Shared helper: wraps a privileged result as the JSON-able map every tool returns. */
private fun PrivilegedResult.asToolMap(): Map<String, Any> = mapOf(
    "success" to success,
    "backend" to backend,
    "exit_code" to exitCode,
    "stdout" to stdout,
    "stderr" to stderr,
)

/**
 * Runs arbitrary shell commands with root or Shizuku privileges. This is the low-level
 * primitive behind the file and UI tools; it is also exposed on its own so the agent can
 * use device utilities (`am`, `pm`, `settings`, `dumpsys`, …) the other tools don't wrap.
 */
class AndroidShellTool(private val shell: PrivilegedShell) : Tool {
    override val schema = ToolSchema(
        name = ID,
        description = "Execute a shell command on the Android device with root or Shizuku (adb shell) privileges. " +
            "Use standard Android shell tools: am, pm, settings, dumpsys, input, screencap, cmd, getprop, etc. " +
            "The working directory is the shell default. Output is capped by the tool executor.",
        parameters = mapOf(
            "command" to ParameterSchema("string", "The shell command to run, e.g. 'getprop ro.product.model'.", required = true),
            "timeout_seconds" to ParameterSchema("number", "Timeout in seconds (1-120, default 30).", required = false),
        ),
    )

    override val timeout = 130.seconds

    override suspend fun execute(args: Map<String, Any>): Any {
        val command = args["command"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return mapOf("success" to false, "error" to "Missing command")
        val timeoutSeconds = (args["timeout_seconds"] as? Number)?.toLong()?.coerceIn(1L, 120L) ?: 30L
        return shell.execute(command, timeoutSeconds * 1000L).asToolMap()
    }

    companion object {
        const val ID = "android_shell"

        val toolInfo = ToolInfo(
            id = ID,
            name = "Android Shell",
            description = "Run shell commands with root/Shizuku privileges",
            isEnabled = false,
            userToggleable = false,
        )
    }
}

/**
 * Reads and writes files under shared storage (`/sdcard`) by going through the privileged
 * shell, so it works even when the app itself does not hold MANAGE_EXTERNAL_STORAGE.
 */
class AndroidFileTool(private val shell: PrivilegedShell) : Tool {

    override val schema = ToolSchema(
        name = ID,
        description = "Access files on shared storage (/sdcard) using root/Shizuku privileges. " +
            "Actions: list (default), read, write, append, delete, move, mkdir, stat. " +
            "Paths must be under /sdcard, /storage or /mnt/media_rw.",
        parameters = mapOf(
            "action" to ParameterSchema("string", "One of: list, read, write, append, delete, move, mkdir, stat.", required = true),
            "path" to ParameterSchema("string", "Absolute path, e.g. /sdcard/Download/file.txt.", required = true),
            "content" to ParameterSchema("string", "Text content for write/append.", required = false),
            "destination" to ParameterSchema("string", "Destination path for move.", required = false),
            "recursive" to ParameterSchema("boolean", "For delete: remove directories recursively (default true).", required = false),
        ),
    )

    override val timeout = 90.seconds

    override suspend fun execute(args: Map<String, Any>): Any {
        val action = args["action"]?.toString()?.lowercase() ?: "list"
        val rawPath = args["path"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return mapOf("success" to false, "error" to "Missing path")
        val path = normalizeSharedPath(rawPath)
            ?: return mapOf("success" to false, "error" to "Path must be under /sdcard, /storage or /mnt/media_rw: $rawPath")
        val quoted = shell.shellQuote(path)

        return when (action) {
            "list" -> shell.execute("ls -la $quoted 2>&1").asToolMap()

            "stat" -> shell.execute("stat -c '%n %s bytes %a %y' $quoted 2>&1 || ls -ld $quoted 2>&1").asToolMap()

            "read" -> {
                val result = shell.execute("head -c $MAX_FILE_BYTES $quoted 2>&1")
                if (result.success && result.stdout.length >= MAX_FILE_BYTES) {
                    result.asToolMap() + mapOf("truncated" to true)
                } else {
                    result.asToolMap()
                }
            }

            "write", "append" -> {
                val content = args["content"]?.toString()
                    ?: return mapOf("success" to false, "error" to "Missing content")
                val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val redirect = if (action == "append") ">>" else ">"
                shell.execute("printf '%s' '$encoded' | base64 -d $redirect $quoted 2>&1 && echo OK").asToolMap()
            }

            "delete" -> {
                val recursive = (args["recursive"] as? Boolean) ?: true
                val flag = if (recursive) "-rf" else "-f"
                shell.execute("rm $flag $quoted 2>&1 && echo OK").asToolMap()
            }

            "move" -> {
                val destination = args["destination"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return mapOf("success" to false, "error" to "Missing destination")
                val dest = normalizeSharedPath(destination)
                    ?: return mapOf("success" to false, "error" to "Destination must be under /sdcard: $destination")
                shell.execute("mv $quoted ${shell.shellQuote(dest)} 2>&1 && echo OK").asToolMap()
            }

            "mkdir" -> shell.execute("mkdir -p $quoted 2>&1 && echo OK").asToolMap()

            else -> mapOf("success" to false, "error" to "Unknown action: $action")
        }
    }

    /** Accepts /sdcard and /storage/emulated/0 spellings; rejects anything outside shared storage. */
    private fun normalizeSharedPath(path: String): String? {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) return null
        val allowed = listOf("/sdcard", "/storage", "/mnt/media_rw")
        if (allowed.none { trimmed == it || trimmed.startsWith("$it/") }) return null
        if (trimmed.contains("/../")) return null
        return trimmed
    }

    companion object {
        const val ID = "android_files"

        val toolInfo = ToolInfo(
            id = ID,
            name = "Android Files",
            description = "Read/write files under /sdcard with privileged access",
            isEnabled = false,
            userToggleable = false,
        )
    }
}

/**
 * Drives the device UI: taps, swipes, text entry, key events, app launch, and a compact
 * accessibility-tree dump the model can reason about. All actions run as shell
 * (`input`, `uiautomator`, `am`, `dumpsys`), which is why this needs root or Shizuku.
 */
class AndroidUiTool(private val shell: PrivilegedShell) : Tool {

    private val uiPruner = UiTreePruner()
    private val uiFallback = UiTreeShellFallback(shell)
    private val uiActions = UiActionExecutor(pruner = uiPruner, shell = shell, fallback = uiFallback)


    override val schema = ToolSchema(
        name = ID,
        description = "Control the phone user interface using accessibility, root or Shizuku. " +
            "Actions: dump (accessibility tree), tap_node (tap by label — preferred), tap, swipe, text, key, " +
            "launch, current_app, screenshot, wait, back, home, recents. " +
            "Prefer tap_node with a visible label over tap with coordinates: labels survive scrolling and " +
            "layout shifts, raw coordinates do not. Use dump first when you do not know the label.",
        parameters = mapOf(
            "action" to ParameterSchema(
                "string",
                "One of: dump, tap, tap_node, swipe, text, key, launch, current_app, screenshot, wait, back, home, recents.",
                required = true,
            ),
            "target" to ParameterSchema(
                "string",
                "For tap_node: visible text, content description, or resource id of the control to activate. " +
                    "Prefers an exact match, then a case-insensitive contains match.",
                required = false,
            ),
            "x" to ParameterSchema("number", "X coordinate for tap / swipe start.", required = false),
            "y" to ParameterSchema("number", "Y coordinate for tap / swipe start.", required = false),
            "x2" to ParameterSchema("number", "Swipe end X.", required = false),
            "y2" to ParameterSchema("number", "Swipe end Y.", required = false),
            "duration_ms" to ParameterSchema("number", "Swipe duration in ms (default 300).", required = false),
            "text" to ParameterSchema("string", "Text to type for the text action.", required = false),
            "key" to ParameterSchema("string", "Key event name or code: HOME, BACK, ENTER, POWER, VOLUME_UP, or a number.", required = false),
            "package" to ParameterSchema("string", "Package name for launch, e.g. com.android.settings.", required = false),
            "activity" to ParameterSchema("string", "Optional activity for launch.", required = false),
            "seconds" to ParameterSchema("number", "Seconds to wait for the wait action.", required = false),
        ),
    )

    override val timeout = 120.seconds

    override suspend fun execute(args: Map<String, Any>): Any {
        val action = args["action"]?.toString()?.lowercase()
            ?: return mapOf("success" to false, "error" to "Missing action")
        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()

        return when (action) {
            "dump" -> dumpUi()

            "tap" -> {
                if (x == null || y == null) return missing("x/y for tap")
                // A gesture through the accessibility service is dispatched by the system input
                // pipeline, so it works even where `input tap` is restricted, and it does not
                // depend on a shell backend being available at all.
                val a11y = KaiAccessibilityService.current()
                if (a11y != null && a11y.gestureTap(x.toFloat(), y.toFloat())) {
                    mapOf("success" to true, "backend" to "accessibility", "x" to x, "y" to y)
                } else {
                    shell.execute("input tap $x $y 2>&1").asToolMap()
                }
            }

            "tap_node" -> tapNode(args["target"]?.toString())

            "swipe" -> {
                val x2 = (args["x2"] as? Number)?.toInt()
                val y2 = (args["y2"] as? Number)?.toInt()
                if (x == null || y == null || x2 == null || y2 == null) return missing("x/y/x2/y2 for swipe")
                val duration = (args["duration_ms"] as? Number)?.toInt()?.coerceIn(50, 5000) ?: 300
                val a11y = KaiAccessibilityService.current()
                if (a11y != null && a11y.gestureSwipe(x.toFloat(), y.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())) {
                    mapOf("success" to true, "backend" to "accessibility")
                } else {
                    shell.execute("input swipe $x $y $x2 $y2 $duration 2>&1").asToolMap()
                }
            }

            "text" -> {
                val text = args["text"]?.toString()
                if (text.isNullOrEmpty()) return missing("text")
                // Typing through the service sets the field's text directly: no keyboard focus
                // race, and spaces/newlines survive intact (unlike `input text`, which needs %s).
                val a11y = KaiAccessibilityService.current()
                if (a11y != null && a11y.setText(null, text)) {
                    mapOf("success" to true, "backend" to "accessibility")
                } else {
                    val escaped = text.replace(" ", "%s")
                    shell.execute("input text ${shell.shellQuote(escaped)} 2>&1").asToolMap()
                }
            }

            "key" -> {
                val key = args["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return missing("key")
                shell.execute("input keyevent ${shell.shellQuote(key)} 2>&1").asToolMap()
            }

            "back" -> globalOrShell(KaiAccessibilityService.GlobalAction.BACK, "KEYCODE_BACK")
            "home" -> globalOrShell(KaiAccessibilityService.GlobalAction.HOME, "KEYCODE_HOME")
            "recents" -> globalOrShell(KaiAccessibilityService.GlobalAction.RECENTS, "KEYCODE_APP_SWITCH")

            "launch" -> {
                val pkg = args["package"]?.toString()?.takeIf { it.isNotBlank() } ?: return missing("package")
                val activity = args["activity"]?.toString()?.takeIf { it.isNotBlank() }
                val command = if (activity != null) {
                    "am start -n ${shell.shellQuote("$pkg/$activity")} 2>&1"
                } else {
                    "monkey -p ${shell.shellQuote(pkg)} -c android.intent.category.LAUNCHER 1 2>&1"
                }
                shell.execute(command).asToolMap()
            }

            "current_app" -> {
                shell.execute("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4").asToolMap()
            }

            "screenshot" -> {
                val path = "/sdcard/kai_screenshot_${System.currentTimeMillis()}.png"
                val result = shell.execute("screencap -p ${shell.shellQuote(path)} 2>&1 && echo OK")
                result.asToolMap() + mapOf("path" to path)
            }

            "wait" -> {
                val seconds = (args["seconds"] as? Number)?.toInt()?.coerceIn(1, 10_000) ?: 1
                shell.execute("sleep $seconds 2>&1").asToolMap() + mapOf("waited_seconds" to seconds)
            }

            else -> mapOf("success" to false, "error" to "Unknown action: $action")
        }
    }

    /**
     * Activates a control by its label instead of coordinates — the precise-click path.
     *
     * Coordinate taps break as soon as a list scrolls, a keyboard appears, or the layout shifts
     * between the dump and the tap. Resolving the node live and invoking ACTION_CLICK (or a
     * gesture on its current bounds when it is not clickable) removes that whole class of
     * "tapped the wrong thing" failures.
     */
    private suspend fun tapNode(target: String?): Map<String, Any> {
        val needle = target?.trim().orEmpty()
        if (needle.isEmpty()) return missing("target for tap_node")

        val service = KaiAccessibilityService.current()
        val node = service?.let { findNode(it.activeRoot(), needle) }
        if (service != null && node != null) {
            if (service.clickNode(node)) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                return mapOf(
                    "success" to true,
                    "backend" to "accessibility",
                    "matched" to (node.text?.toString() ?: node.contentDescription?.toString() ?: needle),
                    "bounds" to "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                )
            }
            // Present but not marked clickable: tap its centre, which is what a user would do.
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (service.gestureTap(rect.centerX().toFloat(), rect.centerY().toFloat())) {
                return mapOf(
                    "success" to true,
                    "backend" to "accessibility_gesture",
                    "bounds" to "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                )
            }
        }

        // No service: resolve through a shell dump, then tap the reported centre.
        val dumpPath = "/sdcard/kai_ui_dump.xml"
        val dump = shell.execute(
            "uiautomator dump --compressed ${shell.shellQuote(dumpPath)} >/dev/null 2>&1; cat ${shell.shellQuote(dumpPath)} 2>/dev/null",
        )
        val nodes = if (dump.success && dump.stdout.isNotBlank()) parseUiNodes(dump.stdout) else emptyList()
        val match = nodes.firstOrNull { matches(it, needle, exact = true) }
            ?: nodes.firstOrNull { matches(it, needle, exact = false) }
            ?: return mapOf(
                "success" to false,
                "error" to "No control matching '$needle'. Run action=dump to see what is on screen.",
            )
        val cx = match["center_x"] as? Int
        val cy = match["center_y"] as? Int
        if (cx == null || cy == null) {
            return mapOf("success" to false, "error" to "Matched node has no bounds")
        }
        return shell.execute("input tap $cx $cy 2>&1").asToolMap() +
            mapOf("matched" to (match["text"] ?: match["description"] ?: needle), "x" to cx, "y" to cy)
    }

    /** Exact match first (text, description or resource id), then a case-insensitive contains. */
    private fun findNode(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        needle: String,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null) return null
        var containsMatch: android.view.accessibility.AccessibilityNodeInfo? = null
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val lowered = needle.lowercase()
        while (queue.isNotEmpty() && visited < MAX_UI_NODES * 8) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val id = node.viewIdResourceName
            if (text == needle || desc == needle || id == needle) return node
            if (containsMatch == null && (
                    text?.lowercase()?.contains(lowered) == true ||
                        desc?.lowercase()?.contains(lowered) == true ||
                        id?.lowercase()?.contains(lowered) == true
                    )
            ) {
                containsMatch = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return containsMatch
    }

    private fun matches(node: Map<String, Any>, needle: String, exact: Boolean): Boolean {
        val values = listOfNotNull(node["text"], node["description"], node["resource_id"]).map { it.toString() }
        return if (exact) {
            values.any { it == needle }
        } else {
            values.any { it.contains(needle, ignoreCase = true) }
        }
    }

    /** Dumps the accessibility tree and condenses it to the nodes an agent can act on. */
    private suspend fun dumpUi(): Map<String, Any> {
        // The pruned slice is the preferred output: a raw accessibility tree of a real app is
        // routinely 800-3000 nodes, most of them layout wrappers with no text and no action. That
        // costs tens of thousands of tokens and buries the handful of controls the model can
        // actually use. UiTreePruner keeps only actionable or labelled nodes and reports indices
        // the action executor can resolve against a fresh snapshot.
        uiPruner.slice()?.let { slice ->
            return mapOf(
                "success" to true,
                "backend" to "accessibility",
                "node_count" to slice.nodes.size,
                "truncated" to slice.truncated,
                "nodes" to slice.nodes.map { it.toToolMap() },
            )
        }
        // Shell fallback: same pruning, applied to the uiautomator XML.
        uiFallback.snapshot().takeIf { it.nodes.isNotEmpty() }?.let { slice ->
            return mapOf(
                "success" to true,
                "backend" to "shell",
                "node_count" to slice.nodes.size,
                "truncated" to slice.truncated,
                "nodes" to slice.nodes.map { it.toToolMap() },
            )
        }
        val dumpPath = "/sdcard/kai_ui_dump.xml"
        val result = shell.execute(
            "uiautomator dump --compressed ${shell.shellQuote(dumpPath)} >/dev/null 2>&1; cat ${shell.shellQuote(dumpPath)} 2>/dev/null",
        )
        if (!result.success || result.stdout.isBlank()) {
            return result.asToolMap() + mapOf("error" to (result.stderr.ifBlank { "uiautomator dump produced no output" }))
        }
        val nodes = parseUiNodes(result.stdout)
        return mapOf(
            "success" to true,
            "backend" to result.backend,
            "node_count" to nodes.size,
            "nodes" to nodes.take(MAX_UI_NODES),
        )
    }

    /**
     * Walks the live accessibility tree, emitting only nodes an agent can act on or read.
     * Mirrors the shape [parseUiNodes] produces so the model sees one format either way.
     */
    private fun collectAccessibilityNodes(root: android.view.accessibility.AccessibilityNodeInfo): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && out.size < MAX_UI_NODES && visited < MAX_UI_NODES * 8) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val editable = node.isEditable
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isScrollable || editable) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                out += buildMap<String, Any> {
                    if (text.isNotBlank()) put("text", text)
                    if (desc.isNotBlank()) put("description", desc)
                    node.className?.toString()?.substringAfterLast('.')?.let { put("class", it) }
                    if (node.isClickable) put("clickable", true)
                    if (node.isScrollable) put("scrollable", true)
                    if (editable) put("editable", true)
                    node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { put("resource_id", it) }
                    put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
                    put("center_x", rect.centerX())
                    put("center_y", rect.centerY())
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    private fun parseUiNodes(xml: String): List<Map<String, Any>> {
        val tagRegex = Regex("<node\\b([^>]*)/?>")
        val attrRegex = Regex("([\\w:-]+)=\"([^\"]*)\"")
        val nodes = mutableListOf<Map<String, Any>>()
        for (tag in tagRegex.findAll(xml)) {
            val attrs = attrRegex.findAll(tag.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
            val text = attrs["text"].orEmpty()
            val desc = attrs["content-desc"].orEmpty()
            val clickable = attrs["clickable"] == "true"
            val scrollable = attrs["scrollable"] == "true"
            val editable = attrs["class"]?.contains("EditText") == true
            if (text.isBlank() && desc.isBlank() && !clickable && !scrollable && !editable) continue
            val bounds = attrs["bounds"].orEmpty()
            val center = parseBoundsCenter(bounds)
            nodes += buildMap<String, Any> {
                if (text.isNotBlank()) put("text", text)
                if (desc.isNotBlank()) put("description", desc)
                attrs["class"]?.let { put("class", it.substringAfterLast('.')) }
                if (clickable) put("clickable", true)
                if (scrollable) put("scrollable", true)
                if (editable) put("editable", true)
                attrs["resource-id"]?.takeIf { it.isNotBlank() }?.let { put("resource_id", it) }
                bounds.takeIf { it.isNotBlank() }?.let { put("bounds", it) }
                center?.let { (cx, cy) ->
                    put("center_x", cx)
                    put("center_y", cy)
                }
            }
        }
        return nodes
    }

    /** "[x1,y1][x2,y2]" -> center point. */
    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        val match = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(bounds) ?: return null
        val (x1, y1, x2, y2) = match.destructured.toList().map { it.toInt() }
        return (x1 + x2) / 2 to (y1 + y2) / 2
    }

    /** Prefers the accessibility service's global action, falling back to a shell keyevent. */
    private suspend fun globalOrShell(
        action: KaiAccessibilityService.GlobalAction,
        keyCode: String,
    ): Map<String, Any> {
        val a11y = KaiAccessibilityService.current()
        if (a11y != null && a11y.performGlobal(action)) {
            return mapOf("success" to true, "backend" to "accessibility")
        }
        return shell.execute("input keyevent $keyCode 2>&1").asToolMap()
    }

    private fun missing(name: String): Map<String, Any> = mapOf("success" to false, "error" to "Missing $name")

    companion object {
        const val ID = "android_ui"

        val toolInfo = ToolInfo(
            id = ID,
            name = "Android UI",
            description = "Control the phone UI (tap/swipe/type/keys/dump) via privileges",
            isEnabled = false,
            userToggleable = false,
        )
    }
}
