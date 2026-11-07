package dev.herdr.android.endpoint

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Frozen generation-1 protocol from Herdr's src/protocol/{wire,endpoint}.rs. */
object EndpointCodec {
    const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    const val MAX_CONTROL_BYTES = 2 * 1024 * 1024
    private const val MAX_CELLS = 1_000_000
    private const val MAX_ITEMS = 100_000

    /** Every encoder returns the complete u32LE-length-prefixed frame. */
    fun hello(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int, surfaceActive: Boolean = true): ByteArray {
        validateGeometry(columns, rows, cellWidthPx, cellHeightPx)
        return control("endpoint.hello.v1", buildJsonObject {
            put("generation", 1)
            put("cell_width_px", cellWidthPx)
            put("cell_height_px", cellHeightPx)
            putJsonObject("surface_size") { put("cols", columns); put("rows", rows) }
            put("pixel_mouse", false)
            put("direct_graphics", false)
            put("endpoint_keybindings", false)
            put("mouse_capture", true)
            put("surface_active", surfaceActive)
            put("surface_reuse", false)
            put("surface_delta", false)
            put("surface_scroll", false)
            putJsonArray("snapshot_codecs") { add("shell.snapshot.v1") }
            putJsonArray("surface_codecs") { add("shell.surface.v1") }
            putJsonArray("input_codecs") { add("shell.input.semantic.v1") }
            putJsonArray("blob_codecs") { add("shell.blob.v1") }
        }.toString())
    }

    fun control(kind: String, data: String): ByteArray = frame(20) { string(kind); string(data) }
    fun request(bootId: String, request: JsonObject): ByteArray {
        val text = request.toString()
        require(text.toByteArray().size <= 1024 * 1024) { "Endpoint request exceeds 1 MiB" }
        require(bootId.toByteArray().size <= 128) { "Invalid endpoint boot ID" }
        return frame(15) { string(bootId); string(text) }
    }
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): ByteArray {
        validateGeometry(columns, rows, cellWidthPx, cellHeightPx)
        return frame(12) { uint(cellWidthPx.toLong()); uint(cellHeightPx.toLong()); uint(columns.toLong()); uint(rows.toLong()); bool(false) }
    }
    fun focus(focused: Boolean): ByteArray = frame(18) { bool(focused) }
    fun detach(): ByteArray = frame(4) {}
    fun paneInput(paneId: String, events: List<PaneInput>): ByteArray = inputs(13, paneId, events)
    fun popupInput(terminalId: String, events: List<PaneInput>): ByteArray = inputs(14, terminalId, events)
    fun clipboardImage(target: GraphicsTarget, extension: String, bytes: ByteArray): ByteArray {
        require(bytes.size <= 16 * 1024 * 1024) { "Clipboard image exceeds 16 MiB" }
        require(extension in setOf("png", "jpg", "jpeg", "webp", "gif")) { "Unsupported image extension" }
        return frame(2, MAX_FRAME_BYTES) {
            when (target) {
                is GraphicsTarget.Pane -> { uint(1); string(target.id) }
                is GraphicsTarget.Popup -> { uint(2); string(target.id) }
            }
            string(extension); bytes(bytes)
        }
    }

    /** Blocking read: use a dedicated IO reader and disconnect the SSH channel to cancel it. */
    fun readMessage(input: InputStream): EndpointMessage {
        val header = ByteArray(4)
        input.readExactly(header)
        val size = header.indices.fold(0L) { value, index -> value or ((header[index].toLong() and 255) shl (index * 8)) }
        if (size !in 1..MAX_FRAME_BYTES.toLong()) throw IOException("Invalid endpoint frame length: $size")
        val payload = ByteArray(size.toInt())
        input.readExactly(payload)
        return decodePayload(payload)
    }

    fun decodePayload(payload: ByteArray): EndpointMessage {
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) throw IOException("Invalid endpoint payload size")
        val reader = Reader(payload)
        val message = with(reader) {
            when (val tag = tag(20)) {
                0 -> EndpointMessage.LegacyWelcome(u32(), tag(1), optional { string() })
                1 -> EndpointMessage.TerminalBytes(u64(), u16(), u16(), bool(), bytes())
                2 -> EndpointMessage.GraphicsBytes(bytes())
                3 -> EndpointMessage.Shutdown(optional { string() })
                4 -> EndpointMessage.Notification(tag(2), string(), optional { string() }, null, null, null)
                5 -> EndpointMessage.Clipboard(string())
                6 -> EndpointMessage.WindowTitle(optional { string() })
                7 -> EndpointMessage.ReloadSoundConfig
                8 -> EndpointMessage.MouseCapture(bool(), bool())
                9 -> EndpointMessage.TerminalBell(u16())
                10 -> EndpointMessage.GraphicsFile(string(), u64(), u32(), u64(), bytes(), string(), optional { assetKey() })
                11 -> EndpointMessage.GraphicsRetired(u64(), u32())
                12 -> throw IOException("Legacy binary snapshot is not part of the negotiated shell.snapshot.v1 codec")
                13 -> EndpointMessage.Surface(surface())
                14 -> {
                    val kind = tag(3)
                    val title = string()
                    val body = optional { string() }
                    optional { tag(1) } // Sound category; host notification policy is client-owned.
                    optional { string() } // Agent label, not a routing identity.
                    val workspace = optional { string() }
                    val tab = optional { string() }
                    val pane = optional { string() }
                    optional { tag(3) } // Desktop toast placement.
                    EndpointMessage.Notification(kind, title, body, pane, workspace, tab)
                }
                15 -> EndpointMessage.Error(string())
                16 -> EndpointMessage.DirectKeyboardProtocol(u16(), byte())
                17 -> EndpointMessage.KeyboardReportAll(bool())
                18 -> EndpointMessage.ResponseChunk(string(), string(), bool(), bytes())
                19 -> EndpointMessage.Patch(patch())
                20 -> EndpointMessage.Control(string(), string())
                else -> throw IOException("Unsupported endpoint message: $tag")
            }
        }
        if (reader.remaining != 0) throw IOException("Trailing bytes in endpoint message")
        return message
    }

    private fun inputs(tag: Int, target: String, events: List<PaneInput>): ByteArray {
        require(events.size <= 4096) { "Too many input events" }
        var expandedEvents = 0L
        var payloadBytes = 0L
        // Mirrors server/client_transport.rs: repeats and vertical wheel lines expand;
        // generated key text, commits and all pastes share the same 1 MiB budget.
        fun textSize(text: String): Long {
            require(text.length <= 1024 * 1024) { "Input payload exceeds 1 MiB" }
            return text.toByteArray(Charsets.UTF_8).size.toLong()
        }
        events.forEach { event ->
            expandedEvents += when (event) {
                is PaneInput.Key -> event.repeatCount.coerceAtLeast(1).toLong()
                is PaneInput.Mouse -> if (event.kind == MouseKind.ScrollUp || event.kind == MouseKind.ScrollDown)
                    event.lines.coerceAtLeast(1).toLong() else 1L
                else -> 1L
            }
            require(expandedEvents <= 4096) { "Too many expanded input events" }
            payloadBytes += when (event) {
                is PaneInput.Key -> event.generatedText?.let { textSize(it) * event.repeatCount.coerceAtLeast(1) } ?: 0L
                is PaneInput.TextCommit -> textSize(event.text)
                is PaneInput.Paste -> textSize(event.text)
                is PaneInput.Mouse -> 0L
            }
            require(payloadBytes <= 1024 * 1024) { "Input payload exceeds 1 MiB" }
        }
        return frame(tag) { string(target); uint(events.size.toLong()); events.forEach { input(it) } }
    }

    private fun validateGeometry(columns: Int, rows: Int, width: Int, height: Int) {
        require(columns in 1..4096 && rows in 1..4096 && columns.toLong() * rows <= MAX_CELLS)
        require(width in 0..4096 && height in 0..4096)
    }

    private fun frame(tag: Int, limit: Int = MAX_CONTROL_BYTES, body: Writer.() -> Unit): ByteArray {
        val writer = Writer(limit)
        writer.uint(tag.toLong())
        writer.body()
        val payload = writer.output.toByteArray()
        return ByteArray(4 + payload.size).also { frame ->
            repeat(4) { frame[it] = (payload.size ushr (it * 8)).toByte() }
            payload.copyInto(frame, 4)
        }
    }

    private class Writer(private val limit: Int) {
        val output = ByteArrayOutputStream()
        fun byte(value: Int) {
            require(value in 0..255)
            require(output.size() < limit) { "Endpoint message is too large" }
            output.write(value)
        }
        fun bool(value: Boolean) = byte(if (value) 1 else 0)
        fun uint(value: Long) {
            require(value >= 0)
            when {
                value <= 250 -> byte(value.toInt())
                value <= 65535 -> { byte(251); littleEndian(value, 2) }
                value <= 0xffffffffL -> { byte(252); littleEndian(value, 4) }
                else -> { byte(253); littleEndian(value, 8) }
            }
        }
        private fun littleEndian(value: Long, length: Int) { repeat(length) { byte(((value ushr (8 * it)) and 255).toInt()) } }
        fun bytes(value: ByteArray) {
            uint(value.size.toLong())
            raw(value)
        }
        private fun raw(value: ByteArray) {
            require(value.size <= limit - output.size()) { "Endpoint message is too large" }
            output.write(value)
        }
        fun string(value: String) {
            require(value.length <= limit) { "Endpoint string is too large" }
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
            bytes(ByteArray(buffer.remaining()).also { buffer.get(it) })
        }
        private fun optionalNumber(value: Long?) { bool(value != null); value?.let { require(it in 0..0xffffffffL); uint(it) } }
        private fun optionalText(value: String?) { bool(value != null); value?.let { string(it) } }
        private fun short(value: Int) { require(value in 0..65535); uint(value.toLong()) }
        private fun word(value: Long) { require(value in 0..0xffffffffL); uint(value) }
        fun input(event: PaneInput) {
            when (event) {
                is PaneInput.Key -> {
                    uint(0)
                    when (val key = event.code) {
                        is PaneKey.Special -> uint(key.ordinal.toLong())
                        is PaneKey.Character -> {
                            require(Character.isValidCodePoint(key.codePoint) && key.codePoint !in 0xd800..0xdfff)
                            uint(15); raw(String(Character.toChars(key.codePoint)).toByteArray(Charsets.UTF_8))
                        }
                        is PaneKey.Function -> { require(key.number in 1..255); uint(16); byte(key.number) }
                        PaneKey.Null -> uint(17)
                    }
                    byte(event.modifiers); uint(event.kind.ordinal.toLong()); short(event.repeatCount)
                    optionalNumber(event.shiftedCodePoint); optionalText(event.generatedText)
                    bool(event.tracksRelease); optionalNumber(event.physicalKeyId); bool(false) // Windows console record.
                }
                is PaneInput.TextCommit -> { uint(1); string(event.text) }
                is PaneInput.Paste -> { uint(3); string(event.text) }
                is PaneInput.Mouse -> {
                    uint(2)
                    when (val kind = event.kind) {
                        is MouseKind.Down -> { uint(0); uint(kind.button.ordinal.toLong()) }
                        is MouseKind.Up -> { uint(1); uint(kind.button.ordinal.toLong()) }
                        is MouseKind.Drag -> { uint(2); uint(kind.button.ordinal.toLong()) }
                        MouseKind.Moved -> uint(3)
                        MouseKind.ScrollUp -> uint(4)
                        MouseKind.ScrollDown -> uint(5)
                        MouseKind.ScrollLeft -> uint(6)
                        MouseKind.ScrollRight -> uint(7)
                    }
                    when (val position = event.position) {
                        is MousePosition.Cell -> { uint(0); short(position.column); short(position.row) }
                        is MousePosition.Pixels -> { uint(1); word(position.x); word(position.y); short(position.column); short(position.row) }
                    }
                    bool(event.geometry != null)
                    event.geometry?.let { short(it.columns); short(it.rows); word(it.widthPixels); word(it.heightPixels) }
                    byte(event.modifiers); short(event.lines)
                }
            }
        }
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        val remaining: Int get() = data.size - position
        fun byte(): Int {
            if (remaining == 0) throw EOFException("Truncated endpoint payload")
            return data[position++].toInt() and 255
        }
        fun bool(): Boolean = when (byte()) { 0 -> false; 1 -> true; else -> throw IOException("Invalid boolean") }
        fun u64(): Long = unsigned(8)
        private fun unsigned(width: Int): Long = when (val marker = byte()) {
            in 0..250 -> marker.toLong()
            251 -> littleEndian(2)
            252 -> if (width >= 4) littleEndian(4) else throw IOException("Invalid integer width")
            253 -> if (width >= 8) littleEndian(8) else throw IOException("Invalid integer width")
            else -> throw IOException("Unsupported unsigned integer width")
        }
        private fun littleEndian(length: Int): Long {
            if (length > remaining) throw EOFException("Truncated integer")
            var value = 0L
            repeat(length) { value = value or (byte().toLong() shl (8 * it)) }
            return value
        }
        private fun bounded(max: Long, width: Int = 8): Long = unsigned(width).also { if (it < 0 || it > max) throw IOException("Integer exceeds protocol bounds") }
        fun u32(): Long = bounded(0xffffffffL, 4)
        fun u16(): Int = bounded(65535, 2).toInt()
        fun tag(max: Int): Int = bounded(max.toLong(), 4).toInt()
        fun <T> optional(read: Reader.() -> T): T? = if (bool()) read() else null
        private fun count(limit: Int, minimumBytes: Int = 1): Int {
            val size = bounded(limit.toLong()).toInt()
            if (size > remaining / minimumBytes) throw IOException("Collection exceeds payload")
            return size
        }
        fun bytes(limit: Int = MAX_FRAME_BYTES): ByteArray {
            val length = count(limit)
            return data.copyOfRange(position, position + length).also { position += length }
        }
        fun string(limit: Int = MAX_CONTROL_BYTES): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes(limit))).toString()
        private fun <T> list(limit: Int = MAX_ITEMS, minimumBytes: Int = 1, read: Reader.() -> T): List<T> =
            List(count(limit, minimumBytes)) { read() }
        private fun rect(): SurfaceRect = SurfaceRect(u16(), u16(), u16(), u16())
        private fun cursor(): SurfaceCursor = SurfaceCursor(u16(), u16(), bool(), byte())
        private fun cell(): SurfaceCell = SurfaceCell(string(4096), u32().toInt(), u32().toInt(), u16(), bool(), optional { u32() })
        private fun grid(): SurfaceGrid {
            val cells = list(MAX_CELLS, 6) { cell() }
            val width = u16()
            val height = u16()
            if (width > 4096 || height > 4096 || width.toLong() * height != cells.size.toLong()) throw IOException("Invalid surface dimensions")
            val cursor = optional { cursor() }
            if (cursor != null && (cursor.x >= width || cursor.y >= height)) throw IOException("Cursor is outside the grid")
            val hyperlinks = list { string() }
            if (cells.any { it.hyperlink != null && it.hyperlink >= hyperlinks.size }) throw IOException("Invalid hyperlink index")
            return SurfaceGrid(cells, width, height, cursor, hyperlinks, bytes())
        }
        private fun pane(): SurfacePane = SurfacePane(string(), u64(), rect(), rect(), optional { rect() },
            optional { SurfaceScroll(u64(), u64(), u64()) }, bool(), bool(), bool(), bool(), u32(), u32())
        private fun split(): SurfaceSplit = SurfaceSplit(SplitDirection.entries[tag(1)], u16(), rect(), rect(), list(64) { bool() })
        private fun popupSize(): PopupSize = when (tag(1)) { 0 -> PopupSize.Cells(u16()); else -> PopupSize.Percent(byte()) }
        private fun popup(): SurfacePopup = SurfacePopup(string(), string(), optional { popupSize() }, optional { popupSize() }, grid(), bool(), bool(), u32(), u32())
        fun assetKey(): GraphicsAssetKey {
            val source = when (tag(1)) {
                0 -> GraphicsSource.Terminal(when (tag(1)) { 0 -> GraphicsTarget.Pane(string()); else -> GraphicsTarget.Popup(string()) }, u32())
                else -> GraphicsSource.PaneLayer(string(), string())
            }
            return GraphicsAssetKey(source, u32(), u32(), GraphicsFormat.entries[tag(2)], u64(), u64())
        }
        private fun placement(): GraphicsPlacement {
            val key = assetKey()
            val id = u32()
            val x = u16(); val y = u16()
            val columns = u32(); val rows = u32()
            val sourceX = u32(); val sourceY = u32(); val sourceWidth = u32(); val sourceHeight = u32()
            val xOffset = u32(); val yOffset = u32()
            val zigzag = u32()
            val z = ((zigzag ushr 1) xor -(zigzag and 1)).toInt()
            return GraphicsPlacement(key, id, x, y, columns, rows, sourceX, sourceY, sourceWidth, sourceHeight, xOffset, yOffset, z, u32())
        }
        private fun scene(): GraphicsScene = GraphicsScene(
            list { val key = assetKey(); val bytes = bytes(); if (key.dataLength != bytes.size.toLong()) throw IOException("Graphics asset size mismatch"); GraphicsAsset(key, bytes) },
            list { placement() }, list { assetKey() },
        )
        fun surface(): SurfaceFrame = SurfaceFrame(string(), u64(), u64(), grid(), list { pane() }, list { split() }, optional { popup() }, scene())
        fun patch(): SurfacePatch = SurfacePatch(string(), u64(), u64(), u64(),
            list { PatchRow(u16(), u16(), list(MAX_CELLS, 6) { cell() }) }, list { pane() }, optional { cursor() })
    }
}

private fun InputStream.readExactly(destination: ByteArray) {
    var offset = 0
    while (offset < destination.size) {
        val count = read(destination, offset, destination.size - offset)
        if (count < 0) throw EOFException("Endpoint connection closed inside a frame")
        if (count == 0) {
            val next = read()
            if (next < 0) throw EOFException("Endpoint connection closed inside a frame")
            destination[offset++] = next.toByte()
        } else offset += count
    }
}
