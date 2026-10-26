package com.waz.saathi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

/**
 * The list of launchable apps, and the matching that turns a spoken name into
 * one of them.
 *
 * Speech recognition rarely returns an app's exact label — "whatsapp" comes
 * back as "whats app", "Play Store" as "pay store". Exact matching would fail
 * constantly, so this scores candidates and takes the best one above a floor.
 */
object AppIndex {

    data class Entry(val label: String, val packageName: String) {
        val normalized: String = normalize(label)
    }

    @Volatile
    private var cache: List<Entry> = emptyList()

    fun load(context: Context): List<Entry> {
        cache.takeIf { it.isNotEmpty() }?.let { return it }
        return refresh(context)
    }

    fun refresh(context: Context): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val entries = pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            val pkg = info.activityInfo?.packageName.orEmpty()
            if (label.isEmpty() || pkg.isEmpty()) null else Entry(label, pkg)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }

        cache = entries
        return entries
    }

    /** Best match for a spoken name, or null when nothing is close enough. */
    fun find(context: Context, spoken: String): Entry? = matches(context, spoken).firstOrNull()

    /**
     * Candidates, best first, at most three.
     *
     * "WhatsApp Business kholo" used to open plain WhatsApp. Both scored a flat
     * 1.0 because the spoken name contains "whatsapp", and the first of two
     * equal scores wins — which alphabetically is the wrong one. Containment
     * now scores by how much of the two names actually overlap, so the longer,
     * more specific label wins the moment the user says it.
     */
    fun matches(context: Context, spoken: String): List<Entry> {
        val needle = normalize(spoken)
        if (needle.isEmpty()) return emptyList()

        val apps = load(context)

        apps.firstOrNull { it.normalized == needle }?.let { return listOf(it) }

        return apps
            .map { it to score(needle, it.normalized) }
            .filter { it.second > 0.55f }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
    }

    /** True when the second-best match is close enough to be worth asking about. */
    fun ambiguous(context: Context, spoken: String): Boolean {
        val needle = normalize(spoken)
        if (needle.isEmpty()) return false
        if (load(context).any { it.normalized == needle }) return false

        val top = load(context)
            .map { score(needle, it.normalized) }
            .filter { it > 0.55f }
            .sortedDescending()
            .take(2)

        return top.size == 2 && top[0] - top[1] < 0.18f
    }

    fun launchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------- matching

    /** Lowercase, strip everything but letters and digits. "Play Store" -> "playstore" */
    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * 1.0 for a containment match, otherwise character-overlap in order.
     * Deliberately simple: app labels are short, and a heavier algorithm would
     * not survive the kinds of errors speech recognition actually makes.
     */
    private fun score(needle: String, hay: String): Float {
        if (needle.isEmpty() || hay.isEmpty()) return 0f
        if (needle == hay) return 1f

        // Containment is graded by overlap, not treated as a perfect match:
        // "whatsappbusiness" against "whatsapp" is half a match, against
        // "whatsappbusiness" it is a whole one.
        if (hay.contains(needle) || needle.contains(hay)) {
            val ratio = minOf(needle.length, hay.length).toFloat() /
                maxOf(needle.length, hay.length)
            return 0.6f + 0.39f * ratio
        }

        var i = 0
        var matched = 0
        for (ch in hay) {
            if (i < needle.length && needle[i] == ch) {
                matched++
                i++
            }
        }
        return matched.toFloat() / maxOf(needle.length, hay.length)
    }

    fun packageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
