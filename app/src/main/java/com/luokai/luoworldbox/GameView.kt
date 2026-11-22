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
import com.luokai.luoworldbox.core.Act
import com.luokai.luoworldbox.core.Creature
import com.luokai.luoworldbox.core.Fx
import com.luokai.luoworldbox.core.Job
import com.luokai.luoworldbox.core.Kind
import com.luokai.luoworldbox.core.Report
import com.luokai.luoworldbox.core.SaveStore
import com.luokai.luoworldbox.core.TileRender
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
    @Volatile var showMini = true
    @Volatile var sfx: Sfx? = null
    private val sprites = SpriteBank()
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
    private val MODE_MINI = 5
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
    private var rowBuf = IntArray(0)
    private val fx = Effects(resources.displayMetrics.density)
    @Volatile private var frameDt = 0.016f
    private var oomRetried = false
    private val dst = RectF()
    private val rect = RectF()
    private val oval = RectF()
    private val bmpPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val mapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38000000 }
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
    /** Paints the whole map into a bitmap. Runs on a loading thread so the game never freezes on huge worlds. */
    private fun buildBitmap(w: World): Bitmap {
        val bmp = Bitmap.createBitmap(w.w, w.h, Bitmap.Config.ARGB_8888)
        val chunk = max(1, (1 shl 16) / w.w) + 1
        val buf = IntArray(w.w * chunk)
        var y = 0
        while (y < w.h) {
            val n = min(chunk, w.h - y)
            for (r in 0 until n) {
                val yy = y + r
                for (x in 0 until w.w) buf[r * w.w + x] = TileRender.color(w, yy * w.w + x, x, yy, 0)
            }
            bmp.setPixels(buf, 0, w.w, 0, y, w.w, n)
            y += n
        }
        return bmp
    }

    private fun install(w: World, bmp: Bitmap) {
        cmds.add(Runnable {
            bitmap?.recycle()
            bitmap = bmp
            bmpWorld = w
            w.clearDirty()
            world = w
            needFit = true
            loading = false
        })
    }

    fun newWorld(cfg: WorldConfig, seed: Long = System.currentTimeMillis()) {
        loading = true
        loadingText = "Splitting atoms..."
        Thread {
            val c = cfg.copyConfig()
            var generated: World? = null
            var painted: Bitmap? = null
            while (painted == null) {
                try {
                    generated = WorldGen.generate(c, seed) { loadingText = it }
                    loadingText = "Painting the world..."
                    painted = buildBitmap(generated)
                } catch (e: OutOfMemoryError) {
                    generated = null
                    if (c.sizeIndex == 0) throw e
                    c.sizeIndex -= 1
                    loadingText = "Not enough memory, trying a smaller world..."
                }
            }
            install(generated!!, painted)
        }.start()
    }

    fun loadWorld(w: World) {
        loading = true
        loadingText = "Painting the world..."
        Thread {
            try {
                install(w, buildBitmap(w))
            } catch (e: OutOfMemoryError) {
                loadingText = "This device does not have enough memory for that world."
            }
        }.start()
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
            frameDt = dt
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
                    for (snd in w.drainSounds()) sfx?.play(snd)
                    for (t in w.drainToasts()) {
                        toasts.add(t); toastLife.add(6f)
                        if (toasts.size > 5) { toasts.removeAt(0); toastLife.removeAt(0) }
                    }
                    autosave += dt
                    if (autosave > 180f) {
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
            fx.onPower(p.id, x, y, r)
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
                val mr = RectF(); miniRect(mr)
                val inMini = showMini && mr.contains(ev.x, ev.y)
                if (inMini) jumpToMini(ev.x, ev.y, mr)
                val inMenu = rect.contains(ev.x, ev.y)
                val inBrush = RectF().also { brushRect(it) }.contains(ev.x, ev.y)
                val p = selected
                mode = when {
                    inMini -> MODE_MINI
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
                    MODE_MINI -> { val mr = RectF(); miniRect(mr); jumpToMini(ev.x, ev.y, mr) }
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

    private fun miniRect(r: RectF) {
        val sz = 84f * dp
        r.set(width - pad - sz, 28f * dp + 46f * dp + 8f * dp, width - pad, 28f * dp + 46f * dp + 8f * dp + sz)
    }

    private fun jumpToMini(x: Float, y: Float, r: RectF) {
        val w = world ?: return
        camX = ((x - r.left) / r.width() * w.w).coerceIn(0f, w.w.toFloat())
        camY = ((y - r.top) / r.height() * w.h).coerceIn(0f, w.h.toFloat())
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
    private fun refreshBitmap(w: World) {
        if (bmpWorld !== w || bitmap == null) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w.w, w.h, Bitmap.Config.ARGB_8888)
            bmpWorld = w
            w.markAllDirty()
        }
        if (w.dirtyMax >= w.dirtyMin) {
            val y0 = max(0, w.dirtyMin)
            val y1 = min(w.h - 1, w.dirtyMax)
            // paint in chunks of rows so a 5 million tile world never needs a second full-size array
            val chunk = max(1, 1 shl 16) / w.w + 1
            if (rowBuf.size < w.w * chunk) rowBuf = IntArray(w.w * chunk)
            var y = y0
            while (y <= y1) {
                val n = min(chunk, y1 - y + 1)
                for (r in 0 until n) {
                    val yy = y + r
                    for (x in 0 until w.w) rowBuf[r * w.w + x] = TileRender.color(w, yy * w.w + x, x, yy, frame)
                }
                bitmap!!.setPixels(rowBuf, 0, w.w, 0, y, w.w, n)
                y += n
            }
            w.clearDirty()
        }
    }

    private fun renderFrame(canvas: Canvas) {
        frame++
        canvas.drawColor(0xFF0A0F1E.toInt())
        val w = world
        if (loading || w == null) { drawLoading(canvas); return }
        if (needFit) {
            val v0 = w.villages.firstOrNull()
            if (v0 != null) {
                camX = v0.cx; camY = v0.cy
                zoom = max(28f, minZoom())
            } else {
                camX = w.w / 2f; camY = w.h / 2f
                zoom = min(width / w.w.toFloat(), (height - barH) / w.h.toFloat()) * 0.95f
                zoom = max(zoom, minZoom())
            }
            needFit = false
        }
        try {
            refreshBitmap(w)
        } catch (e: OutOfMemoryError) {
            if (!oomRetried) {
                oomRetried = true
                loading = true
                world = null
                bitmap = null
                bmpWorld = null
                ui.post { host.showInfo("Not enough memory", "This device could not hold a world that large. A smaller world will be created.") }
                newWorld(WorldConfig(sizeIndex = 7))
            }
            return
        }
        val s = zoom
        val shakeAmt = fx.shake
        val shx = if (shakeAmt > 0.3f) (Math.random().toFloat() - 0.5f) * shakeAmt else 0f
        val shy = if (shakeAmt > 0.3f) (Math.random().toFloat() - 0.5f) * shakeAmt else 0f
        val ox = width / 2f - camX * s + shx
        val oy = height / 2f - camY * s + shy
        dst.set(ox, oy, ox + w.w * s, oy + w.h * s)
        mapPaint.isFilterBitmap = s < 1.2f
        canvas.drawBitmap(bitmap!!, null, dst, mapPaint)
        val effDt = if (paused || appPaused) 0f else frameDt
        fx.begin(width, height, s, camX - width / 2f / s, camY - height / 2f / s, camX + width / 2f / s, camY + height / 2f / s)
        fx.update(w, effDt)

        stroke.color = 0x66FFFFFF; stroke.strokeWidth = 1.5f * dp
        canvas.drawRect(dst, stroke)

        val vx0 = max(0, ((-ox) / s).toInt() - 1)
        val vy0 = max(0, ((-oy) / s).toInt() - 1)
        val vx1 = min(w.w - 1, ((width - ox) / s).toInt() + 1)
        val vy1 = min(w.h - 1, ((height - oy) / s).toInt() + 1)

        // kingdom borders
        if (showBorders && s >= 1.0f) drawVillages(canvas, w, s, ox, oy)

        // trees
        if (s >= 5f) {
            val ts = s * 1.55f
            for (y in vy0..vy1) for (x in vx0..vx1) {
                val i = y * w.w + x
                if (w.tiles[i].toInt() == Tile.FOREST && w.fire[i].toInt() == 0) {
                    val kind = when (TileRender.hash(x, y) and 3) { 0 -> 0; 3 -> 2; else -> 1 }
                    val cx = ox + (x + 0.5f) * s
                    val bottom = oy + (y + 1f) * s
                    dst.set(cx - ts / 2, bottom - ts, cx + ts / 2, bottom)
                    canvas.drawBitmap(sprites.tree(kind), null, dst, bmpPaint)
                }
            }
        }

        // houses
        if (s >= 2.5f) {
            val hs = s * 1.6f
            for (v in w.villages) {
                val k = w.kingdomById(v.kingdomId)
                val roof = k?.color ?: 0xFFCC4444.toInt()
                val tier = if (v.houses.size < 3) 0 else if (v.houses.size < 8) 1 else 2
                val spr = sprites.house(tier, roof)
                if (v.site >= 0) {
                    val sx = v.site % w.w
                    val sy = v.site / w.w
                    if (sx in vx0..vx1 && sy in vy0..vy1) {
                        val cx = ox + (sx + 0.5f) * s
                        val bottom = oy + (sy + 1f) * s
                        dst.set(cx - hs / 2, bottom - hs, cx + hs / 2, bottom)
                        canvas.drawBitmap(sprites.site(v.siteProgress), null, dst, bmpPaint)
                    }
                }
                for (hi in v.houses) {
                    val hx = hi % w.w
                    val hy = hi / w.w
                    if (hx < vx0 || hx > vx1 || hy < vy0 || hy > vy1) continue
                    val cx = ox + (hx + 0.5f) * s
                    val bottom = oy + (hy + 1f) * s
                    dst.set(cx - hs / 2, bottom - hs, cx + hs / 2, bottom)
                    canvas.drawBitmap(spr, null, dst, bmpPaint)
                }
            }
        }

        drawCreatures(canvas, w, s, ox, oy)
        fx.drawShapes(canvas, w, ox, oy)
        drawDayNight(canvas, w, s, ox, oy)
        fx.drawParticles(canvas, ox, oy)
        fx.drawScreen(canvas)
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
        val size = max(s * 1.25f, 12f * dp).coerceAtMost(110f * dp)
        for (c in w.creatures) {
            if (!c.alive || c.inside) continue
            val cx = ox + c.x * s
            val cy = oy + c.y * s
            if (cx < -80 || cx > width + 80 || cy < -80 || cy > height + 80) continue
            val sp = c.sp
            if (s < 2.5f) {
                fill.color = sp.color
                canvas.drawRect(cx - 1.5f * dp, cy - 1.5f * dp, cx + 1.5f * dp, cy + 1.5f * dp, fill)
                continue
            }
            val moving = c.hasTarget || c.chase != null
            val speedUp = if (c.fleeing || c.chase != null) 14 else 9
            val frame = if (moving) ((w.time * speedUp).toInt() + c.id) and 3 else 0
            val breathe = if (!moving) sin(w.time * 2.0 + c.id).toFloat() * 0.012f else 0f
            val bob = if (moving && (frame == 1 || frame == 3)) -size * 0.03f else 0f
            val kid = if (!c.isAdult && sp.kind != Kind.MONSTER) 0.68f else 1f
            val sz = size * kid * sp.scale
            // lunge when attacking
            var lunge = 0f
            if (c.chase != null && c.atkCd > 0.5f) lunge = (c.atkCd - 0.5f) * sz * 0.5f * (if (c.faceRight) 1f else -1f)
            val flyLift = if (sp.flies) -sz * 0.35f - sin(w.time * 4.0 + c.id).toFloat() * sz * 0.05f else 0f
            val px = cx + lunge
            if (s >= 3f) {
                val shw = sz * 0.34f * (if (sp.flies) 0.7f else 1f)
                oval.set(cx - shw, cy - sz * 0.03f, cx + shw, cy + sz * 0.10f)
                canvas.drawOval(oval, shadow)
            }
            dst.set(px - sz / 2, cy - sz * 0.88f + bob + flyLift - sz * breathe, px + sz / 2, cy + sz * 0.12f + bob + flyLift)
            canvas.drawBitmap(sprites.creature(sp, frame, c.faceRight, if (sp.kind == Kind.MONSTER) 0 else c.id), null, dst, bmpPaint)
            if (sp.kind == Kind.SAPIENT && s >= 5f) { drawCarry(canvas, c, px, cy + flyLift, sz); drawTool(canvas, w, c, px, cy + flyLift, sz) }
            if (sp.kind == Kind.SAPIENT) {
                val k = w.kingdomById(c.kingdomId)
                if (k != null) {
                    fill.color = k.color
                    canvas.drawRect(px - sz * 0.16f, dst.top - 3 * dp, px + sz * 0.16f, dst.top, fill)
                }
            }
            if (c.burning > 0f) {
                fill.color = 0xFFFF7A1A.toInt()
                canvas.drawRect(px - 2 * dp, dst.top - 6 * dp, px + 2 * dp, dst.top - 1 * dp, fill)
            }
            if (c.infected >= 0f) {
                fill.color = 0xFF66FF66.toInt()
                canvas.drawCircle(px, dst.top - 4 * dp, 2.5f * dp, fill)
            }
            if (s >= 5f && c.hp < sp.hp) {
                fill.color = 0xAA000000.toInt()
                canvas.drawRect(px - sz / 2, dst.bottom + 1, px + sz / 2, dst.bottom + 1 + 3 * dp, fill)
                fill.color = 0xFF3DDC55.toInt()
                canvas.drawRect(px - sz / 2, dst.bottom + 1, px - sz / 2 + sz * (c.hp / sp.hp).coerceIn(0f, 1f), dst.bottom + 1 + 3 * dp, fill)
            }
        }
    }

    private fun drawCarry(canvas: Canvas, c: Creature, cx: Float, cy: Float, sz: Float) {
        if (c.carry == 0) return
        val u = sz / 16f
        val dir = if (c.faceRight) 1f else -1f
        val bx = cx - dir * sz * 0.2f
        val by = cy - sz * 0.5f
        fill.color = when (c.carry) { 1 -> 0xFF8A5A2A.toInt(); 2 -> 0xFFE0C040.toInt(); 3 -> 0xFF8A9AAA.toInt(); else -> 0xFFC0443A.toInt() }
        canvas.drawRect(bx - 2.4f * u, by - 1.4f * u, bx + 2.4f * u, by + 1.4f * u, fill)
        fill.color = 0x55FFFFFF
        canvas.drawRect(bx - 2.4f * u, by - 1.4f * u, bx + 2.4f * u, by - 0.4f * u, fill)
    }

    /** Tool in the villager's hand: axe, hoe, pickaxe, hammer, spear or sword. It swings while working. */
    private fun drawTool(canvas: Canvas, w: World, c: Creature, cx: Float, cy: Float, sz: Float) {
        var kind = 0
        val ch = c.chase
        if (ch != null && c.isAdult) kind = if (c.job == Job.HUNT && ch.sp.kind == Kind.HERBIVORE) 5 else 6
        else if (c.act == Act.GOTO_WORK || c.act == Act.WORK) kind = c.job
        if (kind <= 0 || kind > 6) return
        val working = c.act == Act.WORK || (ch != null && c.atkCd > 0.2f)
        val ang = if (working) sin(w.time * 10.0 + c.id).toFloat() * 55f else -25f
        val u = sz / 16f
        val dir = if (c.faceRight) 1f else -1f
        canvas.save()
        canvas.translate(cx + dir * sz * 0.17f, cy - sz * 0.22f)
        canvas.scale(dir, 1f)
        canvas.rotate(ang)
        val wood = 0xFF6A4A2A.toInt()
        val steel = 0xFFC8CCD2.toInt()
        when (kind) {
            1 -> { fill.color = wood; canvas.drawRect(-0.5f * u, -7f * u, 0.5f * u, 1f * u, fill)
                   fill.color = steel; canvas.drawRect(0.5f * u, -7f * u, 3.5f * u, -3.8f * u, fill) }
            2 -> { fill.color = wood; canvas.drawRect(-0.5f * u, -7f * u, 0.5f * u, 1f * u, fill)
                   fill.color = steel; canvas.drawRect(-0.2f * u, -7.4f * u, 3.6f * u, -6f * u, fill) }
            3 -> { fill.color = wood; canvas.drawRect(-0.5f * u, -7f * u, 0.5f * u, 1f * u, fill)
                   fill.color = steel; canvas.drawRect(-3.2f * u, -7.6f * u, 3.2f * u, -6.2f * u, fill) }
            4 -> { fill.color = wood; canvas.drawRect(-0.5f * u, -6f * u, 0.5f * u, 1f * u, fill)
                   fill.color = 0xFF5A5E66.toInt(); canvas.drawRect(-2f * u, -8f * u, 2f * u, -5.4f * u, fill) }
            5 -> { fill.color = wood; canvas.drawRect(-0.4f * u, -9f * u, 0.4f * u, 2f * u, fill)
                   fill.color = steel; canvas.drawRect(-1f * u, -11.5f * u, 1f * u, -8.8f * u, fill) }
            else -> { fill.color = steel; canvas.drawRect(-0.7f * u, -9f * u, 0.7f * u, -2f * u, fill)
                      fill.color = 0xFFC9A227.toInt(); canvas.drawRect(-2.2f * u, -2.2f * u, 2.2f * u, -1.4f * u, fill)
                      fill.color = wood; canvas.drawRect(-0.5f * u, -1.4f * u, 0.5f * u, 1f * u, fill) }
        }
        canvas.restore()
    }

    /** Night tint, lit windows and chimney smoke for houses that have someone inside. */
    private fun drawDayNight(canvas: Canvas, w: World, s: Float, ox: Float, oy: Float) {
        val nl = w.nightLevel()
        if (nl > 0.02f) {
            fill.color = ((nl * 125).toInt() shl 24) or 0x0A1236
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        if (s < 4f) return
        val occ = HashSet<Int>()
        for (c in w.creatures) if (c.alive && c.inside && c.homeIdx >= 0) occ.add(c.homeIdx)
        if (occ.isEmpty()) return
        for (v in w.villages) for (hi in v.houses) {
            if (!occ.contains(hi)) continue
            val hx = hi % w.w
            val hy = hi / w.w
            val cx = ox + (hx + 0.5f) * s
            val bottom = oy + (hy + 1f) * s
            if (cx < -40 || cx > width + 40 || bottom < -60 || bottom > height + 80) continue
            if (nl > 0.1f) {
                fill.color = ((nl * 200).toInt() shl 24) or 0xFFD060
                canvas.drawRect(cx - s * 0.42f, bottom - s * 0.62f, cx - s * 0.22f, bottom - s * 0.42f, fill)
                canvas.drawRect(cx + s * 0.22f, bottom - s * 0.62f, cx + s * 0.42f, bottom - s * 0.42f, fill)
            }
            for (k in 0 until 3) {
                val t = ((w.time * 0.7 + k / 3.0 + hi * 0.37) % 1.0).toFloat()
                val px = cx + s * 0.32f + sin(t * 6f + hi).toFloat() * s * 0.08f
                val py = bottom - s * 1.0f - t * s * 1.2f
                fill.color = (((1f - t) * 150).toInt() shl 24) or 0xC8C8C8
                canvas.drawCircle(px, py, s * 0.09f * (0.7f + t), fill)
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

    private fun drawPowerIcon(canvas: Canvas, p: Power, cx: Float, cy: Float) {
        if (p.id.startsWith("spawn:")) {
            val sp = com.luokai.luoworldbox.core.Sp.byKey(p.id.substring(6))
            if (sp != null) {
                val sz = 40f * dp
                dst.set(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2)
                canvas.drawBitmap(sprites.creature(sp, 0, true, 0), null, dst, bmpPaint)
                return
            }
        }
        if (p.tile >= 0) {
            val half = 17f * dp
            val cell = half * 2 / 6
            val base = Tile.COLORS[p.tile]
            for (gy in 0 until 6) for (gx in 0 until 6) {
                val f = 0.86f + ((gx * 7 + gy * 13 + p.tile * 5) % 9) / 9f * 0.28f
                fill.color = TileRender.scale(base, f)
                canvas.drawRect(cx - half + gx * cell, cy - half + gy * cell, cx - half + (gx + 1) * cell + 0.5f, cy - half + (gy + 1) * cell + 0.5f, fill)
            }
            if (p.tile == Tile.FOREST) {
                val sz = 30f * dp
                dst.set(cx - sz / 2, cy - sz / 2 - 2 * dp, cx + sz / 2, cy + sz / 2 - 2 * dp)
                canvas.drawBitmap(sprites.tree(0), null, dst, bmpPaint)
            }
            stroke.color = 0xFF101410.toInt(); stroke.strokeWidth = 1.5f * dp
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, stroke)
            return
        }
        emoji(canvas, p.icon, cx, cy, 26 * dp)
    }

    private fun drawMinimap(canvas: Canvas, w: World) {
        miniRect(rect)
        val bmp = bitmap ?: return
        fill.color = 0xAA0A0F1E.toInt()
        canvas.drawRect(rect.left - 3 * dp, rect.top - 3 * dp, rect.right + 3 * dp, rect.bottom + 3 * dp, fill)
        canvas.drawBitmap(bmp, null, rect, bmpPaint)
        stroke.color = 0xCCFFFFFF.toInt(); stroke.strokeWidth = 1.5f * dp
        canvas.drawRect(rect, stroke)
        val vw = width / zoom / w.w * rect.width()
        val vh = height / zoom / w.h * rect.height()
        val vx = rect.left + (camX / w.w) * rect.width() - vw / 2
        val vy = rect.top + (camY / w.h) * rect.height() - vh / 2
        canvas.save()
        canvas.clipRect(rect)
        stroke.color = 0xFFFFD27A.toInt()
        canvas.drawRect(vx, vy, vx + vw, vy + vh, stroke)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas, w: World) {
        if (showMini) drawMinimap(canvas, w)
        if (frame % 30 == 1) {
            var a = 0; var b = 0
            for (c in w.creatures) if (c.alive) { if (c.sp.kind == Kind.SAPIENT) a++ else b++ }
            popCache = a; mobCache = b
        }
        // status
        val line1 = (if (w.nightLevel() > 0.5f) "🌙 " else "☀ ") + "${w.age.name}  •  Year ${w.year}  •  ${w.monthName}  •  ${w.moonsLeft} moons left"
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
            drawPowerIcon(canvas, list[i], x + btn / 2, y + btn / 2)
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
