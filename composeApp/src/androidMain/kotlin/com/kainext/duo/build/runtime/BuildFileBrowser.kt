package com.kainext.duo.build.runtime

import android.content.Context
import com.kainext.duo.FileBrowserSource
import com.kainext.duo.SandboxFileEntry
import com.kainext.duo.TextFileResult
import com.kainext.duo.MAX_BROWSER_TEXT_BYTES
import io.github.vinceglb.filekit.PlatformFile
import kotlin.Result

class BuildFileBrowser(private val context: Context, private val paths: Any) : FileBrowserSource {
    override suspend fun listDirectory(path: String): List<SandboxFileEntry> = emptyList()
    override suspend fun readTextFile(path: String, maxBytes: Int, force: Boolean): TextFileResult = TextFileResult.Unreadable
    override suspend fun writeTextFile(path: String, content: String): Boolean = false
    override suspend fun openFile(path: String): Result<Unit> = Result.failure(UnsupportedOperationException("Kai Build file browsing is not available"))
    override suspend fun deleteEntry(path: String, recursive: Boolean): Boolean = false
    override suspend fun renameEntry(path: String, newName: String): Result<String> = Result.failure(UnsupportedOperationException("Kai Build file browsing is not available"))
    override suspend fun importFile(directoryPath: String, source: PlatformFile): Result<String> = Result.failure(UnsupportedOperationException("Kai Build file browsing is not available"))
}
