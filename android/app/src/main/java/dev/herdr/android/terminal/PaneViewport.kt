package dev.herdr.android.terminal

import dev.herdr.android.endpoint.GraphicsSource
import dev.herdr.android.endpoint.GraphicsTarget
import dev.herdr.android.endpoint.SurfaceFrame
import dev.herdr.android.endpoint.SurfaceRect
import kotlin.math.ceil
import kotlin.math.sqrt

/** A phone viewport is a projection of server cells, never a global pane.zoom or text reflow. */
internal fun SurfaceFrame.forPane(paneId: String): SurfaceFrame? {
    val pane = panes.firstOrNull { it.paneId == paneId } ?: return null
    val area = pane.innerRect
    if (area.width <= 0 || area.height <= 0) return null
    val origin = SurfaceRect(0, 0, area.width, area.height)
    val cells = (area.y until area.y + area.height).flatMap { row ->
        grid.cells.subList(row * grid.width + area.x, row * grid.width + area.x + area.width)
    }
    val cursor = grid.cursor?.takeIf { it.x in area.x until area.x + area.width && it.y in area.y until area.y + area.height }
        ?.let { it.copy(x = it.x - area.x, y = it.y - area.y) }
    val placements = graphics.placements.mapNotNull { placement ->
        val source = placement.asset.source
        when {
            source is GraphicsSource.Terminal && source.target is GraphicsTarget.Popup -> placement
            source is GraphicsSource.Terminal && (source.target as? GraphicsTarget.Pane)?.id == paneId ||
                source is GraphicsSource.PaneLayer && source.paneId == paneId -> placement.copy(x = placement.x - area.x, y = placement.y - area.y)
            else -> null
        }
    }
    return copy(grid = grid.copy(cells = cells, width = area.width, height = area.height, cursor = cursor),
        panes = listOf(pane.copy(rect = origin, innerRect = origin, scrollbarRect = null)), splits = emptyList(),
        graphics = graphics.copy(placements = placements))
}

internal data class SurfaceSize(val columns: Int, val rows: Int)

/** Enlarges the whole server surface so the selected leaf receives actual phone-sized PTY cells. */
internal fun paneSurfaceSize(frame: SurfaceFrame?, paneId: String?, viewport: SurfaceSize): SurfaceSize {
    val pane = frame?.panes?.firstOrNull { it.paneId == paneId } ?: return viewport
    if (frame.popup != null || pane.rect.width <= 0 || pane.rect.height <= 0) return viewport
    val fractionX = pane.rect.width.toDouble() / frame.grid.width
    val fractionY = pane.rect.height.toDouble() / frame.grid.height
    val borderX = pane.rect.width - pane.innerRect.width
    val borderY = pane.rect.height - pane.innerRect.height
    var columns = ceil((viewport.columns + borderX) / fractionX).toInt().coerceIn(2, 4096)
    var rows = ceil((viewport.rows + borderY) / fractionY).toInt().coerceIn(2, 4096)
    if (columns.toLong() * rows > 1_000_000) {
        val scale = sqrt(1_000_000.0 / (columns.toLong() * rows))
        columns = (columns * scale).toInt().coerceAtLeast(2)
        rows = (rows * scale).toInt().coerceAtLeast(2)
    }
    return SurfaceSize(columns, rows)
}
