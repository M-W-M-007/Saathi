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
        val details: List<String> = emptyList(),
        /** Absolute folder path for OPEN_FOLDER, so "Files mein kholo"
         * can try to open that exact folder, not a generic Files screen. */
        val folderPath: String = ""
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
        Command.Action.OPEN_FOLDER -> openFolder(context, command.from, command.to)
        Command.Action.OPEN_FOLDER_IN_FILES -> openFolderInFiles(context, command.from)
        Command.Action.CREATE_FILE -> createFile(command.from, command.to)
        Command.Action.SEARCH_FILE -> searchFile(command.target)
        Command.Action.OPEN_SETTINGS -> openSettings(context)
        Command.Action.UNKNOWN -> Outcome(false, "Yeh samajh nahi aaya.")
    }

    // ---------------------------------------------------------------- apps

    // v3.7: folder kholna, Siri-style. v3.4 mein Files app bhi khul
    // jati thi, aur user ko lagta tha "kewal file manager khula,
    // folder nahi khula" — kyunki Android kisi folder ko seedha
    // Files app mein usi jagah par nahi kholta, woh hamesha Recent
    // ya generic jagah par khulta hai. Isliye ab default yeh hai:
    // folder Saathi ke panel ke andar hi khulta hai, uski list
    // panel par dikhti hai, aur panel tab tak rehta hai jab tak
    // user band na kare. Files app ab khud nahi khulti — panel
    // par "Files mein kholo" ka button milta hai, jo VoiceService
    // execute() mein lagata hai, taaki user chahe toh wahan bhi
    // ja sake, lekin folder ka saboot pehle panel par hi ho.
    private fun openFolder(context: Context, parent: String, name: String): Outcome {
        requireStorage()?.let { return it }

        // "download folder open karo" mein to = download hai, matlab
        // Download folder khud. "download mein test folder" mein
        // parent = download, to = test, matlab Download ke andar test.
        val folder = when {
            name.isBlank() -> null
            parent.isBlank() -> FileOps.resolveFolder(name)
            else -> {
                val base = FileOps.resolveFolder(parent)
                if (base != null) {
                    val child = File(base, name)
                    if (child.isDirectory) child else FileOps.resolveFolder(name)
                } else {
                    FileOps.resolveFolder(name)
                }
            }
        } ?: return Outcome(false, "\"$name\" folder nahi mila.")

        val items = FileOps.list(folder)
        val details = items.map { if (it.isDirectory) "📁 ${it.name}" else "📄 ${it.name}" }

        return Outcome(
            true,
            "${FileOps.shortPath(folder)} khul gaya — usme ${items.size} cheezein hain.",
            details,
            folderPath = folder.absolutePath
        )
    }

    // v3.8: "Files mein kholo" ke liye specific folder ka Documents
    // URI try karo. Android ka system Files app is URI ko usi folder
    // par khol sakta hai; OnePlus/Google Files isko na maane toh
    // generic Files par gir jayega, aur message mein woh saaf hoga.
    // Ye hissa device par hi verify hoga — har Files app is URI
    // ko nahi maanti, isliye isko guaranteed mat samajhna.
    private fun openFolderInFiles(context: Context, folderPath: String): Outcome {
        requireStorage()?.let { return it }
        if (folderPath.isBlank()) return Outcome(false, "Folder ka path nahi mila.")

        val prefix = "/storage/emulated/0/"
        if (folderPath.startsWith(prefix)) {
            val relative = folderPath.removePrefix(prefix)
            val uri = Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3A" +
                    relative.replace("/", "%2F")
            )
            for (pkg in listOf(
                "com.google.android.documentsui",
                "com.android.documentsui"
            )) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (ok) return Outcome(true, "Files app mein folder khul gaya.")
            }
        }

        // Fallback: generic Files app, lekin message mein saaf hai
        // ki specific folder nahi khula.
        val managers = listOf(
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.oneplus.filemanager",
            "com.samsung.android.myfiles.MyFiles",
            "com.mi.android.globalFileexplorer"
        )
        for (pkg in managers) {
            val intent = AppIndex.launchIntent(context, pkg)
            if (intent != null) {
                val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (ok) return Outcome(true, "Files app khul gayi, lekin specific folder par nahi — panel list hi sahi folder hai.")
            }
        }
        return Outcome(false, "Files app nahi khuli.")
    }

    // v3.7: text file banana. Naam mein .txt na ho toh laga dein,
    // aur pehle se maujood naam par overwrite kabhi nahi — (2) banega.
    private fun createFile(parent: String, name: String): Outcome {
        requireStorage()?.let { return it }
        if (name.isBlank()) return Outcome(false, "File ka naam nahi mila.")

        val folder = if (parent.isBlank()) FileOps.resolveFolder("") else FileOps.resolveFolder(parent)
            ?: return Outcome(false, "\"$parent\" folder nahi mila.")

        val baseName = if (name.endsWith(".txt", ignoreCase = true)) name else "$name.txt"
        var target = File(folder, baseName)
        var n = 2
        while (target.exists() && n < 1000) {
            val stem = baseName.removeSuffix(".txt")
            target = File(folder, "$stem ($n).txt")
            n++
        }

        return runCatching {
            require(target.createNewFile()) { "File nahi bani." }
            Outcome(true, "${FileOps.shortPath(target)} ban gayi.")
        }.getOrElse { Outcome(false, it.message ?: "File nahi bani.") }
    }

    private fun openApp(context: Context, name: String): Outcome {
        if (name.isBlank()) return Outcome(false, "Kaunsi app kholni hai?")

        // v3.4: Business ke liye AppIndex.find() package pin karta hai
        // (label par bharosa nahi), isliye "WhatsApp Business" yahan
        // bhi w4b hi kholega.
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
