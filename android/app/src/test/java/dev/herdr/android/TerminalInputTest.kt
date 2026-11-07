package dev.herdr.android

import dev.herdr.android.endpoint.*
import org.junit.Assert.*
import org.junit.Test

class TerminalInputTest {
    @Test fun `IME newline executes instead of inserting a bracketed paste`() {
        assertEquals(listOf(PaneInput.TextCommit("echo hello"), PaneInput.Key(PaneKey.Special.Enter)), terminalCommit("echo hello\r\n"))
        assertEquals(listOf(PaneInput.Key(PaneKey.Special.Tab)), terminalCommit("\t"))
        assertEquals(PaneInput.Key(PaneKey.Character('2'.code)), keyChord("2", 0))
        assertNull(keyChord("unexpected-key", 0))
    }
    @Test fun `one shot modifier applies only to first committed character`() {
        assertEquals(listOf(PaneInput.Key(PaneKey.Character('c'.code), 2), PaneInput.TextCommit("rest")),
            withOneShotModifiers(listOf(PaneInput.TextCommit("crest")), 2))
        val paste = listOf(PaneInput.Paste("verbatim\ntext"))
        assertEquals(paste, withOneShotModifiers(paste, 2))
    }
}
