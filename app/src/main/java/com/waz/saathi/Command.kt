package com.waz.saathi

/**
 * One understood instruction. Everything the user says is turned into exactly
 * one of these before anything happens, so the part that understands speech and
 * the part that acts on it stay independent: the parser can be swapped from
 * rules to a model without touching the actions, and an action can be tested
 * without speaking a word.
 */
data class Command(
    val action: Action,
    /** App name, contact name, phone number — whatever the action acts on. */
    val target: String = "",
    /** Message body, text to type, or the new folder name. */
    val text: String = "",
    /** Source path for file moves and copies. */
    val from: String = "",
    /** Destination path for file moves, copies and new folders. */
    val to: String = "",
    /** What the assistant will say back before doing it. */
    val speak: String = "",
    /**
     * The words the user actually used for [target], before any matching.
     * Kept so the UI can offer the near misses — "WhatsApp" and "WhatsApp
     * Business" are one letter of recognition apart, and picking silently is
     * how the wrong one kept opening.
     */
    val hint: String = ""
) {
    enum class Action {
        OPEN_APP,
        CALL,
        SEND_SMS,
        WHATSAPP,
        TYPE_TEXT,
        CREATE_FOLDER,
        MOVE_FILE,
        COPY_FILE,
        DELETE_FILE,
        LIST_FILES,
        OPEN_FOLDER,
        OPEN_FOLDER_IN_FILES,
        CREATE_FILE,
        SEARCH_FILE,
        OPEN_SETTINGS,
        UNKNOWN
    }

    /**
     * Actions that change something the user cannot easily undo get confirmed
     * out loud before they run. Speech recognition mishears names and numbers
     * often enough that silently dialling or deleting on a wrong guess is not
     * acceptable.
     */
    val needsConfirmation: Boolean
        get() = action in setOf(
            Action.CALL,
            Action.SEND_SMS,
            Action.WHATSAPP,
            Action.MOVE_FILE,
            Action.DELETE_FILE
        )

    fun describe(): String = when (action) {
        Action.OPEN_APP -> "$target kholun?"
        Action.CALL -> "$target ko call karun?"
        Action.SEND_SMS -> "$target ko SMS bhejun: \"$text\"?"
        Action.WHATSAPP -> "$target ko WhatsApp par bhejun: \"$text\"?"
        Action.TYPE_TEXT -> "Likhun: \"$text\"?"
        Action.CREATE_FOLDER -> "Folder banaun: $to"
        Action.MOVE_FILE -> "$from ko $to mein move karun?"
        Action.COPY_FILE -> "$from ko $to mein copy karun?"
        Action.DELETE_FILE -> "$from delete karun?"
        Action.LIST_FILES -> "$from ki files dikhaun"
        Action.OPEN_FOLDER -> "$to folder kholun"
        Action.OPEN_FOLDER_IN_FILES -> "Files app mein $to kholun"
        Action.CREATE_FILE -> "File banaun: $to"
        Action.SEARCH_FILE -> "$target dhoondhun"
        Action.OPEN_SETTINGS -> "Settings kholun"
        Action.UNKNOWN -> "Samajh nahi aaya"
    }
}
