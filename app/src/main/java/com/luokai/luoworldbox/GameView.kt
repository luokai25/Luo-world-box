package com.luokai.luoworldbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.luokai.luoworldbox.core.Fx
import com.luokai.luoworldbox.core.Kind
import com.luokai.luoworldbox.core.Report
import com.luokai.luoworldbox.core.SaveStore
import com.luokai.luoworldbox.core.STEP
import com.luokai.luoworldbox.core.Tile
import com.luokai.luoworldbox.core.World
import com.luokai.luoworldbox.core.WorldConfig
import com.luokai.luoworldbox.core.WorldGen
import com.luokai.luoworldbox.core.Zone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GameView(context: Context, private val host: Host) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    interface Host {
        fun showInfo(title: String, text: String)
        fun openMenu()
    }

    // ------------------------------------------------------------------ state
    @Volatile var world: World? = null
    @Volatile private var loading = true
    @Volatile private var loadingText = "Splitting atoms..."
    @Volatile var appPaused = false
    @Volatile var paused = false
    @Volatile var showBorders = true
    private val ui = Handler(Looper.getMainLooper())
    private val cmds = ConcurrentLinkedQueue<Runnable>()
    private var thread: Thread? = null
    @Volatile private var running = false

    // camera (tile units)
    @Volatile private var camX = 64f
    @Volatile private var camY = 64f
    @Volatile private var zoom = 5f
    @Volatile private var needFit = true

    // HUD state
    private val speeds = intArrayOf(1, 2, 4, 8)
    @Volatile private var speedIdx = 0
    @Volatile private var tab = Tab.SHAPING
    @Volatile private var selected: Power? = null
    private val brushSizes = intArrayOf(1, 2, 3, 5, 8)
    @Volatile private var brushIdx = 1
    private var scrollX = 0f

    // touch state
    private val MODE_NONE = 0
    private val MODE_HUD = 1
    private val MODE_PAN = 2
    private val MODE_PAINT = 3
    private val MODE_ZOOM = 4
    @Volatile private var mode = MODE_NONE
    @Volatile private var paintX = 0f
    @Volatile private var paintY = 0f
    @Volatile private var firstPaint = false
    private var lastApply = 0L
    private var lastWx = 0f
    private var lastWy = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f
    private var hudStartX = 0f
    private var hudScrollStart = 0f
    private var zMidX = 0f
    private var zMidY = 0f
    private var zDist = 1f

    // rendering
    private val dp = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var bmpWorld: World? = null
    private var px = IntArray(0)
    private val dst = RectF()
    private val rect = RectF()
    private val bmpPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var frame = 0
    private var popCache = 0
    private var mobCache = 0
    private val toasts = ArrayList<String>()
    private val toastLife = ArrayList<Float>()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ------------------------------------------------------------------ lifecycle
    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this, "luo-game").also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        try { thread?.join(1500) } catch (e: InterruptedException) { }
        thread = null
    }

    // ------------------------------------------------------------------ public API
    fun newWorld(cfg: WorldConfig, seed: Long = System.currentTimeMillis()) {
        loading = true
        loadingText = "Splitting atoms..."
        Thread {
            val w = WorldGen.generate(cfg, seed) { loadingText = it }
            cmds.add(Runnable {
                world = w
                needFit = true
                loading = false
            })
        }.start()
    }

    fun loadWorld(w: World) {
        cmds.add(Runnable { world = w; needFit = true; loading = false })
    }

    fun postGame(fn: (World) -> Unit) {
        cmds.add(Runnable { world?.let(fn) })
    }

    fun queryGame(fn: (World) -> String, cb: (String) -> Unit) {
        cmds.add(Runnable {
            val w = world
            val s = if (w != null) fn(w) else ""
            ui.post { cb(s) }
        })
    }

    fun saveSlot(slot: String, done: (Boolean) -> Unit) {
        cmds.add(Runnable {
            val w = world
            var ok = false
            if (w != null) {
                try { SaveStore.save(context.filesDir, slot, w); ok = true } catch (e: Exception) { }
            }
            ui.post { done(ok) }
        })
    }

    /** Blocks briefly; used from onPause so the world is written before the process may die. */
    fun saveNowBlocking() {
        if (!running) return
        val latch = CountDownLatch(1)
        cmds.add(Runnable {
            try { world?.let { SaveStore.save(context.filesDir, SaveStore.AUTO, it) } } catch (e: Exception) { }
            latch.countDown()
        })
        try { latch.await(2, TimeUnit.SECONDS) } catch (e: InterruptedException) { }
    }

    fun currentWorldSize(): Int = world?.w ?: 128

    // ------------------------------------------------------------------ game loop
    override fun run() {
        var last = System.nanoTime()
        var acc = 0f
        var autosave = 0f
        while (running) {
            val now = System.nanoTime()
            val dt = min(0.1f, (now - last) / 1e9f)
            last = now
            while (true) { (cmds.poll() ?: break).run() }
            var ti = 0
            while (ti < toasts.size) {
                toastLife[ti] = toastLife[ti] - dt
                if (toastLife[ti] <= 0f) { toasts.removeAt(ti); toastLife.removeAt(ti) } else ti++
            }
            val w = world
            if (w != null && !loading) {
                handleHeld(w, now / 1_000_000L)
                if (!paused && !appPaused) {
                    acc += dt * speeds[speedIdx]
                    var n = 0
                    while (acc >= STEP && n < 10) { w.step(STEP); acc -= STEP; n++ }
                    if (n == 10) acc = 0f
                    for (t in w.drainToasts()) {
                        toasts.add(t); toastLife.add(6f)
                        if (toasts.size > 5) { toasts.removeAt(0); toastLife.removeAt(0) }
                    }
                    autosave += dt
                    if (autosave > 90f) {
                        autosave = 0f
                        try { SaveStore.save(context.filesDir, SaveStore.AUTO, w) } catch (e: Exception) { }
                    }
                }
            }
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
                if (canvas != null) renderFrame(canvas)
            } catch (e: Exception) {
                // surface may be going away
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { }
            }
            val spent = (System.nanoTime() - now) / 1_000_000L
            if (spent < 16) try { Thread.sleep(16 - spent) } catch (e: InterruptedException) { }
        }
    }

    private fun apply(w: World, p: Power, x: Float, y: Float) {
        val r = if (p.brush) brushSizes[brushIdx] else 1
        if (p.id.startsWith("spawn:")) {
            repeat(max(1, r)) { w.applyPower(p.id, x, y, r) }
        } else {
            w.applyPower(p.id, x, y, r)
        }
    }

    private fun handleHeld(w: World, nowMs: Long) {
        val p = selected
        if (mode != MODE_PAINT || p == null) return
        val wx = camX + (paintX - width / 2f) / zoom
        val wy = camY + (paintY - height / 2f) / zoom
        if (firstPaint) {
            firstPaint = false
            apply(w, p, wx, wy)
            lastApply = nowMs; lastWx = wx; lastWy = wy
        } else if (p.repeatMs > 0 && nowMs - lastApply >= p.repeatMs) {
            if (p.brush) {
                val r = brushSizes[brushIdx]
                val dist = hypot(wx - lastWx, wy - lastWy)
                val stepLen = max(1f, r * 0.8f)
                val n = (dist / stepLen).toInt()
                for (k in 1..n) {
                    val t = k / (n + 1f)
                    apply(w, p, lastWx + (wx - lastWx) * t, lastWy + (wy - lastWy) * t)
                }
            }
            apply(w, p, wx, wy)
            lastApply = nowMs; lastWx = wx; lastWy = wy
        }
    }

    // ------------------------------------------------------------------ HUD geometry
    private val btn get() = 50f * dp
    private val gap get() = 5f * dp
    private val pad get() = 8f * dp
    private val tabH get() = 34f * dp
    private val barH get() = tabH + pad + btn * 2 + gap + pad
    private val barTop get() = height - barH
    private val powerX0 get() = pad + btn * 2 + gap * 3

    private fun tabPowers(): List<Power> = Powers.forTab(tab)

    private fun maxScroll(): Float {
        val cols = (tabPowers().size + 1) / 2
        return max(0f, cols * (btn + gap) - (width - pad - powerX0))
    }

    private fun menuRect(r: RectF) { r.set(width - pad - 46 * dp, 28 * dp, width - pad, 28 * dp + 46 * dp) }
    private fun brushRect(r: RectF) { r.set(pad, 28 * dp, pad + 46 * dp, 28 * dp + 46 * dp) }

    // ------------------------------------------------------------------ touch
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (loading || world == null) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                downT = ev.eventTime; moved = false
                menuRect(rect)
                val inMenu = rect.contains(ev.x, ev.y)
                val inBrush = RectF().also { brushRect(it) }.contains(ev.x, ev.y)
                val p = selected
                mode = when {
                    ev.y >= barTop -> { hudStartX = ev.x; hudScrollStart = scrollX; MODE_HUD }
                    inMenu || inBrush -> MODE_NONE
                    p != null && !p.inspect -> {
                        paintX = ev.x; paintY = ev.y; firstPaint = true; MODE_PAINT
                    }
                    else -> MODE_PAN
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    mode = MODE_ZOOM
                    firstPaint = false
                    zMidX = (ev.getX(0) + ev.getX(1)) / 2f
                    zMidY = (ev.getY(0) + ev.getY(1)) / 2f
                    zDist = max(1f, hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.x - downX, ev.y - downY) > 10 * dp) moved = true
                when (mode) {
                    MODE_HUD -> {
                        if (ev.x >= powerX0 - pad || hudStartX >= powerX0 - pad) {
                            if (hudStartX >= powerX0 - pad) {
                                scrollX = (hudScrollStart - (ev.x - hudStartX)).coerceIn(0f, maxScroll())
                            }
                        }
                    }
                    MODE_PAN -> {
                        camX -= (ev.x - lastX) / zoom
                        camY -= (ev.y - lastY) / zoom
                        clampCamera()
                    }
                    MODE_PAINT -> { paintX = ev.x; paintY = ev.y }
                    MODE_ZOOM -> if (ev.pointerCount >= 2) {
                        val mx = (ev.getX(0) + ev.getX(1)) / 2f
                        val my = (ev.getY(0) + ev.getY(1)) / 2f
                        val d = max(1f, hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)))
                        val wx = camX + (zMidX - width / 2f) / zoom
                        val wy = camY + (zMidY - height / 2f) / zoom
                        zoom = (zoom * d / zDist).coerceIn(minZoom(), 64f)
                        camX = wx - (mx - width / 2f) / zoom
                        camY = wy - (my - height / 2f) / zoom
                        clampCamera()
                        zMidX = mx; zMidY = my; zDist = d
                    }
                }
                lastX = ev.x; lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == MODE_ZOOM && ev.pointerCount <= 2) {
                    mode = MODE_NONE
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = !moved && ev.eventTime - downT < 400
                when (mode) {
                    MODE_HUD -> if (tap) hudTap(ev.x, ev.y)
                    MODE_PAN -> if (tap) inspectAt(ev.x, ev.y)
                    MODE_NONE -> if (tap) {
                        menuRect(rect)
                        if (rect.contains(ev.x, ev.y)) host.openMenu()
                        brushRect(rect)
                        if (rect.contains(ev.x, ev.y)) brushIdx = (brushIdx + 1) % brushSizes.size
                    }
                }
                mode = MODE_NONE
                firstPaint = false
            }
            MotionEvent.ACTION_CANCEL -> { mode = MODE_NONE; firstPaint = false }
        }
        return true
    }

    private fun hudTap(x: Float, y: Float) {
        // tabs
        val tabW = (width - 2 * pad) / Tab.values().size
        if (y < barTop + tabH) {
            val i = ((x - pad) / tabW).toInt()
            if (i in Tab.values().indices) {
                tab = Tab.values()[i]
                scrollX = 0f
            }
            return
        }
        val y0 = barTop + tabH + pad
        val y1 = y0 + btn + gap
        // undo = deselect
        if (x in pad..(pad + btn)) { selected = null; return }
        val bx = pad + btn + gap
        if (x in bx..(bx + btn)) {
            if (y in y0..(y0 + btn)) paused = !paused
            else if (y in y1..(y1 + btn)) speedIdx = (speedIdx + 1) % speeds.size
            return
        }
        if (x < powerX0 - pad) return
        val list = tabPowers()
        for (i in list.indices) {
            val col = i / 2
            val row = i % 2
            val px0 = powerX0 + col * (btn + gap) - scrollX
            val py0 = if (row == 0) y0 else y1
            if (x in px0..(px0 + btn) && y in py0..(py0 + btn)) {
                selected = list[i]
                return
            }
        }
    }

    private fun inspectAt(sx: Float, sy: Float) {
        val wx = camX + (sx - width / 2f) / zoom
        val wy = camY + (sy - height / 2f) / zoom
        val reach = max(1.5f, 26f * dp / zoom)
        cmds.add(Runnable {
            val w = world ?: return@Runnable
            val c = w.creatureAt(wx, wy, reach)
            var title = ""
            var body = ""
            if (c != null) {
                title = c.sp.name
                body = Report.creature(w, c)
            } else {
                for (v in w.villages) {
                    val dx = v.cx - wx; val dy = v.cy - wy
                    if (dx * dx + dy * dy < v.radius * v.radius) {
                        val k = w.kingdomById(v.kingdomId)
                        title = "Village ${v.name}"
                        body = "Kingdom: ${k?.name ?: "none"}\nPopulation: ${v.pop}\nHouses: ${v.houses.size}\nFood: ${v.food.toInt()}"
                        break
                    }
                }
            }
            if (title.isNotEmpty()) ui.post { host.showInfo(title, body) }
        })
    }

    private fun minZoom(): Float {
        val w = world ?: return 1f
        return min(width / w.w.toFloat(), height / w.h.toFloat()) * 0.6f
    }

    private fun clampCamera() {
        val w = world ?: return
        camX = camX.coerceIn(0f, w.w.toFloat())
        camY = camY.coerceIn(0f, w.h.toFloat())
    }

    // ------------------------------------------------------------------ rendering
    private fun scaleColor(c: Int, f: Float): Int {
        val r = min(255, ((c shr 16 and 255) * f).toInt())
        val g = min(255, ((c shr 8 and 255) * f).toInt())
        val b = min(255, ((c and 255) * f).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun tileColor(w: World, i: Int, x: Int, y: Int): Int {
        val t = w.tiles[i].toInt()
        val hsh = ((x * 73856093) xor (y * 19349663)) and 0xFF
        var c = scaleColor(Tile.COLORS[t], 0.93f + hsh / 255f * 0.14f)
        val f = w.fire[i].toInt() and 0xFF
        if (f != 0) {
            c = if (((hsh + frame) and 3) < 2) 0xFFFF8A1F.toInt() else 0xFFE03A0A.toInt()
        } else if (w.ore[i].toInt() != 0 && (t == Tile.MOUNTAIN || t == Tile.HILLS)) {
            c = scaleColor(c, 1.45f)
        }
        return c
    }

    private fun refreshBitmap(w: World) {
        if (bmpWorld !== w || bitmap == null) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w.w, w.h, Bitmap.Config.ARGB_8888)
            px = IntArray(w.w * w.h)
            bmpWorld = w
            w.markAllDirty()
        }
        if (w.dirtyMax >= w.dirtyMin) {
            val y0 = max(0, w.dirtyMin)
            val y1 = min(w.h - 1, w.dirtyMax)
            for (y in y0..y1) for (x in 0 until w.w) {
                val i = y * w.w + x
                px[i] = tileColor(w, i, x, y)
            }
            bitmap!!.setPixels(px, y0 * w.w, w.w, 0, y0, w.w, y1 - y0 + 1)
            w.clearDirty()
        }
    }

    private fun renderFrame(canvas: Canvas) {
        frame++
        canvas.drawColor(0xFF0A0F1E.toInt())
        val w = world
        if (loading || w == null) { drawLoading(canvas); return }
        if (needFit) {
            camX = w.w / 2f; camY = w.h / 2f
            zoom = min(width / w.w.toFloat(), (height - barH) / w.h.toFloat()) * 0.95f
            zoom = max(zoom, minZoom())
            needFit = false
        }
        refreshBitmap(w)
        val s = zoom
        val ox = width / 2f - camX * s
        val oy = height / 2f - camY * s
        dst.set(ox, oy, ox + w.w * s, oy + w.h * s)
        canvas.drawBitmap(bitmap!!, null, dst, bmpPaint)

        stroke.color = 0x66FFFFFF; stroke.strokeWidth = 1.5f * dp
        canvas.drawRect(dst, stroke)

        val vx0 = max(0, ((-ox) / s).toInt() - 1)
        val vy0 = max(0, ((-oy) / s).toInt() - 1)
        val vx1 = min(w.w - 1, ((width - ox) / s).toInt() + 1)
        val vy1 = min(w.h - 1, ((height - oy) / s).toInt() + 1)

        // kingdom borders
        if (showBorders && s >= 1.0f) drawVillages(canvas, w, s, ox, oy)

        // trees
        if (s >= 7f) {
            for (y in vy0..vy1) for (x in vx0..vx1) {
                if (w.tiles[y * w.w + x].toInt() == Tile.FOREST && w.fire[y * w.w + x].toInt() == 0) {
                    val cx = ox + (x + 0.5f) * s
                    val cy = oy + (y + 0.5f) * s
                    fill.color = 0xFF5B3A1E.toInt()
                    canvas.drawRect(cx - s * 0.06f, cy, cx + s * 0.06f, cy + s * 0.38f, fill)
                    fill.color = 0xFF1F5A14.toInt()
                    canvas.drawRect(cx - s * 0.30f, cy - s * 0.05f, cx + s * 0.30f, cy + s * 0.22f, fill)
                    fill.color = 0xFF2F7A1E.toInt()
                    canvas.drawRect(cx - s * 0.22f, cy - s * 0.28f, cx + s * 0.22f, cy, fill)
                    fill.color = 0xFF47A02E.toInt()
                    canvas.drawRect(cx - s * 0.12f, cy - s * 0.42f, cx + s * 0.12f, cy - s * 0.2f, fill)
                }
            }
        }

        // houses
        if (s >= 3f) {
            for (v in w.villages) {
                val k = w.kingdomById(v.kingdomId)
                val roof = k?.color ?: 0xFFCC4444.toInt()
                for (hi in v.houses) {
                    val hx = hi % w.w
                    val hy = hi / w.w
                    if (hx < vx0 || hx > vx1 || hy < vy0 || hy > vy1) continue
                    val cx = ox + (hx + 0.5f) * s
                    val cy = oy + (hy + 0.5f) * s
                    val burn = (w.fire[hi].toInt() and 0xFF) != 0
                    fill.color = if (burn) 0xFF6A2A10.toInt() else 0xFFE8D2A0.toInt()
                    canvas.drawRect(cx - s * 0.36f, cy - s * 0.1f, cx + s * 0.36f, cy + s * 0.36f, fill)
                    fill.color = roof
                    canvas.drawRect(cx - s * 0.44f, cy - s * 0.38f, cx + s * 0.44f, cy - s * 0.08f, fill)
                    fill.color = 0xFF5A3A1E.toInt()
                    canvas.drawRect(cx - s * 0.07f, cy + s * 0.12f, cx + s * 0.07f, cy + s * 0.36f, fill)
                }
            }
        }

        drawCreatures(canvas, w, s, ox, oy)
        drawZonesAndFx(canvas, w, s, ox, oy)
        drawBrush(canvas)
        drawHud(canvas, w)
    }

    private fun drawVillages(canvas: Canvas, w: World, s: Float, ox: Float, oy: Float) {
        for (v in w.villages) {
            val k = w.kingdomById(v.kingdomId) ?: continue
            val cx = ox + v.cx * s
            val cy = oy + v.cy * s
            val r = v.radius * s
            if (cx + r < 0 || cx - r > width || cy + r < 0 || cy - r > height) continue
            fill.color = (k.color and 0x00FFFFFF) or 0x26000000
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = (k.color and 0x00FFFFFF) or 0xB0000000.toInt()
            stroke.strokeWidth = 2f * dp
            canvas.drawCircle(cx, cy, r, stroke)
            if (v.id == k.capitalId) {
                text.textSize = 13f * dp
                text.style = Paint.Style.STROKE; text.strokeWidth = 3f * dp; text.color = 0xCC000000.toInt()
                canvas.drawText(k.name, cx, cy - r - 4 * dp, text)
                text.style = Paint.Style.FILL; text.color = k.color
                canvas.drawText(k.name, cx, cy - r - 4 * dp, text)
            }
        }
    }

    private fun drawCreatures(canvas: Canvas, w: World, s: Float, ox: Float, oy: Float) {
        val big = s >= 10f
        val size = (s * 0.9f).coerceIn(3f * dp, 60f * dp)
        if (big) text.textSize = (s * 1.1f).coerceAtMost(80f * dp)
        for (c in w.creatures) {
            if (!c.alive) continue
            val cx = ox + c.x * s
            val cy = oy + c.y * s
            if (cx < -50 || cx > width + 50 || cy < -50 || cy > height + 50) continue
            val sp = c.sp
            if (s < 2.5f) {
                fill.color = sp.color
                canvas.drawRect(cx - 1.5f * dp, cy - 1.5f * dp, cx + 1.5f * dp, cy + 1.5f * dp, fill)
            } else if (!big) {
                fill.color = sp.color
                canvas.drawRect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2, fill)
                fill.color = 0xFF000000.toInt()
                canvas.drawRect(cx - size * 0.28f, cy - size * 0.22f, cx - size * 0.1f, cy - size * 0.04f, fill)
                canvas.drawRect(cx + size * 0.1f, cy - size * 0.22f, cx + size * 0.28f, cy - size * 0.04f, fill)
                if (sp.kind == Kind.SAPIENT && s >= 4f) {
                    val k = w.kingdomById(c.kingdomId)
                    if (k != null) {
                        fill.color = k.color
                        canvas.drawRect(cx - size / 2, cy - size / 2 - 3 * dp, cx + size / 2, cy - size / 2, fill)
                    }
                }
            } else {
                val fm = text.fontMetrics
                text.color = Color.WHITE
                text.style = Paint.Style.FILL
                canvas.drawText(sp.emoji, cx, cy - (fm.ascent + fm.descent) / 2f, text)
                if (sp.kind == Kind.SAPIENT) {
                    val k = w.kingdomById(c.kingdomId)
                    if (k != null) {
                        fill.color = k.color
                        canvas.drawCircle(cx, cy + s * 0.55f, 3f * dp, fill)
                    }
                }
            }
            if (c.burning > 0f) {
                fill.color = 0xFFFF7A1A.toInt()
                canvas.drawRect(cx - 2 * dp, cy - size / 2 - 4 * dp, cx + 2 * dp, cy - size / 2, fill)
            }
            if (c.infected >= 0f) {
                fill.color = 0xFF66FF66.toInt()
                canvas.drawCircle(cx, cy - size / 2 - 3 * dp, 2.5f * dp, fill)
            }
            if (s >= 6f && c.hp < sp.hp) {
                fill.color = 0xAA000000.toInt()
                canvas.drawRect(cx - size / 2, cy + size / 2 + 1, cx + size / 2, cy + size / 2 + 3 * dp, fill)
                fill.color = 0xFF3DDC55.toInt()
                canvas.drawRect(cx - size / 2, cy + size / 2 + 1, cx - size / 2 + size * (c.hp / sp.hp).coerceIn(0f, 1f), cy + size / 2 + 3 * dp, fill)
            }
        }
    }

    private fun drawZonesAndFx(canvas: Canvas, w: World, s: Float, ox: Float, oy: Float) {
        for (z in w.zones) {
            val cx = ox + z.x * s
            val cy = oy + z.y * s
            when (z.type) {
                Zone.RAIN -> {
                    fill.color = 0x553A6FC8
                    canvas.drawCircle(cx, cy, z.r * s, fill)
                    stroke.color = 0xCC9CC8FF.toInt(); stroke.strokeWidth = 1.5f * dp
                    for (k in 0 until 14) {
                        val a = (k * 97 + frame * 3) % 360
                        val rx = cx + ((a * 7) % 100 - 50) / 50f * z.r * s * 0.8f
                        val ry = cy + (((a * 13 + frame * 6) % 100) - 50) / 50f * z.r * s * 0.8f
                        canvas.drawLine(rx, ry, rx - 2 * dp, ry + 8 * dp, stroke)
                    }
                }
                Zone.ACID -> {
                    fill.color = 0x5570C020
                    canvas.drawCircle(cx, cy, z.r * s, fill)
                }
                Zone.TORNADO -> {
                    stroke.color = 0xFFD0D0D8.toInt(); stroke.strokeWidth = 2.5f * dp
                    for (k in 0 until 6) {
                        val ph = frame * 0.25f + k
                        val rr = (1f + k * 0.55f) * s * 0.8f
                        val yy = cy - k * s * 0.7f
                        canvas.drawLine(cx - rr + sin(ph) * s * 0.3f, yy, cx + rr + sin(ph) * s * 0.3f, yy, stroke)
                    }
                }
                Zone.BLACKHOLE -> {
                    fill.color = 0xFF000000.toInt()
                    canvas.drawCircle(cx, cy, z.r * s * 0.8f, fill)
                    stroke.color = 0xFFB050FF.toInt(); stroke.strokeWidth = 3f * dp
                    canvas.drawCircle(cx, cy, z.r * s * 0.8f + 2 * dp, stroke)
                }
                Zone.METEOR -> {
                    val fall = z.life / 0.9f
                    fill.color = 0xFFFFB030.toInt()
                    canvas.drawCircle(cx + fall * 6 * s, cy - fall * 22 * s, max(3f * dp, s * 0.9f), fill)
                }
            }
        }
        for (f in w.fxs()) {
            val cx = ox + f.x * s
            val cy = oy + f.y * s
            val p = (f.t / f.life).coerceIn(0f, 1f)
            when (f.type) {
                Fx.EXPLOSION -> {
                    fill.color = ((((1f - p) * 190).toInt()) shl 24) or 0xFF9A20
                    canvas.drawCircle(cx, cy, f.r * s * (0.3f + p * 0.9f), fill)
                }
                Fx.RING -> {
                    stroke.color = ((((1f - p) * 255).toInt()) shl 24) or 0xFFF0A0
                    stroke.strokeWidth = 3f * dp
                    canvas.drawCircle(cx, cy, f.r * s * (0.3f + p * 0.8f), stroke)
                }
                Fx.LIGHTNING -> {
                    stroke.color = ((((1f - p) * 255).toInt()) shl 24) or 0xFFFFFF
                    stroke.strokeWidth = 3f * dp
                    var px0 = cx + 4 * dp
                    var py0 = 0f
                    val steps = 8
                    for (k in 1..steps) {
                        val py1 = cy * k / steps
                        val px1 = if (k == steps) cx else cx + (((k * 37 + frame / 2) % 9) - 4) * dp
                        canvas.drawLine(px0, py0, px1, py1, stroke)
                        px0 = px1; py0 = py1
                    }
                }
            }
        }
    }

    private fun drawBrush(canvas: Canvas) {
        val p = selected ?: return
        if (mode != MODE_PAINT || !p.brush) return
        val r = brushSizes[brushIdx]
        stroke.color = 0xCCFFFFFF.toInt(); stroke.strokeWidth = 1.5f * dp
        canvas.drawCircle(paintX, paintY, (r + 0.5f) * zoom, stroke)
    }

    // ------------------------------------------------------------------ HUD
    private fun roundBox(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, border: Int) {
        rect.set(l, t, r, b)
        fill.color = color
        canvas.drawRoundRect(rect, 6 * dp, 6 * dp, fill)
        stroke.color = border; stroke.strokeWidth = 2f * dp
        canvas.drawRoundRect(rect, 6 * dp, 6 * dp, stroke)
    }

    private fun emoji(canvas: Canvas, e: String, cx: Float, cy: Float, size: Float) {
        text.style = Paint.Style.FILL
        text.color = Color.WHITE
        text.textSize = size
        val fm = text.fontMetrics
        canvas.drawText(e, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawHud(canvas: Canvas, w: World) {
        if (frame % 30 == 1) {
            var a = 0; var b = 0
            for (c in w.creatures) if (c.alive) { if (c.sp.kind == Kind.SAPIENT) a++ else b++ }
            popCache = a; mobCache = b
        }
        // status
        val line1 = "${w.age.name}  •  Year ${w.year}  •  ${w.monthName}  •  ${w.moonsLeft} moons left"
        val sel = selected
        val line2 = "Pop $popCache   Mobs $mobCache   " + (if (sel != null) "${sel.name}" else "Move mode") +
            (if (paused) "   ⏸ PAUSED" else "") + "   ×${speeds[speedIdx]}"
        text.textSize = 12f * dp
        val tw = max(text.measureText(line1), text.measureText(line2)) + 20 * dp
        roundBox(canvas, width / 2f - tw / 2, barTop - 46 * dp, width / 2f + tw / 2, barTop - 6 * dp, 0xAA101418.toInt(), 0x55FFFFFF)
        text.style = Paint.Style.FILL
        text.color = 0xFFFFD27A.toInt()
        canvas.drawText(line1, width / 2f, barTop - 29 * dp, text)
        text.color = 0xFFE8E8E8.toInt()
        canvas.drawText(line2, width / 2f, barTop - 12 * dp, text)

        // event toasts (top left, under the brush button)
        text.textSize = 12f * dp
        text.textAlign = Paint.Align.LEFT
        for (i in toasts.indices) {
            val y = 28 * dp + 46 * dp + 22 * dp + i * 18 * dp
            val a = (min(1f, toastLife[i]) * 255).toInt()
            text.style = Paint.Style.STROKE; text.strokeWidth = 3f * dp; text.color = (a shl 24)
            canvas.drawText(toasts[i], pad, y, text)
            text.style = Paint.Style.FILL; text.color = (a shl 24) or 0xFFD27A
            canvas.drawText(toasts[i], pad, y, text)
        }
        text.textAlign = Paint.Align.CENTER

        // top buttons
        menuRect(rect)
        roundBox(canvas, rect.left, rect.top, rect.right, rect.bottom, 0xFFC0392B.toInt(), 0xFF7A1E16.toInt())
        emoji(canvas, "☰", rect.centerX(), rect.centerY(), 24 * dp)
        brushRect(rect)
        roundBox(canvas, rect.left, rect.top, rect.right, rect.bottom, 0xCC20261E.toInt(), 0xFF6A7A5A.toInt())
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(rect.centerX(), rect.centerY(), (brushSizes[brushIdx] + 1) * 2.4f * dp, fill)

        // bar
        roundBox(canvas, 0f, barTop, width.toFloat(), height.toFloat() + 10 * dp, 0xFF343A30.toInt(), 0xFF1B1F19.toInt())
        val tabs = Tab.values()
        val tabW = (width - 2 * pad) / tabs.size
        for (i in tabs.indices) {
            val on = tabs[i] == tab
            roundBox(canvas, pad + i * tabW + 2 * dp, barTop + 2 * dp, pad + (i + 1) * tabW - 2 * dp, barTop + tabH,
                if (on) 0xFFD0382B.toInt() else 0xFF4A5244.toInt(), if (on) 0xFF8A1E16.toInt() else 0xFF2A2F26.toInt())
            emoji(canvas, tabs[i].icon, pad + (i + 0.5f) * tabW, barTop + tabH / 2f + 1 * dp, 18 * dp)
        }
        val y0 = barTop + tabH + pad
        val y1 = y0 + btn + gap
        // deselect (red), pause, speed
        roundBox(canvas, pad, y0, pad + btn, y1 + btn, 0xFFD0382B.toInt(), 0xFF8A1E16.toInt())
        emoji(canvas, "↩", pad + btn / 2, (y0 + y1 + btn) / 2, 28 * dp)
        val bx = pad + btn + gap
        roundBox(canvas, bx, y0, bx + btn, y0 + btn, 0xFF22282A.toInt(), if (paused) 0xFFFFC93C.toInt() else 0xFF5A6A5A.toInt())
        emoji(canvas, if (paused) "▶" else "⏸", bx + btn / 2, y0 + btn / 2, 24 * dp)
        roundBox(canvas, bx, y1, bx + btn, y1 + btn, 0xFF22282A.toInt(), 0xFF5A6A5A.toInt())
        emoji(canvas, "⏳", bx + btn / 2, y1 + btn / 2 - 5 * dp, 20 * dp)
        text.textSize = 11f * dp; text.color = 0xFFFFD27A.toInt()
        canvas.drawText("×${speeds[speedIdx]}", bx + btn / 2, y1 + btn - 6 * dp, text)

        // powers (clipped)
        canvas.save()
        canvas.clipRect(powerX0 - gap, barTop + tabH, width.toFloat(), height.toFloat())
        val list = tabPowers()
        for (i in list.indices) {
            val col = i / 2
            val row = i % 2
            val x = powerX0 + col * (btn + gap) - scrollX
            if (x > width || x + btn < powerX0 - gap) continue
            val y = if (row == 0) y0 else y1
            val on = sel === list[i]
            roundBox(canvas, x, y, x + btn, y + btn, if (on) 0xFF4A4A22.toInt() else 0xFF22282A.toInt(),
                if (on) 0xFFFFC93C.toInt() else 0xFF5A6A5A.toInt())
            emoji(canvas, list[i].icon, x + btn / 2, y + btn / 2, 26 * dp)
        }
        canvas.restore()
        // tab title toast
        text.textSize = 13f * dp
        text.style = Paint.Style.STROKE; text.strokeWidth = 3f * dp; text.color = 0xFF000000.toInt()
        canvas.drawText(tab.title, width / 2f, barTop - 52 * dp, text)
        text.style = Paint.Style.FILL; text.color = 0xFFFFA630.toInt()
        canvas.drawText(tab.title, width / 2f, barTop - 52 * dp, text)
    }

    private fun drawLoading(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // stars
        fill.color = 0xFFFFFFFF.toInt()
        for (k in 0 until 40) {
            val sx = ((k * 7919) % 1000) / 1000f * width
            val sy = ((k * 104729) % 1000) / 1000f * height
            canvas.drawRect(sx, sy, sx + 2 * dp, sy + 2 * dp, fill)
        }
        // planet
        val r = min(width, height) * 0.18f
        fill.color = 0xFF2F7BD6.toInt()
        canvas.drawCircle(cx, cy, r, fill)
        fill.color = 0xFF4C9A2A.toInt()
        val sh = (frame * 0.02f)
        canvas.drawCircle(cx + sin(sh) * r * 0.4f, cy - r * 0.2f, r * 0.35f, fill)
        canvas.drawCircle(cx - sin(sh) * r * 0.3f, cy + r * 0.35f, r * 0.22f, fill)
        stroke.color = 0xFF9FE8FF.toInt(); stroke.strokeWidth = 3f * dp
        canvas.drawCircle(cx, cy, r, stroke)
        // title
        text.textSize = 38f * dp
        text.style = Paint.Style.STROKE; text.strokeWidth = 8f * dp; text.color = 0xFF3A1A08.toInt()
        canvas.drawText("LUO WORLD BOX", cx, cy - r - 36 * dp, text)
        text.style = Paint.Style.FILL; text.color = 0xFFFFA630.toInt()
        canvas.drawText("LUO WORLD BOX", cx, cy - r - 36 * dp, text)
        text.textSize = 16f * dp; text.color = 0xFFFFB347.toInt()
        canvas.drawText("•$loadingText", cx, cy + r + 44 * dp, text)
        text.textSize = 13f * dp; text.color = 0xFFD8E8FF.toInt()
        canvas.drawText("Make sure to save your world before you go!", cx, cy + r + 72 * dp, text)
    }
}
