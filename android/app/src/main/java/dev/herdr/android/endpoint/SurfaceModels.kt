package dev.herdr.android.endpoint

import java.io.IOException

data class SurfaceCell(
    val symbol: String,
    val foreground: Int,
    val background: Int,
    val modifiers: Int,
    val skip: Boolean,
    val hyperlink: Long?,
)

data class SurfaceCursor(val x: Int, val y: Int, val visible: Boolean, val shape: Int)
data class SurfaceRect(val x: Int, val y: Int, val width: Int, val height: Int)
data class SurfaceGrid(
    val cells: List<SurfaceCell>,
    val width: Int,
    val height: Int,
    val cursor: SurfaceCursor?,
    val hyperlinks: List<String>,
    val graphics: ByteArray,
)

data class SurfaceScroll(val offsetFromBottom: Long, val maxOffsetFromBottom: Long, val viewportRows: Long)
data class SurfacePane(
    val paneId: String,
    val contentRevision: Long,
    val rect: SurfaceRect,
    val innerRect: SurfaceRect,
    val scrollbarRect: SurfaceRect?,
    val scroll: SurfaceScroll?,
    val focused: Boolean,
    val mouseReporting: Boolean,
    val sgrPixelMouse: Boolean,
    val alternateScreenActive: Boolean,
    val pixelWidth: Long,
    val pixelHeight: Long,
)

enum class SplitDirection { Horizontal, Vertical }
data class SurfaceSplit(val direction: SplitDirection, val position: Int, val area: SurfaceRect, val hitRect: SurfaceRect, val path: List<Boolean>)
sealed interface PopupSize {
    data class Cells(val value: Int) : PopupSize
    data class Percent(val value: Int) : PopupSize
}
data class SurfacePopup(
    val terminalId: String,
    val title: String,
    val width: PopupSize?,
    val height: PopupSize?,
    val grid: SurfaceGrid,
    val mouseReporting: Boolean,
    val sgrPixelMouse: Boolean,
    val pixelWidth: Long,
    val pixelHeight: Long,
)

sealed interface GraphicsTarget {
    data class Pane(val id: String) : GraphicsTarget
    data class Popup(val id: String) : GraphicsTarget
}
sealed interface GraphicsSource {
    data class Terminal(val target: GraphicsTarget, val imageId: Long) : GraphicsSource
    data class PaneLayer(val paneId: String, val layerId: String) : GraphicsSource
}
enum class GraphicsFormat { Rgb, Rgba, Png }
data class GraphicsAssetKey(
    val source: GraphicsSource,
    val imageWidth: Long,
    val imageHeight: Long,
    val format: GraphicsFormat,
    val dataLength: Long,
    /** The opaque u64 fingerprint is retained as its exact signed Long bit pattern. */
    val fingerprint: Long,
)
data class GraphicsAsset(val key: GraphicsAssetKey, val bytes: ByteArray)
data class GraphicsPlacement(
    val asset: GraphicsAssetKey,
    val logicalPlacementId: Long,
    val x: Int,
    val y: Int,
    val columns: Long,
    val rows: Long,
    val sourceX: Long,
    val sourceY: Long,
    val sourceWidth: Long,
    val sourceHeight: Long,
    val xOffset: Long,
    val yOffset: Long,
    val z: Int,
    val scrollbackOffset: Long,
)
data class GraphicsScene(val assets: List<GraphicsAsset>, val placements: List<GraphicsPlacement>, val retainedAssets: List<GraphicsAssetKey>)

data class SurfaceFrame(
    val bootId: String,
    val projectionRevision: Long,
    val surfaceRevision: Long,
    val grid: SurfaceGrid,
    val panes: List<SurfacePane>,
    val splits: List<SurfaceSplit>,
    val popup: SurfacePopup?,
    val graphics: GraphicsScene,
) {
    /** Patches are meaningful only against the exact committed baseline on this connection. */
    fun applyPatch(patch: SurfacePatch): SurfaceFrame {
        if (patch.bootId != bootId || patch.projectionRevision != projectionRevision ||
            patch.baseSurfaceRevision != surfaceRevision || surfaceRevision == Long.MAX_VALUE || patch.surfaceRevision != surfaceRevision + 1) {
            throw IOException("Surface patch does not match its baseline")
        }
        val cells = grid.cells.toMutableList()
        if (patch.cursor != null && (patch.cursor.x !in 0 until grid.width || patch.cursor.y !in 0 until grid.height)) {
            throw IOException("Patched cursor is outside the grid")
        }
        patch.rows.forEach { row ->
            if (row.x !in 0..grid.width || row.y !in 0 until grid.height || row.cells.size > grid.width - row.x) {
                throw IOException("Surface patch is outside the grid")
            }
            if (row.cells.any { it.hyperlink != null && it.hyperlink >= grid.hyperlinks.size }) {
                throw IOException("Patched cell has an invalid hyperlink index")
            }
            row.cells.forEachIndexed { offset, cell -> cells[row.y * grid.width + row.x + offset] = cell }
        }
        val knownIds = panes.map { it.paneId }.toSet()
        if (patch.panes.any { it.paneId !in knownIds }) throw IOException("Patch references an unknown pane")
        val updated = patch.panes.associateBy { it.paneId }
        return copy(surfaceRevision = patch.surfaceRevision,
            grid = grid.copy(cells = cells, cursor = patch.cursor),
            panes = panes.map { updated[it.paneId] ?: it })
    }
}

data class PatchRow(val x: Int, val y: Int, val cells: List<SurfaceCell>)
data class SurfacePatch(
    val bootId: String,
    val projectionRevision: Long,
    val baseSurfaceRevision: Long,
    val surfaceRevision: Long,
    val rows: List<PatchRow>,
    val panes: List<SurfacePane>,
    val cursor: SurfaceCursor?,
)

sealed interface EndpointMessage {
    data class Control(val kind: String, val data: String) : EndpointMessage
    data class Surface(val value: SurfaceFrame) : EndpointMessage
    data class Patch(val value: SurfacePatch) : EndpointMessage
    data class ResponseChunk(val bootId: String, val requestId: String, val finalChunk: Boolean, val data: ByteArray) : EndpointMessage
    data class Shutdown(val reason: String?) : EndpointMessage
    data class Error(val message: String) : EndpointMessage
    data class Clipboard(val base64: String) : EndpointMessage
    data class WindowTitle(val title: String?) : EndpointMessage
    data class MouseCapture(val enabled: Boolean, val sgrPixels: Boolean) : EndpointMessage
    data class KeyboardReportAll(val enabled: Boolean) : EndpointMessage
    data class TerminalBell(val count: Int) : EndpointMessage
    data class Notification(val kind: Int, val title: String, val body: String?, val paneId: String?, val workspaceId: String?, val tabId: String?) : EndpointMessage
    data class GraphicsFile(val path: String, val expectedLength: Long, val imageId: Long, val transferId: Long, val leading: ByteArray, val control: String, val asset: GraphicsAssetKey?) : EndpointMessage
    data class GraphicsRetired(val transferId: Long, val imageId: Long) : EndpointMessage
    data object ReloadSoundConfig : EndpointMessage
    /** Direct-terminal variants are decoded but must not be interpreted as a client-shell grid. */
    data class LegacyWelcome(val version: Long, val encoding: Int, val error: String?) : EndpointMessage
    data class TerminalBytes(val sequence: Long, val width: Int, val height: Int, val full: Boolean, val bytes: ByteArray) : EndpointMessage
    data class GraphicsBytes(val bytes: ByteArray) : EndpointMessage
    data class DirectKeyboardProtocol(val flags: Int, val modifyOtherKeys: Int) : EndpointMessage
}

sealed interface PaneKey {
    enum class Special : PaneKey { Backspace, Enter, Left, Right, Up, Down, Home, End, PageUp, PageDown, Tab, BackTab, Delete, Insert, Esc }
    data class Character(val codePoint: Int) : PaneKey
    data class Function(val number: Int) : PaneKey
    data object Null : PaneKey
}
enum class KeyKind { Press, Repeat, Release }
enum class MouseButton { Left, Right, Middle }
sealed interface MouseKind {
    data class Down(val button: MouseButton) : MouseKind
    data class Up(val button: MouseButton) : MouseKind
    data class Drag(val button: MouseButton) : MouseKind
    data object Moved : MouseKind
    data object ScrollUp : MouseKind
    data object ScrollDown : MouseKind
    data object ScrollLeft : MouseKind
    data object ScrollRight : MouseKind
}
sealed interface MousePosition {
    data class Cell(val column: Int, val row: Int) : MousePosition
    data class Pixels(val x: Long, val y: Long, val column: Int, val row: Int) : MousePosition
}
data class MouseGeometry(val columns: Int, val rows: Int, val widthPixels: Long, val heightPixels: Long)
sealed interface PaneInput {
    data class Key(val code: PaneKey, val modifiers: Int = 0, val kind: KeyKind = KeyKind.Press,
        val repeatCount: Int = 1, val shiftedCodePoint: Long? = null, val generatedText: String? = null,
        val tracksRelease: Boolean = false, val physicalKeyId: Long? = null) : PaneInput
    data class TextCommit(val text: String) : PaneInput
    data class Paste(val text: String) : PaneInput
    data class Mouse(val kind: MouseKind, val position: MousePosition,
        val geometry: MouseGeometry? = null, val modifiers: Int = 0, val lines: Int = 1) : PaneInput
}
