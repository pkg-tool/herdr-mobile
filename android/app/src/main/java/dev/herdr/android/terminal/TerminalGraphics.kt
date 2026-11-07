package dev.herdr.android.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dev.herdr.android.endpoint.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One instance per terminal view. update/draw/close are confined to the UI thread. */
class TerminalGraphics(private val onWarning: (String?) -> Unit = {}) {
    private class Entry(val key: GraphicsAssetKey, var encoded: ByteArray?, var reservation: Long) {
        var bitmap: Bitmap? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = Mutex()
    private val entries = LinkedHashMap<GraphicsAssetKey, Entry>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var decoding: Entry? = null
    private var bootId: String? = null
    private var closed = false
    private var desired = emptySet<GraphicsAssetKey>()
    private var lastWarning: String? = null
    private var repaint: (() -> Unit)? = null

    fun update(frame: SurfaceFrame, invalidate: () -> Unit) {
        if (closed) return
        repaint = invalidate
        if (bootId != frame.bootId) {
            entries.values.forEach { if (it !== decoding) it.encoded = null }
            entries.clear()
            bootId = frame.bootId
        }
        desired = frame.graphics.placements.mapTo(HashSet()) { it.asset }.apply { addAll(frame.graphics.retainedAssets) }
        entries.values.filter { it.key !in desired }.forEach { if (it !== decoding) it.encoded = null }
        entries.keys.retainAll(desired)
        var reserved = entries.values.sumOf { it.reservation } +
            (decoding?.takeIf { entries[it.key] !== it }?.reservation ?: 0L)
        for (asset in frame.graphics.assets) {
            if (asset.key !in desired || asset.key in entries || entries.size >= MAX_ASSETS) continue
            val pixels = imagePixels(asset.key) ?: continue
            if (asset.key.dataLength != asset.bytes.size.toLong()) continue
            val cost = asset.bytes.size.toLong() + pixels * 4 + asset.key.imageWidth * 4
            if (cost > CACHE_BYTES - reserved) continue
            val entry = Entry(asset.key, asset.bytes, cost)
            entries[asset.key] = entry
            reserved += cost
            scope.launch {
                // Serial decoding bounds temporary pixel memory. Entries survive newer frames
                // that refer to the same asset without retransmitting its bytes.
                decoder.withLock {
                    if (entries[entry.key] !== entry) return@withLock
                    val bytes = entry.encoded ?: return@withLock
                    decoding = entry
                    val bitmap = try { withContext(Dispatchers.Default) { decode(entry.key, bytes) } }
                        finally { decoding = null; entry.encoded = null }
                    if (entries[entry.key] === entry && !closed) {
                        entry.encoded = null
                        entry.bitmap = bitmap
                        entry.reservation = bitmap?.allocationByteCount?.toLong() ?: 0L
                        updateWarning()
                        repaint?.invoke()
                    }
                }
            }
        }
        updateWarning()
    }

    private fun updateWarning() {
        val omitted = desired.count { key -> entries[key]?.let { it.bitmap == null && it.encoded == null } ?: true }
        val warning = if (omitted == 0) null else
            "Images not displayed: $omitted. Data is unavailable, invalid, or exceeds the size or memory limit. Refresh; if the error persists, use a smaller image."
        if (warning != lastWarning) { lastWarning = warning; onWarning(warning) }
    }

    /** Call after cell backgrounds (false), then after glyphs (true), with no canvas translation. */
    fun draw(canvas: Canvas, frame: SurfaceFrame, cellWidth: Float, cellHeight: Float, foreground: Boolean) {
        if (closed || frame.bootId != bootId || cellWidth <= 0f || cellHeight <= 0f) return
        val popup = frame.popup
        val popupRect = popup?.let {
            val width = it.grid.width * cellWidth
            val height = it.grid.height * cellHeight
            val left = ((canvas.width - width) / 2).coerceAtLeast(0f)
            val top = ((canvas.height - height) / 2).coerceAtLeast(0f)
            RectF(left, top, left + width, top + height)
        }
        frame.graphics.placements.asSequence().filter { (it.z >= 0) == foreground }.sortedBy { it.z }.forEach { placement ->
            val bitmap = entries[placement.asset]?.bitmap ?: return@forEach
            val source = placement.asset.source
            val target = (source as? GraphicsSource.Terminal)?.target
            val popupTarget = target as? GraphicsTarget.Popup
            val originX: Float
            val originY: Float
            val clip: RectF
            if (popupTarget != null) {
                if (popup == null || popupRect == null || popupTarget.id != popup.terminalId) return@forEach
                originX = popupRect.left; originY = popupRect.top; clip = popupRect
            } else {
                val paneId = when (source) {
                    is GraphicsSource.PaneLayer -> source.paneId
                    is GraphicsSource.Terminal -> (source.target as? GraphicsTarget.Pane)?.id
                }
                val pane = frame.panes.find { it.paneId == paneId } ?: return@forEach
                originX = 0f; originY = 0f
                clip = pane.innerRect.let { RectF(it.x * cellWidth, it.y * cellHeight, (it.x + it.width) * cellWidth, (it.y + it.height) * cellHeight) }
            }
            val sourceRect = crop(placement, bitmap.width, bitmap.height) ?: return@forEach
            val displayWidth = placement.columns * cellWidth
            val displayHeight = placement.rows * cellHeight
            if (!displayWidth.isFinite() || !displayHeight.isFinite() || displayWidth <= 0 || displayHeight <= 0) return@forEach
            // Offsets shrink the image inside its cell allocation (surface.rs:piece_source_span).
            val left = originX + placement.x * cellWidth
            val top = originY + placement.y * cellHeight
            val destination = RectF(left + placement.xOffset.toFloat().coerceAtMost((displayWidth - 1).coerceAtLeast(0f)),
                top + placement.yOffset.toFloat().coerceAtMost((displayHeight - 1).coerceAtLeast(0f)), left + displayWidth, top + displayHeight)
            val save = canvas.save()
            try {
                canvas.clipRect(clip)
                if (popupTarget == null && popupRect != null) canvas.clipOutRect(popupRect)
                // Placement y already includes the server's scrollback crop; never shift it twice.
                canvas.drawBitmap(bitmap, sourceRect, destination, paint)
            } finally { canvas.restoreToCount(save) }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        entries.values.forEach { if (it !== decoding) it.encoded = null }
        entries.clear()
        desired = emptySet()
        updateWarning()
        repaint = null
        // Do not recycle bitmaps still referenced by Android's recorded display lists.
    }

    private fun imagePixels(key: GraphicsAssetKey): Long? {
        if (key.imageWidth !in 1..8192 || key.imageHeight !in 1..8192) return null
        return (key.imageWidth * key.imageHeight).takeIf { it <= MAX_PIXELS }
    }

    private fun decode(key: GraphicsAssetKey, bytes: ByteArray): Bitmap? {
        val pixels = imagePixels(key) ?: return null
        return try {
            val width = key.imageWidth.toInt()
            val height = key.imageHeight.toInt()
            when (key.format) {
                GraphicsFormat.Png -> {
                    if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth != width || bounds.outHeight != height) return null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
                    })?.takeIf { it.width == width && it.height == height && it.allocationByteCount.toLong() <= pixels * 4 }
                }
                GraphicsFormat.Rgb, GraphicsFormat.Rgba -> {
                    val channels = if (key.format == GraphicsFormat.Rgb) 3 else 4
                    if (bytes.size.toLong() != pixels * channels) return null
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val row = IntArray(width)
                    var offset = 0
                    repeat(height) { y ->
                        repeat(width) { x ->
                            val red = bytes[offset++].toInt() and 255
                            val green = bytes[offset++].toInt() and 255
                            val blue = bytes[offset++].toInt() and 255
                            val alpha = if (channels == 4) bytes[offset++].toInt() and 255 else 255
                            row[x] = Color.argb(alpha, red, green, blue)
                        }
                        bitmap.setPixels(row, 0, width, 0, y, width, 1)
                    }
                    bitmap
                }
            }
        } catch (_: IllegalArgumentException) { null }
          catch (_: OutOfMemoryError) { null }
    }

    private fun crop(placement: GraphicsPlacement, width: Int, height: Int): Rect? {
        if (placement.sourceX !in 0 until width.toLong() || placement.sourceY !in 0 until height.toLong()) return null
        val sourceWidth = if (placement.sourceWidth == 0L) width.toLong() else placement.sourceWidth
        val sourceHeight = if (placement.sourceHeight == 0L) height.toLong() else placement.sourceHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        return Rect(placement.sourceX.toInt(), placement.sourceY.toInt(),
            (placement.sourceX + sourceWidth.coerceAtMost(width.toLong())).coerceAtMost(width.toLong()).toInt(),
            (placement.sourceY + sourceHeight.coerceAtMost(height.toLong())).coerceAtMost(height.toLong()).toInt())
    }

    private companion object {
        const val CACHE_BYTES = 64L * 1024 * 1024
        const val MAX_PIXELS = 16L * 1024 * 1024
        const val MAX_ASSETS = 1024
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
