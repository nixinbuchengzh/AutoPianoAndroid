package com.autopiano.service

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.autopiano.engine.*
import com.autopiano.ui.MainActivity
import kotlinx.coroutines.*
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var generation = 0
    private var destroying = false

    private var panelView: View? = null
    private lateinit var panelLp: WindowManager.LayoutParams
    private var panelAttached = false
    private var ballView: TextView? = null
    private lateinit var ballLp: WindowManager.LayoutParams
    private var ballAttached = false
    private var pickerView: View? = null
    private var calibrationOverlay: CalibrationOverlay? = null
    private var displayManager: DisplayManager? = null
    private data class Screen(val width: Int, val height: Int, val rotation: Int)
    private lateinit var screen: Screen
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refreshScreen()
        }
    }

    private var presetIdx = 0
    private var preset = Keymaps.PRESETS[0]
    private lateinit var calibration: CalibrationProfile
    private var score: Score? = null
    private var rawFileBytes: ByteArray? = null
    private var rawFileName = ""
    private var isMidiFile = false
    private var player: PianoPlayer? = null
    private var looping = false
    private var sustain = false
    private var melodyOnly = false
    private val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
    private var speedIdx = 2
    private var statusTv: TextView? = null
    private var status = "点击曲库选择已导入的曲谱"
    private var timeTv: TextView? = null
    private var playBtn: Button? = null
    private var modeBtn: Button? = null
    private var speedBtn: Button? = null
    private var loopBtn: Button? = null
    private var seekBar: SeekBar? = null
    private var seekingByUser = false
    private var seekPosSec = 0.0
    private var durationSec = 0.0
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        screen = readScreen()
        calibration = loadCalibration()
        buildPanel()
        buildBall()
        showBall()
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::wm.isInitialized) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_PLAY -> togglePlay()
        }
        intent?.getStringExtra(EXTRA_LIBRARY_ID)?.let { loadLibraryScore(it) }
        intent?.getStringExtra(EXTRA_URI)?.let {
            loadFromUri(Uri.parse(it), intent.getStringExtra(EXTRA_NAME) ?: "score")
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::wm.isInitialized) mainHandler.post { if (!destroying) refreshScreen() }
    }

    override fun onDestroy() {
        destroying = true
        generation++
        serviceScope.cancel()
        player?.dispose()
        displayManager?.unregisterDisplayListener(displayListener)
        calibrationOverlay?.dismiss(false)
        removeWindow(pickerView)
        if (panelAttached) removeWindow(panelView)
        if (ballAttached) removeWindow(ballView)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun readScreen(): Screen {
        val dm = DisplayMetrics()
        val display = wm.defaultDisplay
        display.getRealMetrics(dm)
        return Screen(dm.widthPixels, dm.heightPixels, display.rotation)
    }

    private fun loadCalibration() = CalibrationProfile.load(this, preset, screen.width, screen.height, screen.rotation)

    private fun refreshScreen() {
        if (destroying || !::screen.isInitialized) return
        val next = readScreen()
        if (next == screen) return
        // 不让后台播放器继续向旧的竖屏坐标注入手势。
        player?.pause()
        calibrationOverlay?.dismiss(false)
        calibrationOverlay = null
        closePicker(false)
        screen = next
        calibration = loadCalibration()
        player?.calibration = calibration
        setStatus("屏幕方向已变化，已暂停；请确认当前方向的校准点位")
        resizePanel()
        clampWindow(ballLp, dp(48), dp(48))
        if (ballAttached) wm.updateViewLayout(ballView, ballLp)
        if (!panelAttached) showBall()
    }

    private fun startForegroundNotification() {
        val channelId = "autopiano_overlay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "自动弹琴悬浮球", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val play = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_PLAY), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("自动弹琴悬浮球已开启")
            .setContentText("点击悬浮球展开；收起不停止弹奏")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "播放 / 暂停", play).build())
            .addAction(Notification.Action.Builder(null, "退出", stop).build())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(101, notif)
    }

    private fun background(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(textValue: String, size: Float = 12f) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(Color.LTGRAY)
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        textSize = 11f
        isAllCaps = false
        minimumWidth = 0
        minimumHeight = 0
        minWidth = 0
        minHeight = 0
        setTextColor(Color.WHITE)
        backgroundTintList = null
        background = background(Color.rgb(48, 53, 65), 8)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { action() }
    }

    private fun row(parent: LinearLayout, vararg items: Pair<View, Float>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (view, weight) ->
            row.addView(view, LinearLayout.LayoutParams(0, dp(36), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
        }
        parent.addView(row)
    }

    private fun buildPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = background(Color.argb(245, 21, 22, 27))
            // 空白处也由面板消费触控，不能穿透后误点游戏。
            isClickable = true
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = label("自动弹琴 · 拖动", 13f).apply { setTextColor(Color.rgb(90, 169, 255)) }
        header.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(button("收起") { collapsePanel() }, LinearLayout.LayoutParams(dp(48), dp(32)))
        header.addView(button("退出") { stopSelf() }, LinearLayout.LayoutParams(dp(48), dp(32)).apply { leftMargin = dp(4) })
        root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTv = label(status).apply { maxLines = 3 }
        body.addView(statusTv)
        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && durationSec > 0) {
                        seekPosSec = durationSec * p / 1000.0
                        updateTimeLabel()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { seekingByUser = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekingByUser = false
                    seekToDisplay(seekPosSec)
                }
            })
        }
        body.addView(seekBar, LinearLayout.LayoutParams(-1, dp(28)))
        timeTv = label("00:00 / 00:00", 11f).apply { gravity = Gravity.CENTER }
        body.addView(timeTv)
        playBtn = button("开始") { togglePlay() }
        row(body, button("-10s") { seekBy(-10.0) } to 1f, playBtn!! to 1.2f,
            button("停止") { stopPlay() } to 1f, button("+10s") { seekBy(10.0) } to 1f)
        modeBtn = button(preset.displayName) { cyclePreset() }
        speedBtn = button("100%") { cycleSpeed() }
        row(body, modeBtn!! to 1.7f, speedBtn!! to 0.8f, button("校准") { openCalibration() } to 1f)
        loopBtn = button("循环:关") { toggleLoop() }
        row(body, button("本地曲库") { openScorePicker() } to 1.4f, loopBtn!! to 1f)
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.addView(CheckBox(this).apply {
            text = "长音补奏"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked ->
                sustain = checked
                player?.let { it.sustain = checked; it.rebuild() }
            }
        })
        options.addView(CheckBox(this).apply {
            text = "只弹旋律"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> melodyOnly = checked; reparseLoadedScore() }
        })
        body.addView(options)
        body.addView(label("开始/继续后自动收起；双击悬浮球暂停/继续", 10f))
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panelView = root
        panelLp = overlayParams(dp(300), panelHeight()).apply { x = dp(12); y = dp(36) }
        installDrag(title, root, panelLp, { panelAttached })
        resizePanel()
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }

    private fun panelHeight() = dp(300).coerceAtMost((screen.height - dp(48)).coerceAtLeast(dp(96)))

    private fun resizePanel() {
        if (!::panelLp.isInitialized) return
        panelLp.width = dp(300).coerceAtMost(screen.width - dp(24))
        panelLp.height = panelHeight()
        clampWindow(panelLp, panelLp.width, panelLp.height)
        if (panelAttached) wm.updateViewLayout(panelView, panelLp)
    }

    private fun buildBall() {
        val ball = label("♪", 24f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(Color.rgb(42, 103, 172), 24)
            contentDescription = "自动弹琴悬浮球，点击展开，拖动移动，长按暂停或继续"
            elevation = dp(4).toFloat()
        }
        ballView = ball
        ballLp = overlayParams(dp(48), dp(48)).apply { x = dp(8); y = dp(64) }
        // 单击展开，长按暂停/继续。长按不会因为展开面板而挡住游戏。
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (panelAttached) collapsePanel() else expandPanel()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { togglePlay(); return true }
            override fun onLongPress(e: MotionEvent) { togglePlay() }
        })
        installDrag(ball, ball, ballLp, { ballAttached }, detector, snap = true)
    }

    private fun installDrag(handle: View, window: View, lp: WindowManager.LayoutParams,
                            attached: () -> Boolean, detector: GestureDetector? = null, snap: Boolean = false) {
        var rawX = 0f
        var rawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = ev.rawX; rawY = ev.rawY; startX = lp.x; startY = lp.y; moved = false
                    detector?.onTouchEvent(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - rawX
                    val dy = ev.rawY - rawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        detector?.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (moved && attached()) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        clampWindow(lp, lp.width, lp.height)
                        wm.updateViewLayout(window, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { detector?.onTouchEvent(ev); handle.performClick() }
                    else if (snap && attached()) {
                        lp.x = if (lp.x + lp.width / 2 < screen.width / 2) dp(8) else screen.width - lp.width - dp(8)
                        clampWindow(lp, lp.width, lp.height)
                        wm.updateViewLayout(window, lp)
                    }
                }
                MotionEvent.ACTION_CANCEL -> detector?.onTouchEvent(ev)
            }
            true
        }
    }

    private fun clampWindow(lp: WindowManager.LayoutParams, width: Int, height: Int) {
        lp.x = lp.x.coerceIn(0, (screen.width - width - dp(8)).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screen.height - height - dp(32)).coerceAtLeast(0))
    }

    private fun removeWindow(view: View?) {
        if (view == null || !::wm.isInitialized) return
        try { wm.removeView(view) } catch (_: IllegalArgumentException) {}
    }

    private fun showBall() {
        if (destroying || calibrationOverlay != null || pickerView != null || ballAttached) return
        clampWindow(ballLp, ballLp.width, ballLp.height)
        wm.addView(ballView, ballLp)
        ballAttached = true
        updateBall()
    }

    private fun expandPanel() {
        if (destroying || calibrationOverlay != null || pickerView != null || panelAttached) return
        refreshScreen()
        if (player?.state == "playing") {
            player?.pause()
            setStatus("已暂停，可调整参数；继续后会自动收起")
            // 给已提交的短手势留出结束时间，避免它落到刚展开的按钮上。
            mainHandler.postDelayed({ if (!destroying && player?.state != "playing") expandPanel() }, 100L)
            return
        }
        if (ballAttached) { removeWindow(ballView); ballAttached = false }
        // 从悬浮球旁展开，小球仍可点击收起，且始终位于面板之上。
        panelLp.x = if (ballLp.x < screen.width / 2) ballLp.x + dp(56)
            else ballLp.x - panelLp.width - dp(8)
        panelLp.y = ballLp.y
        resizePanel()
        wm.addView(panelView, panelLp)
        panelAttached = true
        showBall()
    }

    private fun collapsePanel() {
        if (panelAttached) { removeWindow(panelView); panelAttached = false }
        showBall() // 这里只改变窗口，不停止播放器。
    }

    private fun updateBall() {
        val playing = player?.state == "playing"
        ballView?.text = if (playing) "Ⅱ" else "♪"
        ballView?.background = background(if (playing) Color.rgb(29, 133, 110) else Color.rgb(42, 103, 172), 24)
    }

    /** 播放前自动把小球移出整个琴键区域，而不仅仅是移出首尾点。 */
    private fun moveBallAwayFromKeys(): Boolean {
        val margin = dp(24)
        val keys = Rect((calibration.left - margin).toInt(), (calibration.top - margin).toInt(),
            (calibration.right + margin).toInt(), (calibration.bottom + margin).toInt())
        val size = dp(48)
        val candidates = listOf(
            ballLp.x to ballLp.y, dp(8) to dp(48),
            (screen.width - size - dp(16)) to dp(48),
            dp(8) to (screen.height - size - dp(48)),
            (screen.width - size - dp(16)) to (screen.height - size - dp(48))
        )
        val safe = candidates.firstOrNull { (x, y) ->
            // 为系统栏 / 刘海的窗口原点差异留出余量。
            !Rect.intersects(keys, Rect(x - margin, y - margin, x + size + margin * 2, y + size + margin * 2))
        } ?: return false
        ballLp.x = safe.first; ballLp.y = safe.second
        clampWindow(ballLp, size, size)
        if (ballAttached) wm.updateViewLayout(ballView, ballLp)
        return true
    }

    private fun windowContains(view: View?, pt: PointF): Boolean {
        if (view == null || !view.isAttachedToWindow || !view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val padding = dp(2)
        return pt.x >= location[0] - padding && pt.x <= location[0] + view.width + padding &&
            pt.y >= location[1] - padding && pt.y <= location[1] + view.height + padding
    }

    private fun dispatchSafely(token: Int, points: List<PointF>, holdMs: Long) {
        // 窗口只能在主线程检查；阻止延迟的旧曲谱回调和误点控制界面。
        mainHandler.post {
            if (destroying || token != generation || player?.state != "playing") return@post
            if (readScreen() != screen) { refreshScreen(); return@post }
            if (calibrationOverlay != null || pickerView != null ||
                points.any { windowContains(panelView, it) || windowContains(ballView, it) }) {
                player?.pause()
                setStatus("已暂停：控制窗口挡住琴键，请移动悬浮球后继续")
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
                return@post
            }
            val accessibility = AutoPianoAccessibilityService.instance
            if (accessibility == null) {
                player?.pause()
                setStatus("无障碍服务已断开，请重新开启")
            } else accessibility.tapChord(points, holdMs)
        }
    }

    private fun loadLibraryScore(id: String) {
        beginLoad()
        loadJob = serviceScope.launch {
            try {
                val (entry, bytes) = withContext(Dispatchers.IO) { ScoreLibrary.read(this@OverlayService, id) }
                rawFileName = entry.name; rawFileBytes = bytes; isMidiFile = entry.isMidi
                loadJob = null // 读取已完成，解析不得取消正在执行的自身协程。
                reparseLoadedScore()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { setStatus("加载失败: ${e.message}") }
        }
    }

    private fun loadFromUri(uri: Uri, name: String) {
        beginLoad()
        loadJob = serviceScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val data = stream.readBytesLimited()
                        require(data.isNotEmpty()) { "文件为空" }
                        data
                    } ?: error("无法读取文件")
                }
                rawFileName = name; rawFileBytes = bytes
                isMidiFile = bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "MThd"
                loadJob = null
                reparseLoadedScore()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { setStatus("加载失败: ${e.message}") }
        }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(out.size() + count <= 10 * 1024 * 1024) { "曲谱不能超过 10 MB" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun beginLoad() {
        loadJob?.cancel()
        generation++
        player?.dispose()
        player = null
        score = null
        rawFileBytes = null
        durationSec = 0.0; seekPosSec = 0.0
        updateTimeLabel()
        updateBall()
        playBtn?.text = "开始"
        setStatus("正在加载曲谱…")
    }

    private fun reparseLoadedScore() {
        val bytes = rawFileBytes ?: return
        loadJob?.cancel()
        generation++
        val token = generation
        player?.dispose()
        player = null
        score = null
        updateBall()
        playBtn?.text = "开始"
        setStatus("正在解析 $rawFileName…")
        val targetPreset = preset
        val onlyMelody = melodyOnly
        val midi = isMidiFile
        loadJob = serviceScope.launch {
            try {
                val sc = withContext(Dispatchers.Default) {
                    if (midi) MidiParser.parse(bytes, targetPreset, onlyMelody)
                    else JianpuParser.parse(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"), targetPreset)
                }
                require(sc.notes.isNotEmpty()) { "曲谱中没有可弹奏的音符" }
                if (token != generation) return@launch
                score = sc
                player = PianoPlayer(sc, preset, calibration, sc.bpm * speeds[speedIdx], looping, sustain,
                    dispatchChord = { pts, holdMs -> dispatchSafely(token, pts, holdMs) },
                    onProgress = { cur, tot -> mainHandler.post {
                        if (token == generation && !seekingByUser) {
                            seekPosSec = cur * speeds[speedIdx]
                            durationSec = tot * speeds[speedIdx]
                            updateTimeLabel()
                        }
                    } },
                    onState = { state -> mainHandler.post {
                        if (token == generation) {
                            playBtn?.text = when (state) { "playing" -> "暂停"; "paused" -> "继续"; else -> "开始" }
                            updateBall()
                        }
                    } })
                durationSec = sc.lengthBeats * 60.0 / sc.bpm
                seekPosSec = 0.0
                updateTimeLabel()
                setStatus("$rawFileName · ${sc.notes.size} 音")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) {
                    durationSec = 0.0; seekPosSec = 0.0
                    updateTimeLabel()
                    setStatus("解析失败: ${e.message}")
                }
            }
        }
    }

    private fun togglePlay() {
        refreshScreen()
        val p = player ?: run { setStatus("请先从本地曲库选择曲谱"); return }
        if (p.state == "playing") { p.pause(); return }
        if (AutoPianoAccessibilityService.instance == null) {
            setStatus("请先在系统设置开启无障碍服务")
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
            return
        }
        if (calibrationOverlay != null || pickerView != null) return
        if (!moveBallAwayFromKeys()) {
            setStatus("琴键区域过大，无法避让悬浮球，请重新校准")
            return
        }
        collapsePanel()
        if (p.state == "paused") p.resume()
        else {
            // 完成 / 跳到末尾后按开始，应从头重播。
            if (seekPosSec >= durationSec) seekPosSec = 0.0
            p.start(seekPosSec / speeds[speedIdx])
        }
        updateBall()
    }

    private fun stopPlay() { player?.stop(); seekPosSec = 0.0; updateTimeLabel(); updateBall() }
    private fun seekBy(delta: Double) {
        if (durationSec > 0) seekToDisplay((seekPosSec + delta).coerceIn(0.0, durationSec))
    }
    private fun seekToDisplay(sec: Double) {
        val p = player
        val ratio = speeds[speedIdx]
        seekPosSec = if (p != null && (p.state == "playing" || p.state == "paused")) p.seek(sec / ratio) * ratio else sec
        updateTimeLabel()
    }
    private fun cyclePreset() {
        presetIdx = (presetIdx + 1) % Keymaps.PRESETS.size
        preset = Keymaps.PRESETS[presetIdx]
        refreshScreen()
        calibration = loadCalibration()
        player?.calibration = calibration
        modeBtn?.text = preset.displayName
        reparseLoadedScore()
    }
    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        speedBtn?.text = "${(speeds[speedIdx] * 100).toInt()}%"
        score?.let { player?.setTempo(it.bpm * speeds[speedIdx]) }
    }
    private fun toggleLoop() {
        looping = !looping
        player?.loop = looping
        loopBtn?.text = if (looping) "循环:开" else "循环:关"
    }

    private fun openCalibration() {
        if (calibrationOverlay != null) return
        refreshScreen()
        player?.pause()
        calibration = loadCalibration() // 每次打开都读取当前方向，不依赖服务启动方向。
        if (panelAttached) { removeWindow(panelView); panelAttached = false }
        if (ballAttached) { removeWindow(ballView); ballAttached = false }
        calibrationOverlay = CalibrationOverlay(this, wm, preset, calibration,
            onSaved = { saved ->
                calibration = saved
                player?.calibration = saved
                setStatus("${preset.displayName} 当前方向坐标已保存")
            },
            onDismissed = {
                calibrationOverlay = null
                showBall()
            }).also { it.show() }
    }

    private fun openScorePicker() {
        if (pickerView != null) return
        player?.pause()
        if (panelAttached) { removeWindow(panelView); panelAttached = false }
        if (ballAttached) { removeWindow(ballView); ballAttached = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = background(Color.argb(250, 21, 22, 27))
            isClickable = true
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("本地曲库", 14f), LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(button("返回") { closePicker(true) }, LinearLayout.LayoutParams(dp(56), dp(32)))
        root.addView(header)
        val search = EditText(this).apply {
            hint = "搜索已导入的曲名"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 12f
            isSingleLine = true
            setSelectAllOnFocus(false)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(44)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("提前在应用中导入 MIDI / TXT；选曲不会切回应用", 10f))
        var entries = emptyList<LibraryScore>()
        fun render(query: String) {
            list.removeAllViews()
            val shown = entries.filter { it.name.contains(query, true) }
            if (shown.isEmpty()) list.addView(label(if (entries.isEmpty()) "曲库为空，请先在应用中导入" else "没有匹配的曲谱").apply { setPadding(0, dp(16), 0, dp(16)) })
            shown.forEach { entry ->
                list.addView(button("${entry.name}\n${if (entry.isMidi) "MIDI" else "TXT"}") {
                    closePicker(true)
                    loadLibraryScore(entry.id)
                }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(6) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        pickerView = root
        val lp = overlayParams(panelLp.width, panelHeight()).apply {
            x = panelLp.x; y = panelLp.y
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        clampWindow(lp, lp.width, lp.height)
        wm.addView(root, lp)
        root.requestFocus()
        serviceScope.launch {
            try {
                entries = withContext(Dispatchers.IO) { ScoreLibrary.list(this@OverlayService) }
                if (pickerView === root) render(search.text.toString())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (pickerView === root) list.addView(label("读取曲库失败: ${e.message}"))
            }
        }
    }

    private fun closePicker(showPanel: Boolean) {
        val view = pickerView ?: return
        // 显式关闭输入法，避免搜曲后键盘留在游戏上。
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        removeWindow(view)
        pickerView = null
        if (showPanel) expandPanel()
    }
    private fun setStatus(message: String) { status = message; statusTv?.text = message }
    private fun updateTimeLabel() {
        fun fmt(s: Double): String {
            val sec = s.toInt().coerceAtLeast(0)
            return "%02d:%02d".format(sec / 60, sec % 60)
        }
        timeTv?.text = "${fmt(seekPosSec)} / ${fmt(durationSec)}"
        seekBar?.progress = if (durationSec > 0) ((seekPosSec / durationSec) * 1000).toInt().coerceIn(0, 1000) else 0
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_LIBRARY_ID = "extra_library_id"
        private const val ACTION_STOP = "com.autopiano.STOP"
        private const val ACTION_TOGGLE_PLAY = "com.autopiano.TOGGLE_PLAY"
    }
}
