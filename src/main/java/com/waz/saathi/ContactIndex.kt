package com.waz.saathi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/** Looks up a phone number from a spoken name. */
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
    fun resolve(context: Context, spoken: String): Contact? {
        val digits = spoken.filter { it.isDigit() || it == '+' }
        if (digits.length >= 7) return Contact(spoken.trim(), digits)

        if (!hasPermission(context)) return null
        return search(context, spoken)
    }

    private fun search(context: Context, name: String): Contact? {
        val needle = name.lowercase(Locale.ROOT).trim()
        if (needle.isEmpty()) return null

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
        }.getOrNull() ?: return null

        var exact: Contact? = null
        var partial: Contact? = null

        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numIdx < 0) return null

            while (it.moveToNext()) {
                val display = it.getString(nameIdx)?.trim().orEmpty()
                val number = it.getString(numIdx)?.replace(" ", "").orEmpty()
                if (display.isEmpty() || number.isEmpty()) continue

                val lower = display.lowercase(Locale.ROOT)
                when {
                    lower == needle -> {
                        exact = Contact(display, number)
                        return@use
                    }
                    // "call mummy" should find "Mummy Home", and a first name
                    // should find the full name it starts.
                    partial == null && (lower.contains(needle) || needle.contains(lower)) ->
                        partial = Contact(display, number)
                }
            }
        }

        return exact ?: partial
    }
}
