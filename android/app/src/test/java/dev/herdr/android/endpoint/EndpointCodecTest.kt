package dev.herdr.android.endpoint

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointCodecTest {
    @Test fun `named controls use frozen bincode tag and little endian frame length`() {
        // Herdr wire.rs endpoint_control_empty_payload_bytes fixture: [20, 0, 0].
        assertArrayEquals(hex("03 00 00 00 14 00 00"), EndpointCodec.control("", ""))
        val result = EndpointCodec.readMessage(ByteArrayInputStream(hex("05 00 00 00 14 01 78 01 79")))
        assertEquals(EndpointMessage.Control("x", "y"), result)
    }

    @Test fun `semantic key event encodes every frozen field including absent windows record`() {
        assertArrayEquals(hex("12 00 00 00 0d 05 77 31 3a 70 31 01 00 01 00 00 01 00 00 00 00 00"),
            EndpointCodec.paneInput("w1:p1", listOf(PaneInput.Key(PaneKey.Special.Enter))))
    }

    @Test fun `unicode IME commits and paste remain separate events`() {
        val commit = EndpointCodec.paneInput("p", listOf(PaneInput.TextCommit("я")))
        assertArrayEquals(hex("08 00 00 00 0d 01 70 01 01 02 d1 8f"), commit)
        val paste = EndpointCodec.paneInput("p", listOf(PaneInput.Paste("я")))
        assertArrayEquals(hex("08 00 00 00 0d 01 70 01 03 02 d1 8f"), paste)
    }

    @Test fun `resize uses bincode variable integers not fixed u16`() {
        assertArrayEquals(hex("08 00 00 00 0c 08 10 fb 2c 01 18 00"), EndpointCodec.resize(300, 24, 8, 16))
    }

    @Test fun `hello negotiates stable generation without optional delta encodings`() {
        val control = EndpointCodec.readMessage(ByteArrayInputStream(EndpointCodec.hello(80, 24, 8, 16))) as EndpointMessage.Control
        assertEquals("endpoint.hello.v1", control.kind)
        assertTrue(control.data.contains("\"shell.surface.v1\""))
        assertTrue(control.data.contains("\"surface_delta\":false"))
        assertTrue(control.data.contains("\"generation\":1"))
    }

    @Test fun `complete surface and incremental patch reconstruct the same grid`() {
        // Source-order golden vector: one A cell, 1x1 grid, visible steady block cursor, no panes/images.
        val full = hex("0d 01 62 01 02 01 01 41 00 00 00 00 00 01 01 01 00 00 01 02 00 00 00 00 00 00 00 00")
        val surface = (EndpointCodec.decodePayload(full) as EndpointMessage.Surface).value
        assertEquals("A", surface.grid.cells.single().symbol)
        assertEquals(2, surface.grid.cursor?.shape)
        assertEquals(2L, surface.surfaceRevision)
        // Same boot/projection, base=2, revision=3; one changed B cell at (0,0), no cursor.
        val patchBytes = hex("13 01 62 01 02 03 01 00 00 01 01 42 00 00 00 00 00 00 00")
        val patch = (EndpointCodec.decodePayload(patchBytes) as EndpointMessage.Patch).value
        val updated = surface.applyPatch(patch)
        assertEquals("B", updated.grid.cells.single().symbol)
        assertEquals(null, updated.grid.cursor)
        assertEquals("A", surface.grid.cells.single().symbol)
        assertEquals(3L, updated.surfaceRevision)
        expectIo { updated.applyPatch(patch) }
        expectIo { surface.applyPatch(patch.copy(surfaceRevision = 4)) }
        expectIo { surface.applyPatch(patch.copy(bootId = "another boot")) }
        expectIo { surface.applyPatch(patch.copy(rows = listOf(patch.rows.single().copy(x = 1)))) }
    }

    @Test fun `partial stream reads are reassembled`() {
        val bytes = EndpointCodec.control("health", "ok")
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
        }
        assertEquals(EndpointMessage.Control("health", "ok"), EndpointCodec.readMessage(stream))
    }

    @Test fun `malformed lengths booleans unicode and trailing data fail closed`() {
        expectIo { EndpointCodec.readMessage(ByteArrayInputStream(hex("ff ff ff 7f"))) }
        expectIo { EndpointCodec.readMessage(ByteArrayInputStream(hex("04 00 00 00 14"))) }
        expectIo { EndpointCodec.decodePayload(hex("14 00 00 00")) }
        expectIo { EndpointCodec.decodePayload(hex("11 02")) }
        expectIo { EndpointCodec.decodePayload(hex("14 01 ff 00")) }
        expectIo { EndpointCodec.decodePayload(hex("14 fd ff ff ff ff ff ff ff 7f")) }
        expectIo { EndpointCodec.decodePayload(hex("0d 01 62 01 02 fd ff ff ff ff ff ff ff 7f")) }
    }

    @Test fun `response chunks preserve correlation and do not execute opaque payload`() {
        val message = EndpointCodec.decodePayload(hex("12 01 62 01 72 01 02 7b 7d")) as EndpointMessage.ResponseChunk
        assertEquals("b", message.bootId)
        assertEquals("r", message.requestId)
        assertTrue(message.finalChunk)
        assertArrayEquals("{}".toByteArray(), message.data)
    }

    @Test fun `expanded key and vertical mouse limits match server boundaries`() {
        val key = PaneInput.Key(PaneKey.Special.Enter, repeatCount = 4096)
        EndpointCodec.paneInput("p", listOf(key))
        assertThrows(IllegalArgumentException::class.java) { EndpointCodec.paneInput("p", listOf(key, key.copy(repeatCount = 0))) }
        val wheel = PaneInput.Mouse(MouseKind.ScrollDown, MousePosition.Cell(0, 0), lines = 4096)
        EndpointCodec.popupInput("p", listOf(wheel))
        assertThrows(IllegalArgumentException::class.java) { EndpointCodec.popupInput("p", listOf(wheel.copy(lines = 4097))) }
        EndpointCodec.paneInput("p", listOf(wheel.copy(kind = MouseKind.ScrollLeft, lines = 65535)))
    }

    @Test fun `UTF8 commits generated text and paste share payload budget`() {
        val oneMiB = "я".repeat(512 * 1024)
        EndpointCodec.paneInput("p", listOf(PaneInput.Paste(oneMiB)))
        EndpointCodec.paneInput("p", listOf(PaneInput.TextCommit(oneMiB)))
        assertThrows(IllegalArgumentException::class.java) { EndpointCodec.paneInput("p", listOf(PaneInput.Paste(oneMiB), PaneInput.Paste("x"))) }
        assertThrows(IllegalArgumentException::class.java) { EndpointCodec.paneInput("p", listOf(PaneInput.Paste(oneMiB), PaneInput.TextCommit("x"))) }
        val key = PaneInput.Key(PaneKey.Special.Enter, repeatCount = 4096, generatedText = "x".repeat(256))
        EndpointCodec.paneInput("p", listOf(key))
        assertThrows(IllegalArgumentException::class.java) { EndpointCodec.paneInput("p", listOf(key.copy(generatedText = "x".repeat(257)))) }
        assertThrows(IllegalArgumentException::class.java) { EndpointCodec.paneInput("p", listOf(PaneInput.TextCommit(oneMiB + "x"))) }
    }

    private fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun expectIo(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IOException) { rejected = true }
        assertTrue("Expected malformed protocol data to be rejected", rejected)
    }
}
