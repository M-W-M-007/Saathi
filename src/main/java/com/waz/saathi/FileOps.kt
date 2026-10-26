package com.waz.saathi

import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * File work, done directly rather than by driving a file manager app.
 *
 * Folder names are spoken, not typed, so a path is never given exactly:
 * "downloads mein daal do" has to become /storage/emulated/0/Download. This
 * resolves spoken names against the real folders on the device.
 */
object FileOps {

    private val root: File get() = Environment.getExternalStorageDirectory()

    /** Common folders under their spoken names, including Hindi ones. */
    private val aliases: Map<String, String> = mapOf(
        "download" to "Download",
        "downloads" to "Download",
        "dawnload" to "Download",
        "document" to "Documents",
        "documents" to "Documents",
        "dastavez" to "Documents",
        "picture" to "Pictures",
        "pictures" to "Pictures",
        "photo" to "DCIM",
        "photos" to "DCIM",
        "camera" to "DCIM/Camera",
        "tasveer" to "DCIM",
        "music" to "Music",
        "gaane" to "Music",
        "movie" to "Movies",
        "movies" to "Movies",
        "video" to "Movies",
        "videos" to "Movies",
        "whatsapp" to "Android/media/com.whatsapp/WhatsApp/Media",
        "dcim" to "DCIM"
    )

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    /**
     * Turns something spoken into a real folder.
     * Accepts an alias, a real path, or a folder name to search for.
     */
    fun resolveFolder(spoken: String): File? {
        val clean = spoken.trim().trim('/')
        if (clean.isEmpty()) return root

        if (clean.startsWith("/storage") || clean.startsWith("/sdcard")) {
            return File(clean).takeIf { it.isDirectory }
        }

        val key = clean.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        aliases[key]?.let { rel ->
            val f = File(root, rel)
            if (f.isDirectory) return f
        }

        File(root, clean).takeIf { it.isDirectory }?.let { return it }

        // Last resort: look for a folder by that name a couple of levels down.
        return findFolder(root, key, depth = 3)
    }

    private fun findFolder(dir: File, key: String, depth: Int): File? {
        if (depth <= 0) return null
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null

        children.firstOrNull {
            it.name.lowercase(Locale.ROOT).filter { c -> c.isLetterOrDigit() } == key
        }?.let { return it }

        for (child in children) {
            if (child.name.startsWith(".")) continue
            findFolder(child, key, depth - 1)?.let { return it }
        }
        return null
    }

    /** Finds a file by spoken name, optionally inside one folder. */
    fun findFile(spokenName: String, inFolder: File? = null): File? {
        val key = spokenName.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return null

        val start = inFolder ?: root
        return search(start, key, depth = 4)
    }

    private fun search(dir: File, key: String, depth: Int): File? {
        if (depth <= 0) return null
        val children = dir.listFiles() ?: return null

        children.filter { it.isFile }.firstOrNull {
            val name = it.nameWithoutExtension.lowercase(Locale.ROOT)
                .filter { c -> c.isLetterOrDigit() }
            name == key || name.contains(key)
        }?.let { return it }

        for (child in children) {
            if (!child.isDirectory || child.name.startsWith(".")) continue
            search(child, key, depth - 1)?.let { return it }
        }
        return null
    }

    fun createFolder(parentSpoken: String, name: String): Result<File> = runCatching {
        val parent = resolveFolder(parentSpoken) ?: root
        val clean = name.trim().trim('/')
        require(clean.isNotEmpty()) { "Folder ka naam nahi mila." }

        val target = File(parent, clean)
        if (target.exists()) return@runCatching target
        require(target.mkdirs()) { "Folder nahi ban paya: ${target.absolutePath}" }
        target
    }

    fun move(source: File, destFolder: File): Result<File> = runCatching {
        require(source.exists()) { "File nahi mili: ${source.name}" }
        require(destFolder.isDirectory) { "Folder nahi mila: ${destFolder.name}" }

        val target = uniqueTarget(destFolder, source.name)

        // renameTo fails across different storage volumes, so fall back to a
        // copy-then-delete, which works everywhere.
        if (source.renameTo(target)) return@runCatching target

        source.copyTo(target, overwrite = false)
        require(source.delete()) { "Copy ho gayi lekin purani file delete nahi hui." }
        target
    }

    fun copy(source: File, destFolder: File): Result<File> = runCatching {
        require(source.exists()) { "File nahi mili: ${source.name}" }
        require(destFolder.isDirectory) { "Folder nahi mila: ${destFolder.name}" }
        source.copyTo(uniqueTarget(destFolder, source.name), overwrite = false)
    }

    fun delete(target: File): Result<Unit> = runCatching {
        require(target.exists()) { "Nahi mila: ${target.name}" }
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        require(ok) { "Delete nahi ho paya." }
    }

    fun list(folder: File, limit: Int = 30): List<File> =
        folder.listFiles()
            ?.filterNot { it.name.startsWith(".") }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            ?.take(limit)
            .orEmpty()

    /** Never silently overwrites: "report.pdf" becomes "report (2).pdf". */
    private fun uniqueTarget(folder: File, name: String): File {
        var candidate = File(folder, name)
        if (!candidate.exists()) return candidate

        val base = candidate.nameWithoutExtension
        val ext = candidate.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (candidate.exists() && n < 1000) {
            candidate = File(folder, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    fun shortPath(file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/').ifEmpty { "Internal storage" }
}
