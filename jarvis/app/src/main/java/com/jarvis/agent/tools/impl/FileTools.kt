package com.jarvis.agent.tools.impl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paths are relative to shared storage (/sdcard). Escaping it with ".." is refused. */
internal object Storage {
    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAccess(ctx: ToolContext): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun resolve(path: String?): File? {
        val rel = path.orEmpty().trim().removePrefix("/sdcard").removePrefix("/storage/emulated/0").trimStart('/')
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(root.canonicalPath)) f else null
    }

    fun noAccess() = ToolResult.error("PERMISSION_MISSING: 'All files access' is not granted for Jarvis.")

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

class ListFilesTool : BaseTool(
    "list_files", "List files in a folder of phone storage (e.g. 'Download', 'Documents', 'DCIM/Camera').",
    RiskLevel.LOW, readOnly = true,
    {
        string("path", "Folder relative to storage root (default: Download)", required = false)
        integer("limit", "Max entries (default 50)", required = false, min = 1, max = 200)
    },
) {
    override fun describe(input: JsonObject) = "List files in ${input.str("path") ?: "Download"}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        if (!Storage.hasAccess(ctx)) return@withContext Storage.noAccess()
        val dir = Storage.resolve(input.str("path") ?: Environment.DIRECTORY_DOWNLOADS)
            ?: return@withContext ToolResult.error("Path outside storage")
        val files = dir.listFiles() ?: return@withContext ToolResult.error("Not a folder: ${input.str("path")}")
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH)
        val lines = files.sortedByDescending { it.lastModified() }.take((input.int("limit") ?: 50).coerceIn(1, 200)).map {
            (if (it.isDirectory) "[dir] " else "") + it.name + (if (it.isFile) " (${Storage.size(it.length())})" else "") +
                " — " + fmt.format(Date(it.lastModified()))
        }
        ToolResult.ok(lines.joinToString("\n").ifEmpty { "Empty folder" })
    }
}

class ReadTextFileTool : BaseTool(
    "read_text_file", "Read a text file (txt, md, csv, json, log…) from phone storage.", RiskLevel.LOW, readOnly = true,
    {
        string("path", "File path relative to storage root")
        integer("max_chars", "Default 8000", required = false, min = 100, max = 12_000)
    },
) {
    private val textExt = setOf("txt", "md", "csv", "json", "log", "xml", "html", "htm", "srt", "vcf", "ics")

    override fun describe(input: JsonObject) = "Read file ${input.str("path")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        if (!Storage.hasAccess(ctx)) return@withContext Storage.noAccess()
        val f = Storage.resolve(input.str("path")) ?: return@withContext ToolResult.error("Path outside storage")
        if (!f.isFile) return@withContext ToolResult.error("File not found")
        if (f.extension.lowercase() !in textExt) return@withContext ToolResult.error("Not a text file (${f.extension})")
        val max = (input.int("max_chars") ?: 8000).coerceIn(100, 12_000)
        val text = f.bufferedReader().use { r -> CharArray(max).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
        ToolResult.untrusted("file ${f.name}", text)
    }
}

class DeleteFileTool : BaseTool(
    "delete_file", "Permanently delete a file from phone storage. High risk: needs fingerprint confirmation.",
    RiskLevel.HIGH,
    schemaBlock = { string("path", "File path relative to storage root") },
) {
    override fun describe(input: JsonObject) = "Delete file ${input.str("path")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val f = Storage.resolve(input.str("path"))
        val detail = if (f != null && f.isFile) "${f.path}\n${Storage.size(f.length())}" else input.str("path").orEmpty()
        return RiskAssessment(RiskLevel.HIGH, "Permanently delete ${f?.name ?: input.str("path")}", detail)
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        if (!Storage.hasAccess(ctx)) return@withContext Storage.noAccess()
        val f = Storage.resolve(input.str("path")) ?: return@withContext ToolResult.error("Path outside storage")
        if (!f.isFile) return@withContext ToolResult.error("Only single files can be deleted")
        if (f.delete()) ToolResult.ok("Deleted ${f.name}.") else ToolResult.error("Could not delete ${f.name}")
    }
}
