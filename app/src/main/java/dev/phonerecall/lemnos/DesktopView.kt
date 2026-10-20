package dev.phonerecall.lemnos

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One viewing of the Lemnos desktop: the mTLS WebSocket (a one-time ticket, like the terminal), an [RfbClient] on its
 * own thread, and the picture as a Bitmap that outlives the view drawing it (inline and full screen share it).
 */
class DesktopLink(private val onStatus: (String) -> Unit, private val onClipboard: (String) -> Unit, private val onSize: (Int, Int) -> Unit = { _, _ -> }) : Framebuffer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var queue: ByteQueue? = null
    @Volatile var rfb: RfbClient? = null; private set
    @Volatile var bitmap: Bitmap? = null; private set
    @Volatile var view: DesktopView? = null
    @Volatile var live = false; private set
    /** Sticky modifiers from the keys row: held for the next key, then let go. */
    val sticky = linkedSetOf<Int>()

    /** Each open() is one attempt; callbacks from an older attempt (closed, or superseded) are ignored. */
    @Volatile private var attempt = 0
    @Volatile private var busy = false

    fun open() {
        if (busy) return
        busy = true
        val n = ++attempt
        onStatus("Connecting")
        scope.launch {
            try {
                val ticket = Gateway.desktopTicket()
                val q = ByteQueue().also { queue = it }
                val sock = Gateway.openDesktop(ticket, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Thread({ protocol(n, q, webSocket) }, "lemnos-rfb").start()
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = q.offer(bytes.toByteArray())
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { q.close(); ended(n, "Closed") }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { q.close(); ended(n, "Dropped: ${t.message ?: t.javaClass.simpleName}") }
                })
                if (n == attempt) ws = sock else sock.cancel()
            } catch (e: Exception) { ended(n, "Couldn't connect: ${e.message}") }
        }
    }

    private fun protocol(n: Int, q: ByteQueue, socket: WebSocket) {
        val c = RfbClient(q, { socket.send(it.toByteString()) }, this, ::decodeJpeg, onClipboard) { _, _, _ -> if (n == attempt) { live = true; onStatus("Live") } }
        if (n == attempt) rfb = c
        try { c.run() } catch (e: Exception) { ended(n, if (live) "Ended: ${e.message ?: "the desktop closed"}" else e.message ?: "Couldn't start the session") }
        socket.close(1000, null)
    }

    private fun ended(n: Int, why: String) {
        if (n != attempt || !busy) return
        busy = false; ws = null; rfb = null; live = false
        onStatus(why)
    }

    fun close() {
        attempt++
        busy = false
        ws?.close(1000, null); queue?.close()
        ws = null; rfb = null; queue = null; live = false
    }

    // ---- sending ----

    fun key(keysym: Int, down: Boolean) { rfb?.key(keysym, down) }

    /** A full press of [keysym], wrapped in the sticky modifiers (which then let go). */
    fun tap(keysym: Int) {
        val r = rfb ?: return
        val mods = sticky.toList()
        mods.forEach { r.key(it, true) }
        r.key(keysym, true); r.key(keysym, false)
        mods.asReversed().forEach { r.key(it, false) }
        if (mods.isNotEmpty()) { sticky.clear(); view?.post { view?.onStickyCleared?.invoke() } }
    }

    fun type(text: String) { text.codePoints().forEach { tap(Keysym.ofChar(it)) } }

    fun paste(text: String) { rfb?.clipboard(text) }

    // ---- Framebuffer: called on the protocol thread ----

    override fun resize(w: Int, h: Int) {
        bitmap = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1C2228.toInt()) }
        onSize(w, h)
        view?.post { view?.fit(true) }
    }

    override fun put(x: Int, y: Int, w: Int, h: Int, px: IntArray) {
        val b = bitmap ?: return
        if (w <= 0 || h <= 0 || x + w > b.width || y + h > b.height) return
        b.setPixels(px, 0, w, x, y, w, h)
    }

    override fun fill(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        if (w <= 0 || h <= 0) return
        put(x, y, w, h, IntArray(w * h) { argb })
    }

    override fun copy(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        val b = bitmap ?: return
        if (w <= 0 || h <= 0 || sx + w > b.width || sy + h > b.height) return
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, sx, sy, w, h)
        put(dx, dy, w, h, px)
    }

    override fun frame() { view?.postInvalidateOnAnimation() }

    private fun decodeJpeg(data: ByteArray): IntArray? {
        val bm = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        return IntArray(bm.width * bm.height).also { bm.getPixels(it, 0, bm.width, 0, 0, bm.width, bm.height); bm.recycle() }
    }
}

/**
 * The desktop on screen, drawn natively. Touch is a mouse where the finger is: tap = click, hold = right click,
 * hold then move = drag with the left button, two fingers = scroll (moving together) or zoom (spreading), one
 * finger on a zoomed-in view = pan. A Bluetooth/USB mouse and keyboard pass straight through.
 * [interactive] false (the cover screen): pan and zoom only, nothing reaches the desktop.
 */
@SuppressLint("ViewConstructor")
class DesktopView(context: Context, var link: DesktopLink?) : View(context) {
    var interactive = true
    var onStickyCleared: (() -> Unit)? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f; private var tx = 0f; private var ty = 0f
    private var fitted = true
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // the remote pointer, in desktop pixels
    private var px = 0; private var py = 0; private var buttons = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xFF15191D.toInt())
    }

    private fun fbW() = link?.bitmap?.width ?: 0
    private fun fbH() = link?.bitmap?.height ?: 0
    private fun fitScale() = if (fbW() == 0 || width == 0) 1f else min(width.toFloat() / fbW(), height.toFloat() / fbH())

    /** Back to "whole desktop visible" (or keep the zoom if [onlyIfFitted] and the user zoomed). */
    fun fit(onlyIfFitted: Boolean = false) {
        if (onlyIfFitted && !fitted) { clamp(); invalidate(); return }
        scale = fitScale(); fitted = true; clamp(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = fit(true)

    private fun clamp() {
        val cw = fbW() * scale; val ch = fbH() * scale
        tx = if (cw <= width) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
    }

    private fun zoomAt(f: Float, fx: Float, fy: Float) {
        val s = (scale * f).coerceIn(fitScale(), max(fitScale() * 6f, 3f))
        tx = fx - (fx - tx) * (s / scale); ty = fy - (fy - ty) * (s / scale)
        scale = s; fitted = abs(s - fitScale()) < 0.001f
        clamp(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = link?.bitmap ?: return
        canvas.save()
        canvas.translate(tx, ty); canvas.scale(scale, scale)
        canvas.drawBitmap(b, 0f, 0f, paint)
        canvas.restore()
    }

    // ---- pointer ----

    private fun toDesk(x: Float, y: Float): Pair<Int, Int> = ((x - tx) / scale).roundToInt() to ((y - ty) / scale).roundToInt()

    private fun pointer(x: Int, y: Int, b: Int) {
        px = x.coerceIn(0, max(0, fbW() - 1)); py = y.coerceIn(0, max(0, fbH() - 1)); buttons = b
        link?.rfb?.pointer(px, py, b)
    }

    private fun clickAt(x: Float, y: Float, button: Int) {
        val (dx, dy) = toDesk(x, y)
        pointer(dx, dy, 0); pointer(dx, dy, button); pointer(dx, dy, 0)
    }

    private fun wheel(bit: Int, n: Int) { repeat(n) { link?.rfb?.pointer(px, py, buttons or bit); link?.rfb?.pointer(px, py, buttons) } }

    private enum class Mode { NONE, PENDING, PAN, DRAG, TWO_PENDING, ZOOM, SCROLL }
    private var mode = Mode.NONE
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var held = false
    private var startSpan = 0f; private var lastSpan = 0f; private var startCx = 0f; private var startCy = 0f; private var lastCx = 0f; private var lastCy = 0f
    private var scrollAcc = 0f; private var hScrollAcc = 0f
    private val hold = Runnable { if (mode == Mode.PENDING && interactive) { held = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }

    private fun centroid(e: MotionEvent): Triple<Float, Float, Float> {
        val n = min(e.pointerCount, 2)
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += e.getX(i); cy += e.getY(i) }
        cx /= n; cy /= n
        val span = if (n == 2) hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) else 0f
        return Triple(cx, cy, span)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouse(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                mode = Mode.PENDING; held = false
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                postDelayed(hold, 420)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && mode != Mode.DRAG) {
                removeCallbacks(hold)
                val (cx, cy, span) = centroid(e)
                startSpan = span; lastSpan = span; startCx = cx; startCy = cy; lastCx = cx; lastCy = cy
                scrollAcc = 0f; hScrollAcc = 0f
                mode = Mode.TWO_PENDING
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING -> if (hypot(e.x - downX, e.y - downY) > slop) {
                    removeCallbacks(hold)
                    if (held && interactive) {
                        mode = Mode.DRAG
                        val (dx, dy) = toDesk(downX, downY)
                        pointer(dx, dy, 0); pointer(dx, dy, 1)
                        val (mx, my) = toDesk(e.x, e.y); pointer(mx, my, 1)
                    } else { mode = Mode.PAN; lastX = e.x; lastY = e.y }
                }
                Mode.PAN -> { tx += e.x - lastX; ty += e.y - lastY; lastX = e.x; lastY = e.y; clamp(); invalidate() }
                Mode.DRAG -> { val (mx, my) = toDesk(e.x, e.y); pointer(mx, my, 1) }
                Mode.TWO_PENDING, Mode.ZOOM, Mode.SCROLL -> if (e.pointerCount >= 2) {
                    val (cx, cy, span) = centroid(e)
                    if (mode == Mode.TWO_PENDING) {
                        val spread = abs(span - startSpan); val moved = hypot(cx - startCx, cy - startCy)
                        if (spread > slop * 1.5f || (moved > slop && !interactive)) mode = Mode.ZOOM
                        else if (moved > slop) mode = Mode.SCROLL
                    }
                    when (mode) {
                        Mode.ZOOM -> {
                            if (lastSpan > 0) zoomAt(span / lastSpan, cx, cy)
                            tx += cx - lastCx; ty += cy - lastCy; clamp(); invalidate()
                        }
                        Mode.SCROLL -> {
                            val (dx, dy) = toDesk(cx, cy); if (buttons == 0) { px = dx; py = dy }
                            val step = 28f * resources.displayMetrics.density
                            scrollAcc += cy - lastCy; hScrollAcc += cx - lastCx
                            while (scrollAcc > step) { wheel(8, 1); scrollAcc -= step }       // fingers down: content up (wheel up)
                            while (scrollAcc < -step) { wheel(16, 1); scrollAcc += step }
                            while (hScrollAcc > step) { wheel(32, 1); hScrollAcc -= step }
                            while (hScrollAcc < -step) { wheel(64, 1); hScrollAcc += step }
                        }
                        else -> Unit
                    }
                    lastSpan = span; lastCx = cx; lastCy = cy
                }
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.TWO_PENDING || mode == Mode.ZOOM || mode == Mode.SCROLL) mode = Mode.NONE
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (interactive) when (mode) {
                    Mode.PENDING -> clickAt(downX, downY, if (held) 4 else 1)
                    Mode.DRAG -> { val (mx, my) = toDesk(e.x, e.y); pointer(mx, my, 0) }
                    else -> Unit
                }
                mode = Mode.NONE; held = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (mode == Mode.DRAG) pointer(px, py, 0)
                mode = Mode.NONE; held = false
            }
        }
        return true
    }

    private fun mouseButtons(e: MotionEvent): Int {
        val s = e.buttonState
        return (if (s and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or (if (s and MotionEvent.BUTTON_TERTIARY != 0) 2 else 0) or
            (if (s and MotionEvent.BUTTON_SECONDARY != 0) 4 else 0)
    }

    private fun mouse(e: MotionEvent): Boolean {
        if (!interactive) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        val (dx, dy) = toDesk(e.x, e.y)
        pointer(dx, dy, if (e.actionMasked == MotionEvent.ACTION_UP) 0 else mouseButtons(e))
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> if (interactive) { val (dx, dy) = toDesk(e.x, e.y); pointer(dx, dy, mouseButtons(e)) }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL); val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (!interactive) { if (v != 0f) zoomAt(if (v > 0) 1.15f else 1 / 1.15f, e.x, e.y); return true }
                val (dx, dy) = toDesk(e.x, e.y); px = dx.coerceIn(0, max(0, fbW() - 1)); py = dy.coerceIn(0, max(0, fbH() - 1))
                if (e.metaState and KeyEvent.META_CTRL_ON != 0 && v != 0f) { zoomAt(if (v > 0) 1.15f else 1 / 1.15f, e.x, e.y); return true }
                if (v > 0) wheel(8, max(1, v.roundToInt())) else if (v < 0) wheel(16, max(1, (-v).roundToInt()))
                if (h > 0) wheel(64, 1) else if (h < 0) wheel(32, 1)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> if (interactive) { val (dx, dy) = toDesk(e.x, e.y); pointer(dx, dy, mouseButtons(e)) }
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    // ---- keyboard ----

    private val downKeys = HashMap<Int, Int>()

    private fun passes(keyCode: Int) = interactive && keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
        keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_POWER

    private fun keysymOf(e: KeyEvent): Int? = Keysym.ofKeyCode(e.keyCode)
        ?: e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
            .takeIf { it > 0 }?.let(Keysym::ofChar)

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        if (!passes(keyCode)) return super.onKeyDown(keyCode, e)
        val ks = keysymOf(e) ?: return super.onKeyDown(keyCode, e)
        val l = link ?: return true
        if (e.repeatCount == 0 && l.sticky.isNotEmpty() && ks !in MODIFIERS) { downKeys.remove(keyCode); l.tap(ks); return true }
        downKeys[keyCode] = ks
        l.key(ks, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        val ks = downKeys.remove(keyCode) ?: return if (passes(keyCode)) true else super.onKeyUp(keyCode, e)
        link?.key(ks, false)
        return true
    }

    override fun onCheckIsTextEditor() = interactive

    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        if (!interactive) return null
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            /** What the IME is still composing, already typed on the desktop; corrections are sent as backspaces. */
            var composing = ""
            fun retype(next: String) {
                val keep = composing.commonPrefixWith(next).length
                repeat(composing.length - keep) { link?.tap(Keysym.BACKSPACE) }
                link?.type(next.substring(keep))
                composing = next
            }
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { retype(text.toString()); composing = ""; return true }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean { retype(text.toString()); return true }
            override fun finishComposingText(): Boolean { composing = ""; return true }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(max(1, before)) { link?.tap(Keysym.BACKSPACE) }
                repeat(after) { link?.tap(Keysym.DELETE) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event) else if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    fun showKeyboard(show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        if (show) { requestFocus(); imm.showSoftInput(this, 0) } else imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** The desktop size that would fill this view at about [density] desktop pixels per view pixel. */
    fun idealSize(density: Float = 1.25f): Pair<Int, Int> {
        val w = (width / density).roundToInt().coerceIn(800, 2560); val h = (height / density).roundToInt().coerceIn(600, 1600)
        return (w / 8 * 8) to (h / 8 * 8)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hold)
        if (link?.view === this) link?.view = null
        downKeys.values.forEach { link?.key(it, false) }; downKeys.clear()
        if (buttons != 0) link?.rfb?.pointer(px, py, 0)
    }

    companion object {
        val MODIFIERS = setOf(Keysym.SHIFT_L, Keysym.SHIFT_R, Keysym.CONTROL_L, Keysym.CONTROL_R, Keysym.ALT_L, Keysym.ALT_R, Keysym.SUPER_L, Keysym.CAPS_LOCK)
    }
}
