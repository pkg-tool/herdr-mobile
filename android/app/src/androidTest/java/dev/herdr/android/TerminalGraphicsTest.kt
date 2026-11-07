package dev.herdr.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.endpoint.GraphicsAsset
import dev.herdr.android.endpoint.GraphicsAssetKey
import dev.herdr.android.endpoint.GraphicsFormat
import dev.herdr.android.endpoint.GraphicsPlacement
import dev.herdr.android.endpoint.GraphicsScene
import dev.herdr.android.endpoint.GraphicsSource
import dev.herdr.android.endpoint.GraphicsTarget
import dev.herdr.android.endpoint.SurfaceCell
import dev.herdr.android.endpoint.SurfaceFrame
import dev.herdr.android.endpoint.SurfaceGrid
import dev.herdr.android.endpoint.SurfacePane
import dev.herdr.android.endpoint.SurfaceRect
import dev.herdr.android.terminal.TerminalGraphics
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's actual BitmapFactory and Canvas, including asynchronous asset decoding. */
@RunWith(AndroidJUnit4::class)
class TerminalGraphicsTest {
    @Test fun rgbRgbaPngRemainDrawableWithoutRetransmissionAndNeverCrossServerBoots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pngBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().use { output ->
            assertTrue(pngBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        pngBitmap.recycle()
        val formats = listOf(GraphicsFormat.Rgb, GraphicsFormat.Rgba, GraphicsFormat.Png)
        val bytes = listOf(ByteArray(12) { if (it % 3 == 0) 255.toByte() else 0 },
            ByteArray(16) { when (it % 4) { 1 -> 255.toByte(); 3 -> 128.toByte(); else -> 0 } }, png)
        val assets = formats.mapIndexed { index, format ->
            GraphicsAsset(GraphicsAssetKey(GraphicsSource.Terminal(GraphicsTarget.Pane("w1:p1"), index.toLong()),
                imageWidth = 2, imageHeight = 2, format = format, dataLength = bytes[index].size.toLong(),
                fingerprint = index.toLong()), bytes[index])
        }
        val placements = assets.mapIndexed { index, asset ->
            GraphicsPlacement(asset.key, index.toLong(), x = index * 2, y = 0, columns = 2, rows = 2,
                sourceX = 0, sourceY = 0, sourceWidth = 0, sourceHeight = 0, xOffset = 0, yOffset = 0, z = 0, scrollbackOffset = 0)
        }
        val rect = SurfaceRect(0, 0, 8, 4)
        val pane = SurfacePane("w1:p1", 0, rect, rect, null, null, focused = true,
            mouseReporting = false, sgrPixelMouse = false, alternateScreenActive = false, pixelWidth = 80, pixelHeight = 40)
        val blank = SurfaceCell(" ", 0, 0, 0, skip = false, hyperlink = null)
        val initial = SurfaceFrame("boot-a", 1, 1, SurfaceGrid(List(32) { blank }, 8, 4, null, emptyList(), byteArrayOf()),
            listOf(pane), emptyList(), null, GraphicsScene(assets, placements, emptyList()))
        val decoded = CountDownLatch(3)
        lateinit var graphics: TerminalGraphics
        instrumentation.runOnMainSync { graphics = TerminalGraphics(); graphics.update(initial) { decoded.countDown() } }
        try {
            assertTrue("All three image formats must decode", decoded.await(10, TimeUnit.SECONDS))
            fun assertPixels(frame: SurfaceFrame, expected: List<Int>) {
                instrumentation.runOnMainSync {
                    val output = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
                    try {
                        graphics.draw(Canvas(output), frame, 10f, 10f, foreground = true)
                        expected.forEachIndexed { index, color -> assertEquals("Image $index", color, output.getPixel(index * 20 + 10, 10)) }
                    } finally { output.recycle() }
                }
            }
            val colors = listOf(Color.RED, Color.argb(128, 0, 255, 0), Color.BLUE)
            assertPixels(initial, colors)
            val retained = initial.copy(surfaceRevision = 2,
                graphics = GraphicsScene(emptyList(), emptyList(), assets.map { it.key }))
            instrumentation.runOnMainSync { graphics.update(retained) {} }
            assertPixels(retained, List(3) { Color.TRANSPARENT })
            val reused = initial.copy(surfaceRevision = 3, graphics = GraphicsScene(emptyList(), placements, emptyList()))
            instrumentation.runOnMainSync { graphics.update(reused) {} }
            assertPixels(reused, colors)
            val restarted = reused.copy(bootId = "boot-b", surfaceRevision = 1)
            instrumentation.runOnMainSync { graphics.update(restarted) {} }
            assertPixels(restarted, List(3) { Color.TRANSPARENT })
        } finally { instrumentation.runOnMainSync { graphics.close() } }
    }
}
