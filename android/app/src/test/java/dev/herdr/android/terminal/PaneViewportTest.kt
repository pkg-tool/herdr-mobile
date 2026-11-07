package dev.herdr.android.terminal

import dev.herdr.android.endpoint.*
import org.junit.Assert.*
import org.junit.Test

class PaneViewportTest {
    private fun frame(): SurfaceFrame {
        val cells = (0 until 80 * 24).map { SurfaceCell(it.toString(), 0, 0, 0, false, null) }
        fun pane(id: String, x: Int) = SurfacePane(id, 1, SurfaceRect(x, 0, 40, 24), SurfaceRect(x + 1, 1, 38, 22),
            null, SurfaceScroll(0, 100, 22), false, false, false, false, 380, 440)
        return SurfaceFrame("boot", 1, 1, SurfaceGrid(cells, 80, 24, SurfaceCursor(43, 2, true, 0), emptyList(), byteArrayOf()),
            listOf(pane("left", 0), pane("right", 40)), emptyList(), null, GraphicsScene(emptyList(), emptyList(), emptyList()))
    }

    @Test fun `right pane projects cells cursor and touch origin without changing shared layout`() {
        val source = frame()
        val projected = source.forPane("right")!!
        assertEquals(38, projected.grid.width)
        assertEquals(22, projected.grid.height)
        assertEquals("121", projected.grid.cells.first().symbol)
        assertEquals(SurfaceCursor(2, 1, true, 0), projected.grid.cursor)
        assertEquals(SurfaceRect(0, 0, 38, 22), projected.panes.single().innerRect)
        assertEquals(80, source.grid.width)
        assertNull(source.forPane("left")!!.grid.cursor)
        assertNull(source.forPane("closed"))
    }

    @Test fun `two panes receive full phone width and geometry stays bounded for tiny leaves`() {
        val source = frame()
        val size = paneSurfaceSize(source, "right", SurfaceSize(50, 40))
        assertEquals(SurfaceSize(104, 42), size)
        assertEquals(size, paneSurfaceSize(source, "left", SurfaceSize(50, 40)))
        val tiny = source.copy(panes = listOf(source.panes.first().copy(rect = SurfaceRect(0, 0, 1, 1), innerRect = SurfaceRect(0, 0, 1, 1))))
        val bounded = paneSurfaceSize(tiny, "left", SurfaceSize(4096, 4096))
        assertTrue(bounded.columns <= 4096 && bounded.rows <= 4096)
        assertTrue(bounded.columns.toLong() * bounded.rows <= 1_000_000)
    }

    @Test fun `popup uses physical phone geometry`() {
        val source = frame()
        val popup = SurfacePopup("popup", "Popup", null, null, source.grid, false, false, 800, 480)
        assertEquals(SurfaceSize(50, 40), paneSurfaceSize(source.copy(popup = popup), "right", SurfaceSize(50, 40)))
    }
}
