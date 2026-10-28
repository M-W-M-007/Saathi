package com.waz.saathi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Looks up a phone number from a spoken name.
 *
 * The matching here used to include `needle.contains(displayName)`, which looks
 * harmless until you try it on a phone with four thousand contacts. A contact
 * saved as "S" is contained in almost any sentence, so a misheard command could
 * put a stranger's number on the dialler. Matching is now on whole words only,
 * with a minimum length, and every candidate is scored so the best one wins
 * instead of whichever row the cursor reached first.
 */
object ContactIndex {

    data class Contact(val name: String, val number: String)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A spoken string is either already a number or a name to look up.
     * Numbers are returned untouched so "call nine eight two..." still works
     * when the recogniser writes it out as digits.
     */
    fun resolve(context: Context, spoken: String): Contact? =
        candidates(context, spoken).firstOrNull()

    /** Best matches first, at most three. Empty when nothing is close enough. */
    fun candidates(context: Context, spoken: String): List<Contact> {
        val digits = spoken.filter { it.isDigit() || it == '+' }
        if (digits.length >= 7) return listOf(Contact(spoken.trim(), digits))

        if (!hasPermission(context)) return emptyList()
        return search(context, spoken)
    }

    private fun search(context: Context, name: String): List<Contact> {
        val needle = normalize(name)
        // One or two characters match far too much to dial on.
        if (needle.length < 3) return emptyList()

        val needleWords = needle.split(" ").filter { it.length >= 3 }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null, null, null
            )
        }.getOrNull() ?: return emptyList()

        val scored = LinkedHashMap<String, Pair<Contact, Float>>()

        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numIdx < 0) return emptyList()

            while (it.moveToNext()) {
                val display = it.getString(nameIdx)?.trim().orEmpty()
                val number = it.getString(numIdx)?.replace(" ", "").orEmpty()
                if (display.isEmpty() || number.isEmpty()) continue

                val score = score(needle, needleWords, normalize(display))
                if (score <= 0f) continue

                val key = number.takeLast(10)
                val existing = scored[key]
                if (existing == null || existing.second < score) {
                    scored[key] = Contact(display, number) to score
                }
            }
        }

        return scored.values
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
    }

    /**
     * Titles the recogniser adds or garbles: "Shaheen sis" is heard as
     * "Shaheen syster", "Shaheen sister" is saved as "Shaheen Sis". The
     * title is not the person's name, so it is dropped before matching —
     * the first name alone is what identifies the contact.
     */
    private val TITLES = setOf(
        "sis", "syster", "sister", "sistar", "bhai", "bhaiya", "bhayya",
        "didi", "ji", "aunty", "uncle", "madam", "sir"
    )

    /**
     * 1.0 exact, then whole-word and prefix matches. Substring matching only
     * counts when the contact's own name is long enough to be distinctive, so
     * short initials can never be dragged in by a long spoken sentence.
     *
     * v3.4: two fallbacks sit below the strict rules, because the
     * recogniser garbles names ("shaheen syster" for "shaheen sis"):
     * title-stripping, then per-word fuzzy matching. Both are capped
     * below the strict scores, so a fuzzy guess can never beat a real
     * exact match — it only stops a "nahi mila" when nothing strict hit.
     */
    private fun score(needle: String, needleWords: List<String>, display: String): Float {
        if (display.isEmpty()) return 0f
        if (display == needle) return 1f

        val displayWords = display.split(" ").filter { it.isNotBlank() }

        // "Ammi" matches "Ammi New Number"; "shaheen sis" matches "Shaheen Sis".
        if (display.startsWith("$needle ")) return 0.95f
        if (displayWords.any { it == needle }) return 0.9f

        // Every spoken word appears as a whole word in the name.
        if (needleWords.isNotEmpty() && needleWords.all { w -> displayWords.any { it == w } }) {
            return 0.85f
        }

        // The spoken name is a prefix of one of the name's words: "ami" -> "ammi".
        if (needle.length >= 4 && displayWords.any { it.startsWith(needle) }) return 0.75f

        // The reverse — the saved name sits inside what was spoken — is only
        // safe for names long enough not to be an accident.
        if (display.length >= 5 && needle.contains(display)) return 0.6f

        // Title stripped: "shaheen syster" -> "shaheen", which then
        // matches "shaheen sis" by the whole-word rule above.
        val strippedWords = needleWords.filter { it !in TITLES }
        if (strippedWords.isNotEmpty() && strippedWords.size < needleWords.size &&
            strippedWords.all { w -> displayWords.any { it == w } }
        ) {
            return 0.88f
        }
        if (strippedWords.size == 1 && displayWords.any { it == strippedWords.first() }) {
            return 0.88f
        }
        // Title stripped + fuzzy: "shahin syster" -> "shahin", which is
        // 2 edits from "shaheen", so it still finds "shaheen sis".
        if (strippedWords.isNotEmpty() && strippedWords.size < needleWords.size) {
            val strippedBest = strippedWords.map { w ->
                displayWords.maxOfOrNull { wordScore(w, it) } ?: 0f
            }
            if (strippedBest.all { it > 0f } && strippedBest.average() >= 0.7) {
                return 0.80f
            }
        }

        // Per-word fuzzy: each spoken word must be close to some word in
        // the saved name (prefix or at most 2 edits for words of 4+),
        // and at least one word must be a strong match. "shahin" is
        // 2 edits from "shaheen", so "shahin syster" still finds
        // "shaheen sis" once "syster" is stripped above — but a word
        // with no close neighbour scores 0 and kills the candidate,
        // so random names cannot sneak in on one good word.
        if (needleWords.isNotEmpty()) {
            val bestPerWord = needleWords.map { w ->
                displayWords.maxOfOrNull { wordScore(w, it) } ?: 0f
            }
            val strong = bestPerWord.any { it >= 0.85f }
            val average = bestPerWord.average().toFloat()
            if (strong && average >= 0.6f && bestPerWord.all { it > 0f }) {
                return 0.55f + 0.30f * average
            }
        }

        return 0f
    }

    /** 1.0 exact, 0.85 prefix, otherwise edit-distance similarity, else 0. */
    private fun wordScore(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.length >= 3 && (b.startsWith(a) || a.startsWith(b))) return 0.85f
        if (a.length < 4 || b.length < 4) return 0f
        val dist = levenshtein(a, b)
        if (dist > 2) return 0f
        return 1f - dist.toFloat() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
