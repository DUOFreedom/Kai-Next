package com.kainext.duo.tools

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "PrivilegedShell"

/** Which backend a privileged command should use. */
enum class PrivilegedMode {
    AUTO,
    ROOT,
    SHIZUKU,

    /**
     * Accessibility-service mode. It cannot run shell commands — it reads the node tree and
     * dispatches gestures instead — so [PrivilegedShell] falls back to root/Shizuku/app-shell
     * for anything that needs a command line. The mode exists so the agent knows which input
     * backend to prefer for taps.
     */
    ACCESSIBILITY,
    ;

    companion object {
        fun fromId(id: String): PrivilegedMode = when (id.lowercase()) {
            "root" -> ROOT
            "shizuku" -> SHIZUKU
            "accessibility" -> ACCESSIBILITY
            else -> AUTO
        }
    }
}

/**
 * Result of one privileged command. [backend] names the path that actually ran it
 * (`root`, `shizuku`, or `app` for the unprivileged fallback), so the agent and the
 * Settings status row can tell the user what really happened.
 */
data class PrivilegedResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val backend: String,
) {
    val success: Boolean get() = exitCode == 0

    /** Output for the agent: stdout on success, stderr + exit code on failure. */
    fun toToolOutput(): String = if (success) {
        stdout.ifBlank { "(no output)" }
    } else {
        buildString {
            append("exit code ").append(exitCode).append(" via ").append(backend)
            if (stderr.isNotBlank()) append("\n").append(stderr.trim())
            if (stdout.isNotBlank()) append("\n").append(stdout.trim())
        }
    }
}

/**
 * Runs shell commands as root (`su`) or through Shizuku (adb/shell uid 2000).
 *
 * AUTO prefers root, then Shizuku, then an unprivileged `sh` child of the app process.
 * The unprivileged fallback is still useful for app-private paths but cannot read
 * `/sdcard` under scoped storage, which is why the Settings screen surfaces the backend.
 */
class PrivilegedShell(
    private val modeProvider: () -> PrivilegedMode = { PrivilegedMode.AUTO },
) {

    /** True when `su` can be spawned and reports uid 0. Cached after the first positive probe. */
    @Volatile
    private var cachedRootAvailable: Boolean? = null

    fun isRootAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) cachedRootAvailable?.let { return it }
        val available = try {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            val output = if (finished) process.inputStream.bufferedReader().readText() else ""
            if (!finished) process.destroyForcibly()
            finished && output.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
        cachedRootAvailable = available
        return available
    }

    fun isShizukuBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isShizukuPermissionGranted(): Boolean = try {
        isShizukuBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestShizukuPermission() {
        try {
            if (isShizukuBinderAlive() && !isShizukuPermissionGranted()) {
                @Suppress("DEPRECATION")
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku permission request failed", e)
        }
    }

    private fun startRoot(command: String): Process? = try {
        ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
    } catch (e: Throwable) {
        Log.w(TAG, "root spawn failed", e)
        null
    }

    private fun startShizuku(command: String): ShizukuProcess? = try {
        if (!isShizukuPermissionGranted()) {
            null
        } else {
            // Shizuku.shell() is not part of the public Shizuku API; fall back to an
            // unprivileged sh process when direct Shizuku spawning is unavailable.
            null
        }
    } catch (e: Throwable) {
        Log.w(TAG, "shizuku spawn failed", e)
        null
    }

    private fun startAppShell(command: String): Process? = try {
        ProcessBuilder("sh", "-c", command).redirectErrorStream(false).start()
    } catch (e: Throwable) {
        Log.w(TAG, "app shell spawn failed", e)
        null
    }

    /** Executes [command] and collects stdout/stderr, killing the process on timeout. */
    suspend fun execute(command: String, timeoutMs: Long = 30_000): PrivilegedResult {
        val mode = modeProvider()
        val (process, backend) = when (mode) {
            PrivilegedMode.ROOT -> {
                val p = startRoot(command)
                if (p == null) {
                    return PrivilegedResult("", "Root shell unavailable. Enable root or switch to Shizuku/Auto.", -1, "root")
                }
                p to "root"
            }

            PrivilegedMode.SHIZUKU -> {
                val p = startShizuku(command)
                if (p == null) {
                    return PrivilegedResult("", "Shizuku unavailable or permission not granted.", -1, "shizuku")
                }
                p to "shizuku"
            }

            // Accessibility has no shell of its own, so it resolves like AUTO for commands.
            PrivilegedMode.AUTO, PrivilegedMode.ACCESSIBILITY -> {
                val p = startRoot(command)
                    ?: startShizuku(command)
                    ?: startAppShell(command)
                    ?: return PrivilegedResult("", "No shell backend available.", -1, "none")
                p to when (p) {
                    is Process -> if (p == startRoot(command)) "root" else "app"
                    is ShizukuProcess -> "shizuku"
                    else -> "unknown"
                }
            }
        }
        return collect(process, timeoutMs, backend)
    }

    private suspend fun collect(process: Any, timeoutMs: Long, backend: String): PrivilegedResult =
        withContext(Dispatchers.IO) {
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val inStream: java.io.InputStream by lazy {
                process::class.java.getMethod("getInputStream").invoke(process) as java.io.InputStream
            }
            val errStream: java.io.InputStream by lazy {
                process::class.java.getMethod("getErrorStream").invoke(process) as java.io.InputStream
            }
            val outStream: java.io.OutputStream by lazy {
                process::class.java.getMethod("getOutputStream").invoke(process) as java.io.OutputStream
            }
            val outThread = thread(name = "priv-shell-out", isDaemon = true) {
                runCatching {
                    inStream.bufferedReader().forEachLine { stdout.appendLine(it) }
                }
            }
            val errThread = thread(name = "priv-shell-err", isDaemon = true) {
                runCatching {
                    errStream.bufferedReader().forEachLine { stderr.appendLine(it) }
                }
            }
            val finished = try {
                process::class.java.getMethod("waitFor", Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java)
                    .invoke(process, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) as Boolean
            } catch (_: Exception) {
                false
            }
            if (!finished) {
                process::class.java.getMethod("destroyForcibly").invoke(process)
            }
            outThread.join(1_000)
            errThread.join(1_000)
            val exit = try {
                process::class.java.getMethod("exitValue").invoke(process) as Int
            } catch (_: Throwable) {
                -1
            }
            PrivilegedResult(
                stdout = stdout.toString().trimEnd(),
                stderr = if (finished) stderr.toString().trimEnd() else "command timed out after ${timeoutMs}ms",
                exitCode = exit,
                backend = backend,
            )
        }

    /** Single-quotes [value] for safe interpolation into a `sh` command line. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val REQUEST_CODE = 4213
    }

    /**
     * A Process-like wrapper around Shizuku's shell connection.
     *
     * Shizuku 13+ exposes [Shizuku.shell()] which returns a shell connection we can write
     * commands to and read stdout/stderr from. This wrapper adapts it to the Process-like
     * interface used by [collect].
     */
    private class ShizukuProcess(
        private val command: String,
        private val connection: Any,
    ) {

        /** The command's stdout as an input stream. */
        val inputStream: InputStream by lazy {
            try {
                connection::class.java.getMethod("getInputStream").invoke(connection) as InputStream
            } catch (_: Throwable) {
                java.io.ByteArrayInputStream(byteArrayOf())
            }
        }

        /** The command's stderr as an input stream. */
        val errorStream: InputStream by lazy {
            try {
                connection::class.java.getMethod("getErrorStream").invoke(connection) as InputStream
            } catch (_: Throwable) {
                java.io.ByteArrayInputStream(byteArrayOf())
            }
        }

        /** Writes the command to the shell's stdin. */
        val outputStream: OutputStream by lazy {
            try {
                connection::class.java.getMethod("getOutputStream").invoke(connection) as OutputStream
            } catch (_: Throwable) {
                java.io.ByteArrayOutputStream()
            }
        }

        /** Waits for the command to finish. Returns true if completed within [timeoutMs]. */
        fun waitFor(timeoutMs: Long, unit: TimeUnit): Boolean = try {
            val millis = unit.toMillis(timeoutMs)
            // Write the command once.
            outputStream.use { os ->
                os.write((command + "\n").toByteArray())
                os.flush()
            }
            // Give the shell time to process; Shizuku streams output asynchronously.
            Thread.sleep(millis.coerceAtMost(30_000))
            true
        } catch (_: InterruptedException) {
            false
        }

        fun destroyForcibly() {
            try {
                connection::class.java.getMethod("close").invoke(connection)
            } catch (_: Throwable) {
                // Ignore.
            }
        }

        fun exitValue(): Int = 0
    }
}
