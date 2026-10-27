package com.waz.saathi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import java.io.File

/**
 * Carries out a parsed [Command].
 *
 * Every entry point returns a [Result] describing what happened, in the same
 * Hinglish the user speaks, because that string is both shown on screen and
 * read back aloud.
 */
object ActionRunner {

    data class Outcome(
        val ok: Boolean,
        val message: String,
        /** Shown as a list when an action produces one, e.g. file listings. */
        val details: List<String> = emptyList()
    )

    fun run(context: Context, command: Command): Outcome = when (command.action) {
        Command.Action.OPEN_APP -> openApp(context, command.target)
        Command.Action.CALL -> call(context, command.target, command.speak)
        Command.Action.SEND_SMS -> sendSms(context, command.target, command.text, command.speak)
        Command.Action.WHATSAPP -> whatsapp(context, command.target, command.text, command.speak, command.hint)
        Command.Action.TYPE_TEXT -> typeText(context, command.text)
        Command.Action.CREATE_FOLDER -> createFolder(command.from, command.to)
        Command.Action.MOVE_FILE -> moveOrCopy(command, move = true)
        Command.Action.COPY_FILE -> moveOrCopy(command, move = false)
        Command.Action.DELETE_FILE -> deleteFile(command.from)
        Command.Action.LIST_FILES -> listFiles(command.from)
        Command.Action.SEARCH_FILE -> searchFile(command.target)
        Command.Action.OPEN_SETTINGS -> openSettings(context)
        Command.Action.UNKNOWN -> Outcome(false, "Yeh samajh nahi aaya.")
    }

    // ---------------------------------------------------------------- apps

    private fun openApp(context: Context, name: String): Outcome {
        if (name.isBlank()) return Outcome(false, "Kaunsi app kholni hai?")

        val entry = AppIndex.find(context, name)
            ?: return Outcome(false, "\"$name\" naam ki koi app nahi mili.")

        val intent = AppIndex.launchIntent(context, entry.packageName)
            ?: return Outcome(false, "${entry.label} khul nahi paayi.")

        context.startActivity(intent)
        return Outcome(true, "${entry.label} khul gayi.")
    }

    private fun openSettings(context: Context): Outcome {
        context.startActivity(
            Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return Outcome(true, "Settings khul gayi.")
    }

    // --------------------------------------------------------------- calls

    /**
     * [label] is the contact name the user already confirmed on screen. It is
     * only used for the message read back, so the lookup is never repeated and
     * cannot land on a different contact than the one that was agreed to.
     */
    private fun call(context: Context, who: String, label: String): Outcome {
        if (who.isBlank()) return Outcome(false, "Kisko call karna hai?")

        val contact = ContactIndex.resolve(context, who)
            ?: return Outcome(false, "\"$who\" contacts mein nahi mila.")

        val name = label.ifBlank { contact.name }

        // The dialler is opened with the number already in it. Placing the call
        // directly would need CALL_PHONE, which this app deliberately does not
        // request — see the manifest for why.
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.number}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        return Outcome(true, "$name ka number dial par hai — call button dabayein.")
    }

    private fun sendSms(context: Context, who: String, body: String, label: String): Outcome {
        if (who.isBlank()) return Outcome(false, "Kisko SMS bhejna hai?")
        if (body.isBlank()) return Outcome(false, "SMS mein kya likhna hai?")

        val contact = ContactIndex.resolve(context, who)
            ?: return Outcome(false, "\"$who\" contacts mein nahi mila.")

        val name = label.ifBlank { contact.name }

        // Handed to the messaging app with the text already written. Sending it
        // silently would need SEND_SMS, which this app does not request.
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}"))
                .putExtra("sms_body", body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return Outcome(true, "$name ke liye SMS tayyar hai — send dabayein.")
    }

    /**
     * WhatsApp has no public API. The chat is opened with the message already
     * typed; sending it needs one tap, or the accessibility service if the user
     * has turned that on.
     */
    private fun whatsapp(
        context: Context,
        who: String,
        body: String,
        label: String,
        hint: String = ""
    ): Outcome {
        val personalInstalled = AppIndex.packageInstalled(context, "com.whatsapp")
        val businessInstalled = AppIndex.packageInstalled(context, "com.whatsapp.w4b")
        if (!personalInstalled && !businessInstalled) {
            return Outcome(false, "WhatsApp install nahi hai.")
        }

        val wantBusiness = hint.contains("business", ignoreCase = true)
        val chosenPackage = when {
            wantBusiness && businessInstalled -> "com.whatsapp.w4b"
            wantBusiness && personalInstalled -> "com.whatsapp"
            personalInstalled -> "com.whatsapp"
            else -> "com.whatsapp.w4b"
        }

        if (who.isBlank()) {
            val intent = AppIndex.launchIntent(context, chosenPackage)
                ?: return Outcome(false, "WhatsApp khul nahi paaya.")
            context.startActivity(intent)
            return Outcome(true, "WhatsApp khul gaya.")
        }

        val contact = ContactIndex.resolve(context, who)
            ?: return Outcome(false, "\"$who\" contacts mein nahi mila.")

        val name = label.ifBlank { contact.name }

        val number = contact.number.filter { it.isDigit() }.let {
            if (it.length == 10) "91$it" else it      // assume India when no code
        }

        val uri = Uri.parse(
            "https://wa.me/$number?text=" + Uri.encode(body)
        )

        // v3.3: wa.me intent ko chosen package par pin karein. Pehle
        // generic intent tha, isliye Business bolne par bhi personal
        // WhatsApp khul jata tha (ya chooser atak jata tha).
        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { viewIntent.setPackage(chosenPackage) }
        context.startActivity(viewIntent)

        val autoSend = SaathiAccessibilityService.isRunning
        if (autoSend && body.isNotBlank()) {
            SaathiAccessibilityService.requestSend()
            // v3.3: pehle yeh jhootha daava karta tha ki message bhej
            // diya, jabki Accessibility ka tap aksar WhatsApp khulne
            // se pehle hi khatam ho jata tha. Ab details mein saaf hai
            // ki agar send na dabe to chat mein send khud dabayein —
            // chat aur message dono pehle se khule hue hain, isliye
            // yeh fallback hamesha kaam karta hai.
            return Outcome(
                true,
                "$name ko WhatsApp message bhej raha hoon.",
                listOf("Agar send button khud na dabe to chat mein send dabayein.")
            )
        }

        return Outcome(
            true,
            if (body.isBlank()) "$name ka chat khul gaya."
            else "$name ka chat khula hai, message likha hua hai — send dabayein."
        )
    }

    // ---------------------------------------------------------------- text

    private fun typeText(context: Context, text: String): Outcome {
        if (text.isBlank()) return Outcome(false, "Kya likhna hai?")

        if (!SaathiAccessibilityService.isRunning) {
            return Outcome(
                false,
                "Kisi aur app mein likhne ke liye Accessibility on karni padegi — " +
                    "Saathi app mein us button se."
            )
        }

        val typed = SaathiAccessibilityService.typeIntoFocusedField(text)
        return if (typed) Outcome(true, "Likh diya.")
        else Outcome(false, "Koi text box focus mein nahi mila. Pehle jahan likhna hai wahan tap karein.")
    }

    // --------------------------------------------------------------- files

    private fun requireStorage(): Outcome? =
        if (FileOps.hasAllFilesAccess()) null
        else Outcome(false, "File ka kaam karne ke liye \"All files access\" deni hogi.")

    private fun createFolder(parent: String, name: String): Outcome {
        requireStorage()?.let { return it }

        return FileOps.createFolder(parent, name).fold(
            onSuccess = { Outcome(true, "Folder ban gaya: ${FileOps.shortPath(it)}") },
            onFailure = { Outcome(false, it.message ?: "Folder nahi bana.") }
        )
    }

    private fun moveOrCopy(command: Command, move: Boolean): Outcome {
        requireStorage()?.let { return it }

        if (command.from.isBlank()) return Outcome(false, "Kaunsi file?")
        if (command.to.isBlank()) return Outcome(false, "Kahan rakhni hai?")

        val source = File(command.from).takeIf { it.exists() }
            ?: FileOps.findFile(command.from)
            ?: return Outcome(false, "\"${command.from}\" naam ki file nahi mili.")

        val dest = FileOps.resolveFolder(command.to)
            ?: return Outcome(false, "\"${command.to}\" folder nahi mila.")

        val result = if (move) FileOps.move(source, dest) else FileOps.copy(source, dest)

        return result.fold(
            onSuccess = {
                val verb = if (move) "move" else "copy"
                Outcome(true, "${source.name} ko ${FileOps.shortPath(dest)} mein $verb kar diya.")
            },
            onFailure = { Outcome(false, it.message ?: "Kaam nahi hua.") }
        )
    }

    private fun deleteFile(what: String): Outcome {
        requireStorage()?.let { return it }
        if (what.isBlank()) return Outcome(false, "Kya delete karna hai?")

        val target = File(what).takeIf { it.exists() }
            ?: FileOps.findFile(what)
            ?: return Outcome(false, "\"$what\" nahi mila.")

        return FileOps.delete(target).fold(
            onSuccess = { Outcome(true, "${target.name} delete kar diya.") },
            onFailure = { Outcome(false, it.message ?: "Delete nahi hua.") }
        )
    }

    private fun listFiles(where: String): Outcome {
        requireStorage()?.let { return it }

        val folder = FileOps.resolveFolder(where)
            ?: return Outcome(false, "\"$where\" folder nahi mila.")

        val items = FileOps.list(folder)
        if (items.isEmpty()) return Outcome(true, "${FileOps.shortPath(folder)} khali hai.")

        return Outcome(
            true,
            "${FileOps.shortPath(folder)} mein ${items.size} cheezein:",
            items.map { if (it.isDirectory) "📁 ${it.name}" else "📄 ${it.name}" }
        )
    }

    private fun searchFile(name: String): Outcome {
        requireStorage()?.let { return it }
        if (name.isBlank()) return Outcome(false, "Kya dhoondhna hai?")

        val found = FileOps.findFiles(name)
        if (found.isEmpty()) return Outcome(false, "\"$name\" nahi mili.")

        return Outcome(
            true,
            if (found.size == 1) "Mili: ${found.first().name}"
            else "${found.size} files mili:",
            found.map { FileOps.shortPath(it) }
        )
    }
}
