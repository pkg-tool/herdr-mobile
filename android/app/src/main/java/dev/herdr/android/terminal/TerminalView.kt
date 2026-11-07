package dev.herdr.android.terminal
import dev.herdr.android.terminalCommit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import dev.herdr.android.endpoint.KeyKind
import dev.herdr.android.endpoint.MouseButton
import dev.herdr.android.endpoint.MouseGeometry
import dev.herdr.android.endpoint.MouseKind
import dev.herdr.android.endpoint.MousePosition
import dev.herdr.android.endpoint.PaneInput
import dev.herdr.android.endpoint.PaneKey
import dev.herdr.android.endpoint.SplitDirection
import dev.herdr.android.endpoint.SurfaceFrame
import dev.herdr.android.endpoint.SurfaceGrid
import dev.herdr.android.endpoint.SurfacePane
import dev.herdr.android.endpoint.SurfaceRect
import dev.herdr.android.endpoint.SurfaceSplit
import kotlin.math.ceil
import kotlin.math.max

/** Renders semantic terminal cells only; all application navigation lives in Compose. */
class TerminalView(context: Context) : View(context) {
    var onViewportReady: () -> Unit = {}
    var onKeyboardRequested: () -> Unit = {}
    var touchMouseMode: Boolean = false
    var onFontScale: (Float) -> Unit = {}
    private var pinchScale = 1f
    private val pinch = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean { pinchScale = 1f; return true }
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean { pinchScale *= detector.scaleFactor; return true }
            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) { onFontScale(pinchScale.coerceIn(0.5f, 2f)) }
        })
    var onInput: (String, List<PaneInput>, Boolean) -> Unit = { _, _, _ -> }
    var onResize: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    var onGestureStart: (String) -> Unit = {}
    var onSelect: (String) -> Unit = {}
    var onScroll: (String, Long) -> Unit = { _, _ -> }
    var onSplit: (SurfaceSplit, Float) -> Unit = { _, _ -> }
    var onLink: (String) -> Unit = {}
    var onGraphicsWarning: (String?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val metrics = Paint.FontMetrics()
    private val rect = RectF()
    private var cellWidth = 10f
    private var cellHeight = 20f
    private var baseline = 16f
    private var requestedColumns = 0
    private var requestedRows = 0
    private var requestedCellWidth = 0
    private var requestedCellHeight = 0
    private var graphics = TerminalGraphics { onGraphicsWarning(it) }
    private var frame: SurfaceFrame? = null
    private var fullFrame: SurfaceFrame? = null
    private var focusedViewport = false
    private var geometryIntent: String? = null
    private var geometryAdjustments = 0
    private var selectedPane: String? = null
    private var touchPane: SurfacePane? = null
    private var split: SurfaceSplit? = null
    private var moved = false
    private var pointerDown = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var scrollRemainder = 0f
    private val defaultBackground = Color.rgb(19, 23, 30)
    private val defaultForeground = Color.rgb(224, 229, 237)
    private val normal = Typeface.MONOSPACE
    private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val italic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onDoubleTap(event: MotionEvent): Boolean { showKeyboard(); return true }
        override fun onLongPress(event: MotionEvent) { moved = true; showSelectionMenu(event.x, event.y) }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setFontSize(13f)
    }

    fun setFontSize(sp: Float) {
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp.coerceIn(8f, 28f), resources.displayMetrics)
        paint.typeface = normal
        cellWidth = ceil(paint.measureText("M"))
        paint.getFontMetrics(metrics)
        cellHeight = ceil(metrics.descent - metrics.ascent + 2 * resources.displayMetrics.density)
        baseline = -metrics.ascent
        updateGeometry()
        invalidate()
    }

    fun update(surface: SurfaceFrame?, paneId: String?, singlePane: Boolean = false) {
        fullFrame = surface
        focusedViewport = singlePane
        selectedPane = paneId
        frame = if (singlePane && paneId != null) surface?.forPane(paneId) else surface
        surface?.let { graphics.update(it) { invalidate() } }
        updateGeometry()
        invalidate()
    }

    fun showKeyboard() {
        onKeyboardRequested()
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGeometry()
    private fun updateGeometry() {
        if (width <= 0 || height <= 0) return
        val viewport = SurfaceSize(max(2, (width / cellWidth).toInt()), max(2, (height / cellHeight).toInt()))
        val source = fullFrame
        val intent = "$selectedPane:$focusedViewport:$viewport:${source?.panes?.map { it.paneId }}:${source?.popup?.terminalId}"
        val changed = geometryIntent != intent
        if (changed) { geometryIntent = intent; geometryAdjustments = 0 }
        if (!changed && source != null && (source.grid.width != requestedColumns || source.grid.height != requestedRows)) return
        var size = if (focusedViewport) paneSurfaceSize(source, selectedPane, viewport) else viewport
        val pane = source?.panes?.firstOrNull { it.paneId == selectedPane }
        if (focusedViewport && source != null && source.popup == null && pane != null &&
            kotlin.math.abs(pane.innerRect.width - viewport.columns) <= 1 && kotlin.math.abs(pane.innerRect.height - viewport.rows) <= 1) {
            size = SurfaceSize(source.grid.width, source.grid.height)
        }
        if (geometryAdjustments >= 3) return
        if (size.columns != requestedColumns || size.rows != requestedRows || cellWidth.toInt() != requestedCellWidth || cellHeight.toInt() != requestedCellHeight) {
            requestedColumns = size.columns
            requestedRows = size.rows
            requestedCellWidth = cellWidth.toInt()
            requestedCellHeight = cellHeight.toInt()
            geometryAdjustments++
            onResize(size.columns, size.rows, cellWidth.toInt(), cellHeight.toInt())
        } else onViewportReady()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.clipRect(0, 0, width, height)
        paint.color = defaultBackground
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val value = frame ?: return
        fun mainPass(backgrounds: Boolean) {
            for (pane in value.panes) {
                canvas.save()
                val area = pane.innerRect
                canvas.clipRect(area.x * cellWidth, area.y * cellHeight,
                    (area.x + area.width) * cellWidth, (area.y + area.height) * cellHeight)
                drawGrid(canvas, value.grid, backgrounds)
                canvas.restore()
            }
        }
        mainPass(backgrounds = true)
        fun popupPass(backgrounds: Boolean) {
            value.popup?.let { popup ->
                val x = ((width - popup.grid.width * cellWidth) / 2).coerceAtLeast(0f)
                val y = ((height - popup.grid.height * cellHeight) / 2).coerceAtLeast(0f)
                canvas.save()
                canvas.translate(x, y)
                drawGrid(canvas, popup.grid, backgrounds)
                canvas.restore()
            }
        }
        popupPass(true)
        graphics.draw(canvas, value, cellWidth, cellHeight, foreground = false)
        canvas.save()
        value.popup?.let { popup ->
            val left = ((width - popup.grid.width * cellWidth) / 2).coerceAtLeast(0f)
            val top = ((height - popup.grid.height * cellHeight) / 2).coerceAtLeast(0f)
            canvas.clipOutRect(left, top, left + popup.grid.width * cellWidth, top + popup.grid.height * cellHeight)
        }
        mainPass(backgrounds = false)
        canvas.restore()
        popupPass(false)
        graphics.draw(canvas, value, cellWidth, cellHeight, foreground = true)
        if (!focusedViewport) value.panes.forEach { pane ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * if (pane.paneId == selectedPane) 2f else 1f
            paint.color = if (pane.paneId == selectedPane) Color.rgb(113, 186, 247) else Color.rgb(59, 67, 80)
            canvas.drawRect(pane.rect.x * cellWidth, pane.rect.y * cellHeight,
                (pane.rect.x + pane.rect.width) * cellWidth, (pane.rect.y + pane.rect.height) * cellHeight, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onDetachedFromWindow() {
        graphics.close()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        graphics.close()
        graphics = TerminalGraphics { onGraphicsWarning(it) }
        fullFrame?.let { graphics.update(it) { invalidate() } }
    }

    private fun drawGrid(canvas: Canvas, grid: SurfaceGrid, backgrounds: Boolean) {
        val visibleRows = minOf(grid.height, (height / cellHeight).toInt() + 1)
        val visibleColumns = minOf(grid.width, (width / cellWidth).toInt() + 1)
        val clip = canvas.clipBounds
        val firstRow = (clip.top / cellHeight).toInt().coerceIn(0, visibleRows)
        val lastRow = ceil(clip.bottom / cellHeight).toInt().coerceIn(firstRow, visibleRows)
        val firstColumn = (clip.left / cellWidth).toInt().coerceIn(0, visibleColumns)
        val lastColumn = ceil(clip.right / cellWidth).toInt().coerceIn(firstColumn, visibleColumns)
        if (backgrounds) for (row in firstRow until lastRow) for (column in firstColumn until lastColumn) {
            val cell = grid.cells[row * grid.width + column]
            paint.color = terminalColor(if (cell.modifiers and 64 != 0) cell.foreground else cell.background,
                if (cell.modifiers and 64 != 0) defaultForeground else defaultBackground)
            canvas.drawRect(column * cellWidth, row * cellHeight, (column + 1) * cellWidth, (row + 1) * cellHeight, paint)
        }
        if (backgrounds) return
        for (row in firstRow until lastRow) for (column in firstColumn until lastColumn) {
            val cell = grid.cells[row * grid.width + column]
            var foreground = terminalColor(cell.foreground, defaultForeground)
            var background = terminalColor(cell.background, defaultBackground)
            if (cell.modifiers and 64 != 0) { val old = foreground; foreground = background; background = old }
            val x = column * cellWidth
            val y = row * cellHeight
            if (cell.skip || cell.modifiers and 128 != 0) continue
            paint.color = foreground
            paint.alpha = if (cell.modifiers and 2 != 0) 150 else 255
            paint.typeface = when (cell.modifiers and 5) { 1 -> bold; 4 -> italic; 5 -> boldItalic; else -> normal }
            paint.isUnderlineText = cell.modifiers and 8 != 0
            paint.isStrikeThruText = cell.modifiers and 256 != 0
            canvas.drawText(cell.symbol, x, y + baseline, paint)
            paint.alpha = 255
            paint.isUnderlineText = false
            paint.isStrikeThruText = false
        }
        grid.cursor?.takeIf { it.visible }?.let { cursor ->
            paint.color = Color.argb(155, 202, 222, 250)
            val x = cursor.x * cellWidth
            val y = cursor.y * cellHeight
            when (cursor.shape) {
                3, 4 -> canvas.drawRect(x, y + cellHeight - 2, x + cellWidth, y + cellHeight, paint)
                5, 6 -> canvas.drawRect(x, y, x + 2, y + cellHeight, paint)
                else -> canvas.drawRect(x, y, x + cellWidth, y + cellHeight, paint)
            }
        }
    }

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_GO
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = ""
                text?.toString()?.takeIf { it.isNotEmpty() }?.let { commitImeText(it) }
                return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString().orEmpty(); return true }
            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) commitImeText(composing)
                composing = ""
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) { composing = composing.dropLast(beforeLength.coerceIn(0, composing.length)); return true }
                val events = List(beforeLength.coerceIn(0, 1024)) { PaneInput.Key(PaneKey.Special.Backspace) } +
                    List(afterLength.coerceIn(0, 1024)) { PaneInput.Key(PaneKey.Special.Delete) }
                if (events.isNotEmpty()) submit(events)
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = handleKey(event)
            override fun performEditorAction(actionCode: Int): Boolean { submit(listOf(PaneInput.Key(PaneKey.Special.Enter))); return true }
        }
    }

    private fun commitImeText(text: String) {
        val events = terminalCommit(text)
        if (events.isNotEmpty()) submit(events)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyUp(keyCode, event)
    private fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(event.keyCode)) return false
        val special = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> PaneKey.Special.Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> PaneKey.Special.Delete
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> PaneKey.Special.Enter
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) PaneKey.Special.BackTab else PaneKey.Special.Tab
            KeyEvent.KEYCODE_ESCAPE -> PaneKey.Special.Esc
            KeyEvent.KEYCODE_DPAD_LEFT -> PaneKey.Special.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> PaneKey.Special.Right
            KeyEvent.KEYCODE_DPAD_UP -> PaneKey.Special.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> PaneKey.Special.Down
            KeyEvent.KEYCODE_MOVE_HOME -> PaneKey.Special.Home
            KeyEvent.KEYCODE_MOVE_END -> PaneKey.Special.End
            KeyEvent.KEYCODE_PAGE_UP -> PaneKey.Special.PageUp
            KeyEvent.KEYCODE_PAGE_DOWN -> PaneKey.Special.PageDown
            KeyEvent.KEYCODE_INSERT -> PaneKey.Special.Insert
            else -> null
        }
        val key = special ?: if (event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            PaneKey.Function(event.keyCode - KeyEvent.KEYCODE_F1 + 1)
        } else {
            val unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv())
            if (unicode == 0) return false
            PaneKey.Character(unicode and KeyCharacterMap.COMBINING_ACCENT_MASK)
        }
        val modifiers = (if (event.isShiftPressed) 1 else 0) or (if (event.isCtrlPressed) 2 else 0) or
            (if (event.isAltPressed) 4 else 0) or (if (event.isMetaPressed) 8 else 0)
        submit(listOf(PaneInput.Key(key, modifiers, if (event.action == KeyEvent.ACTION_UP) KeyKind.Release else if (event.repeatCount > 0) KeyKind.Repeat else KeyKind.Press,
            repeatCount = 1, physicalKeyId = event.scanCode.toLong().takeIf { it > 0 }, tracksRelease = true)))
        return true
    }

    private fun submit(events: List<PaneInput>) {
        val popup = frame?.popup
        val target = popup?.terminalId ?: selectedPane ?: return
        onInput(target, events, popup != null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (event.pointerCount > 1 || pinch.isInProgress) {
            pointerDown = false; split = null; moved = true
            val remaining = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0 && event.pointerCount > 1) 1 else 0
            pointerX = event.getX(remaining); pointerY = event.getY(remaining); scrollRemainder = 0f
            return true
        }
        gestures.onTouchEvent(event)
        val current = frame ?: return true
        val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        val column = (event.x / cellWidth).toInt()
        val row = (event.y / cellHeight).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                moved = false
                pointerDown = true
                pointerX = event.x
                pointerY = event.y
                touchStartX = event.x
                touchStartY = event.y
                scrollRemainder = 0f
                split = if (current.popup == null) current.splits.firstOrNull { it.hitRect.contains(column, row) } else null
                touchPane = if (current.popup == null) current.panes.firstOrNull { it.rect.contains(column, row) } else null
                touchPane?.let { onGestureStart(it.paneId) }
                touchPane?.let { if (it.paneId != selectedPane) onSelect(it.paneId) }
                if (!finger) mouse(event, MouseKind.Down(MouseButton.Left))
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - touchStartX) + kotlin.math.abs(event.y - touchStartY) > 8) moved = true
                if (split != null) { invalidate(); return true }
                val pane = touchPane
                val reporting = current.popup?.mouseReporting ?: (pane?.mouseReporting == true)
                if (!finger && reporting) mouse(event, MouseKind.Drag(MouseButton.Left))
                else if (moved && (pane != null || current.popup != null)) {
                    scrollRemainder += event.y - pointerY
                    val lines = (scrollRemainder / cellHeight).toInt().coerceIn(-4096, 4096)
                    if (lines != 0) {
                        if (reporting) mouse(event, if (lines > 0) MouseKind.ScrollUp else MouseKind.ScrollDown, kotlin.math.abs(lines))
                        else if (pane != null) onScroll(pane.paneId, lines.toLong())
                        scrollRemainder -= lines * cellHeight
                    }
                }
                pointerX = event.x
                pointerY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                split?.let {
                    if (moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        val ratio = if (it.direction == SplitDirection.Horizontal) (column - it.area.x).toFloat() / it.area.width
                            else (row - it.area.y).toFloat() / it.area.height
                        onSplit(it, ratio.coerceIn(0.05f, 0.95f))
                    }
                }
                if (pointerDown) {
                    if (!finger) mouse(event, MouseKind.Up(MouseButton.Left))
                    else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (touchMouseMode) {
                            mouse(event, MouseKind.Down(MouseButton.Left))
                            mouse(event, MouseKind.Up(MouseButton.Left))
                        } else showKeyboard()
                    }
                }
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                pointerDown = false
                split = null
                touchPane = null
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val current = frame ?: return false
            val pane = current.panes.firstOrNull { it.rect.contains((event.x / cellWidth).toInt(), (event.y / cellHeight).toInt()) }
            if (pane == null && current.popup == null) return false
            pane?.let { onGestureStart(it.paneId) }
            val lines = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3).toInt().coerceIn(-4096, 4096)
            if (lines == 0) return true
            if (current.popup?.mouseReporting ?: (pane?.mouseReporting == true)) {
                touchPane = pane
                mouse(event, if (lines > 0) MouseKind.ScrollUp else MouseKind.ScrollDown, kotlin.math.abs(lines))
                touchPane = null
            } else if (pane != null && current.popup == null) onScroll(pane.paneId, lines.toLong())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun mouse(event: MotionEvent, kind: MouseKind, lines: Int = 1) {
        val popup = frame?.popup
        if (popup != null) {
            if (!popup.mouseReporting) return
            val x = ((width - popup.grid.width * cellWidth) / 2).coerceAtLeast(0f)
            val y = ((height - popup.grid.height * cellHeight) / 2).coerceAtLeast(0f)
            val col = ((event.x - x) / cellWidth).toInt().coerceIn(0, popup.grid.width - 1)
            val row = ((event.y - y) / cellHeight).toInt().coerceIn(0, popup.grid.height - 1)
            onInput(popup.terminalId, listOf(PaneInput.Mouse(kind, MousePosition.Cell(col, row), lines = lines)), true)
            return
        }
        val pane = touchPane ?: return
        if (!pane.mouseReporting || split != null) return
        val col = ((event.x / cellWidth).toInt() - pane.innerRect.x).coerceIn(0, (pane.innerRect.width - 1).coerceAtLeast(0))
        val row = ((event.y / cellHeight).toInt() - pane.innerRect.y).coerceIn(0, (pane.innerRect.height - 1).coerceAtLeast(0))
        onInput(pane.paneId, listOf(PaneInput.Mouse(kind, MousePosition.Cell(col, row),
            MouseGeometry(pane.innerRect.width, pane.innerRect.height, (pane.innerRect.width * cellWidth).toLong(), (pane.innerRect.height * cellHeight).toLong()), lines = lines)), false)
    }

    private fun showSelectionMenu(x: Float, y: Float) {
        val current = frame ?: return
        val pane = current.panes.firstOrNull { it.rect.contains((x / cellWidth).toInt(), (y / cellHeight).toInt()) } ?: return
        val text = buildString {
            for (row in pane.innerRect.y until pane.innerRect.y + pane.innerRect.height) {
                for (col in pane.innerRect.x until pane.innerRect.x + pane.innerRect.width) append(current.grid.cells[row * current.grid.width + col].symbol)
                append('\n')
            }
        }
        val index = (y / cellHeight).toInt() * current.grid.width + (x / cellWidth).toInt()
        val link = current.grid.cells.getOrNull(index)?.hyperlink?.toInt()?.let { current.grid.hyperlinks.getOrNull(it) }
        PopupMenu(context, this).apply {
            menu.add("Copy visible text").setOnMenuItemClickListener {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Herdr", text)); true
            }
            menu.add("Paste").setOnMenuItemClickListener {
                context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.let {
                    onInput(pane.paneId, listOf(PaneInput.Paste(it.toString().take(262_144))), false)
                }; true
            }
            if (link != null) menu.add("Open link").setOnMenuItemClickListener { onLink(link); true }
            show()
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true
        info.text = frame?.grid?.let { grid -> grid.cells.chunked(grid.width).joinToString("\n") { row -> row.joinToString("") { it.symbol }.trimEnd() } }
        info.contentDescription = "Interactive Herdr pane"
    }
}

private fun SurfaceRect.contains(column: Int, row: Int) = column >= x && column < x + width && row >= y && row < y + height

internal fun terminalColor(value: Int, default: Int): Int = when (value ushr 24) {
    2 -> 0xff000000.toInt() or (value and 0xffffff)
    1 -> palette(value and 255)
    else -> if (value and 255 == 0) default else palette((value and 255) - 1)
}

private val basePalette = intArrayOf(0x151922, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xabb2bf,
    0x5c6370, 0xff7a85, 0xb3e39b, 0xffd68a, 0x83c3ff, 0xe09bff, 0x82d8e3, 0xffffff)
private fun palette(index: Int): Int {
    val rgb = when {
        index < 16 -> basePalette[index.coerceAtLeast(0)]
        index >= 232 -> { val gray = 8 + (index - 232) * 10; (gray shl 16) or (gray shl 8) or gray }
        else -> { val n = index - 16; fun channel(c: Int) = if (c == 0) 0 else 55 + c * 40
            (channel(n / 36) shl 16) or (channel(n / 6 % 6) shl 8) or channel(n % 6) }
    }
    return 0xff000000.toInt() or rgb
}
