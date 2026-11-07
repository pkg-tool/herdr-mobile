package dev.herdr.android

import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.PaneKey

/** IME Enter executes; only an explicit clipboard paste uses bracketed paste. */
internal fun terminalCommit(text: String): List<PaneInput> = buildList {
    val value = text.take(262_144).replace("\r\n", "\n")
    val chunk = StringBuilder()
    fun flush() { if (chunk.isNotEmpty()) { add(PaneInput.TextCommit(chunk.toString())); chunk.clear() } }
    value.forEach { char ->
        when (char) {
            '\r', '\n' -> { flush(); add(PaneInput.Key(PaneKey.Special.Enter)) }
            '\t' -> { flush(); add(PaneInput.Key(PaneKey.Special.Tab)) }
            else -> if (char >= ' ') chunk.append(char)
        }
    }
    flush()
}

internal fun withOneShotModifiers(events: List<PaneInput>, modifiers: Int): List<PaneInput> {
    if (modifiers == 0) return events
    var pending = true
    return events.flatMap { event ->
        when {
            pending && event is PaneInput.Key -> { pending = false; listOf(event.copy(modifiers = event.modifiers or modifiers)) }
            pending && event is PaneInput.TextCommit && event.text.isNotEmpty() -> {
                pending = false
                val code = event.text.codePointAt(0)
                val rest = event.text.substring(Character.charCount(code))
                listOfNotNull(PaneInput.Key(PaneKey.Character(code), modifiers), rest.takeIf { it.isNotEmpty() }?.let(PaneInput::TextCommit))
            }
            else -> listOf(event)
        }
    }
}
