package dev.herdr.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.endpoint.*
import dev.herdr.android.terminal.TerminalView
import org.junit.Assert.*
import org.junit.Test

class TouchInteractionTest {
    @Test fun fingerSelectsScrollsAndResizesWithoutMouseOrKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext)
            var cw = 1; var ch = 1
            view.onResize = { _, _, width, height -> cw = width; ch = height }
            view.layout(0, 0, 1200, 1200)
            val first = SurfaceRect(0, 0, 10, 20)
            val second = SurfaceRect(10, 0, 10, 20)
            fun pane(id: String, rect: SurfaceRect, reporting: Boolean) = SurfacePane(id, 0, rect, rect, null,
                SurfaceScroll(0, 100, 20), false, reporting, false, false, 0, 0)
            val divider = SurfaceSplit(SplitDirection.Horizontal, 10, SurfaceRect(0, 0, 20, 20),
                SurfaceRect(9, 0, 2, 20), emptyList())
            val blank = SurfaceCell(" ", 0, 0, 0, false, null)
            val frame = SurfaceFrame("boot", 1, 1, SurfaceGrid(List(400) { blank }, 20, 20, null, emptyList(), byteArrayOf()),
                listOf(pane("first", first, false), pane("second", second, true)), listOf(divider), null,
                GraphicsScene(emptyList(), emptyList(), emptyList()))
            view.update(frame, "first")
            val inputs = mutableListOf<PaneInput>()
            var selected: String? = null
            var scroll = 0L
            var ratio: Float? = null
            view.onInput = { target, events, popup -> assertEquals("second", target); assertFalse(popup); inputs.addAll(events) }
            view.onSelect = { selected = it }
            view.onScroll = { target, lines -> assertEquals("first", target); scroll += lines }
            view.onSplit = { _, value -> ratio = value }
            var start = SystemClock.uptimeMillis()
            fun touch(action: Int, col: Float, row: Float, elapsed: Long) {
                val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val point = MotionEvent.PointerCoords().apply { x = col * cw; y = row * ch; pressure = 1f; size = 1f }
                val event = MotionEvent.obtain(start, start + elapsed, action, 1, arrayOf(property), arrayOf(point),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            fun swipe(col: Float) {
                start += 1000
                touch(MotionEvent.ACTION_DOWN, col, 2f, 0)
                touch(MotionEvent.ACTION_MOVE, col, 7f, 100)
                touch(MotionEvent.ACTION_UP, col, 7f, 150)
            }
            swipe(3f)
            assertEquals(5L, scroll)
            assertTrue(inputs.isEmpty())
            swipe(13f)
            assertEquals("second", selected)
            val wheelSemantic = inputs.single() as PaneInput.Mouse
            assertEquals(MouseKind.ScrollUp, wheelSemantic.kind)
            assertEquals(5, wheelSemantic.lines)
            inputs.clear()
            start += 1000
            touch(MotionEvent.ACTION_DOWN, 13f, 2f, 0)
            touch(MotionEvent.ACTION_UP, 13f, 2f, 100)
            assertTrue("A finger tap opens input without clicking the remote application", inputs.isEmpty())
            view.touchMouseMode = true
            start += 1000
            touch(MotionEvent.ACTION_DOWN, 13f, 2f, 0)
            touch(MotionEvent.ACTION_UP, 13f, 2f, 100)
            assertEquals(listOf(MouseKind.Down(MouseButton.Left), MouseKind.Up(MouseButton.Left)), inputs.map { (it as PaneInput.Mouse).kind })
            inputs.clear()
            start += 1000
            touch(MotionEvent.ACTION_DOWN, 10f, 2f, 0)
            touch(MotionEvent.ACTION_MOVE, 14f, 2f, 100)
            touch(MotionEvent.ACTION_UP, 14f, 2f, 150)
            assertEquals(0.7f, ratio!!, 0.01f)
            assertTrue(inputs.isEmpty())
            ratio = null
            start += 1000
            touch(MotionEvent.ACTION_DOWN, 10f, 2f, 0)
            touch(MotionEvent.ACTION_MOVE, 14f, 2f, 100)
            touch(MotionEvent.ACTION_CANCEL, 14f, 2f, 150)
            assertNull("A cancelled gesture must not resize a pane", ratio)
        }
    }
}
