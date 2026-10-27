package com.waz.saathi

/**
 * The last few things that were said and done.
 *
 * Without this every sentence started from nothing, so "nahi, business wala"
 * was meaningless — the assistant had no idea what "wala" referred to and the
 * user had to repeat the whole command, usually getting the same wrong answer.
 * Three turns is enough for a correction and small enough to send with every
 * request without slowing it down.
 */
object Conversation {

    private const val KEEP = 3

    private val turns = ArrayDeque<String>()

    @Synchronized
    fun add(spoken: String, result: String) {
        if (spoken.isBlank()) return
        turns.addLast("User bola: \"$spoken\" -> Saathi ne kiya: $result")
        while (turns.size > KEEP) turns.removeFirst()
    }

    @Synchronized
    fun recent(): String = turns.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = turns.isEmpty()

    @Synchronized
    fun clear() = turns.clear()
}
