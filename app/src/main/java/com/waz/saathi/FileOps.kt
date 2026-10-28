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
        "whatsappimages" to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "whatsappdocuments" to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
        "whatsappvideo" to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
        "whatsappaudio" to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
        "screenshot" to "Pictures/Screenshots",
        "screenshots" to "Pictures/Screenshots",
        "bluetooth" to "Bluetooth",
        "internalstorage" to "",
        "phonestorage" to "",
        "storage" to "",
        "dcim" to "DCIM"
    )

    /**
     * App sandboxes. They hold tens of thousands of cache files, which made a
     * search crawl for a minute and then return somebody's thumbnail. The
     * media folder stays searchable because WhatsApp's files live there.
     */
    private val SKIP = listOf("Android/data", "Android/obb")

    /** Stops a search on a huge phone from running until the user gives up. */
    private const val SCAN_LIMIT = 40_000

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
            val f = if (rel.isEmpty()) root else File(root, rel)
            if (f.isDirectory) return f
        }

        File(root, clean).takeIf { it.isDirectory }?.let { return it }

        // Last resort: look for a folder by that name a couple of levels down.
        return findFolder(root, key, depth = 3)
    }

    private fun findFolder(dir: File, key: String, depth: Int): File? {
        if (depth <= 0) return null
        val children = dir.listFiles()?.filter { it.isDirectory && !skip(it) } ?: return null

        children.firstOrNull { normalize(it.name) == key }?.let { return it }

        for (child in children) {
            findFolder(child, key, depth - 1)?.let { return it }
        }
        return null
    }

    /** Finds a file by spoken name, optionally inside one folder. */
    fun findFile(spokenName: String, inFolder: File? = null): File? =
        findFiles(spokenName, inFolder).firstOrNull()

    /**
     * Candidates, best first.
     *
     * The old search returned the first file whose name merely *contained* the
     * spoken word, wherever in the tree it turned up — so asking for "report"
     * could hand back a cached thumbnail from an app's sandbox. Matches are now
     * ranked, exact names win, and app sandboxes are skipped entirely.
     */
    // v3.7: boli hui file ka naam aksar toota hua aata hai — saal
    // "2026" sunne mein "2000 26" ban jata hai, aur beech mein
    // "and" jud jata hai. Isliye search se pehle poora shabd "and"
    // hata dein, aur neeche collect() mein edit-distance wala
    // fuzzy rank bhi dein, warna saal wali file kabhi nahi milegi.
    fun fuzzyKey(spokenName: String): String =
        spokenName.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it != "and" }
            .joinToString("")
            .filter { it.isLetterOrDigit() }

    fun findFiles(spokenName: String, inFolder: File? = null, limit: Int = 3): List<File> {
        val key = fuzzyKey(spokenName)
        if (key.isEmpty()) return emptyList()

        val hits = mutableListOf<Pair<File, Int>>()
        val budget = intArrayOf(SCAN_LIMIT)

        collect(inFolder ?: root, key, depth = 5, hits = hits, budget = budget)

        return hits
            .sortedWith(compareBy({ it.second }, { it.first.name.length }))
            .map { it.first }
            .take(limit)
    }

    private fun collect(
        dir: File,
        key: String,
        depth: Int,
        hits: MutableList<Pair<File, Int>>,
        budget: IntArray
    ) {
        if (depth <= 0 || budget[0] <= 0) return
        val children = dir.listFiles() ?: return

        for (child in children) {
            if (budget[0] <= 0) return
            budget[0]--

            if (child.isDirectory) {
                if (!skip(child)) collect(child, key, depth - 1, hits, budget)
                continue
            }

            val full = normalize(child.name)
            val stem = normalize(child.nameWithoutExtension)

            val rank = when {
                full == key || stem == key -> 0
                stem.startsWith(key) -> 1
                stem.contains(key) -> 2
                // v3.7 fuzzy: "toidelhi20002625pdf" vs asli
                // "toidelhi20261025pdf" — 4 badlaav, lekin lambi file
                // ke naam mein woh kaafi nazdeek hai. Edit distance
                // naam ki lambai ke hisaab se, full naam par bhi
                // (extension ke saath), warna .pdf wali key stem
                // se 3 zyada door ho jati hai. Subsequence bhi.
                minOf(editDistance(stem, key), editDistance(full, key)) <= maxOf(2, key.length / 4) -> 3
                key.length >= 6 && (stem.contains(key.take(6)) || key.contains(stem.take(6))) -> 3
                else -> continue
            }
            hits.add(child to rank)

            // Nothing beats an exact name, so stop paying for a deeper search.
            if (rank == 0 && hits.count { it.second == 0 } >= 3) return
        }
    }

    private fun skip(dir: File): Boolean {
        if (dir.name.startsWith(".")) return true
        val path = dir.absolutePath
        return SKIP.any { path.contains("/$it") }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Chhoti strings ke liye Levenshtein distance. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                cur[j + 1] = minOf(
                    prev[j + 1] + 1,
                    cur[j] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    fun createFolder(parentSpoken: String, name: String): Result<File> = runCatching {
        // v3.3: pehle resolveFolder() alias folder ke na hone par null
        // deta tha, toh parent root ban jata tha aur folder Downloads
        // ki jagah Internal storage ke root mein ban jata tha — user
        // ko Downloads mein dikhta hi nahi tha. Alias ho toh uska
        // asli path hi parent hai, chahe woh folder abhi bana na ho;
        // mkdirs() parent ko bhi bana dega.
        val aliasKey = parentSpoken.trim().lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
        val parent = when {
            parentSpoken.isBlank() -> root
            aliases[aliasKey] != null -> {
                val rel = aliases.getValue(aliasKey)
                if (rel.isEmpty()) root else File(root, rel)
            }
            else -> resolveFolder(parentSpoken) ?: root
        }
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
