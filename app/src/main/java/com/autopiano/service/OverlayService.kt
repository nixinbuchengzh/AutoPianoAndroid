package com.autopiano.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.autopiano.engine.*
import com.autopiano.ui.MainActivity

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panelView: View? = null
    private lateinit var panelLp: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var presetIdx = 0
    private var preset: KeymapPreset = Keymaps.PRESETS[0]
    private lateinit var calibration: CalibrationProfile

    private var score: Score? = null
    private var rawFileBytes: ByteArray? = null
    private var rawFileName: String = ""
    private var isMidiFile: Boolean = false

    private var player: PianoPlayer? = null
    private var looping = false
    private var sustain = false
    private var melodyOnly = false
    private val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
    private var speedIdx = 2

    private var statusTv: TextView? = null
    private var timeTv: TextView? = null
    private var playBtn: Button? = null
    private var modeBtn: Button? = null
    private var speedBtn: Button? = null
    private var loopBtn: Button? = null
    private var seekBar: SeekBar? = null
    private var seekingByUser = false
    private var seekPosSec = 0.0
    private var durationSec = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (sw, sh) = screenSize()
        calibration = CalibrationProfile.load(this, preset, sw, sh)
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_URI)?.let { uriStr ->
            val name = intent.getStringExtra(EXTRA_NAME) ?: "score"
            loadFromUri(Uri.parse(uriStr), name)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.stop()
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun startForegroundNotification() {
        val channelId = "autopiano_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "自动弹琴悬浮窗", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("自动弹琴运行中")
            .setContentText("悬浮控制面板已开启")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(101, notif)
    }

    private fun showPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 21, 22, 27))
                cornerRadius = 24f
            }
        }

        // 标题栏（可拖动）
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "♪ 自动弹琴 (拖动)"
            setTextColor(Color.parseColor("#5aa9ff"))
            textSize = 14f
        }
        val closeBtn = TextView(this).apply {
            text = "  ×  "
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setOnClickListener { stopSelf() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(closeBtn)
        root.addView(header)

        statusTv = TextView(this).apply {
            text = "请在主界面选择曲谱"
            setTextColor(Color.parseColor("#9aa0ae"))
            textSize = 12f
        }
        root.addView(statusTv)

        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && durationSec > 0) {
                        seekPosSec = durationSec * (p / 1000.0)
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
        root.addView(seekBar)

        timeTv = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        root.addView(timeTv)

        // 第一排按钮：-10s、播放、停止、+10s
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mkBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 11f
            setPadding(8, 4, 8, 4)
            setOnClickListener { onClick() }
        }
        row1.addView(mkBtn("-10s") { seekBy(-10.0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        playBtn = mkBtn("▶ 开始") { togglePlay() }
        row1.addView(playBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        row1.addView(mkBtn("■ 停") { stopPlay() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(mkBtn("+10s") { seekBy(10.0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)

        // 第二排按钮：模式切换、倍速、循环、校准
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeBtn = mkBtn(preset.displayName) { cyclePreset() }
        speedBtn = mkBtn("100%") { cycleSpeed() }
        loopBtn = mkBtn("循环:关") { toggleLoop() }
        val calBtn = mkBtn("校准点位") { openCalibration() }
        row2.addView(modeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        row2.addView(speedBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
        row2.addView(loopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(calBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
        root.addView(row2)

        // 第三排开关：长音补奏、只弹旋律、换曲
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sustainCb = CheckBox(this).apply {
            text = "长音补奏"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked ->
                sustain = checked
                player?.let {
                    it.sustain = checked
                    it.rebuild()
                }
            }
        }
        val melodyCb = CheckBox(this).apply {
            text = "只弹旋律"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked ->
                melodyOnly = checked
                reparseLoadedScore()
            }
        }
        val pickBtn = mkBtn("选曲") {
            val it = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(it)
        }
        row3.addView(sustainCb)
        row3.addView(melodyCb)
        row3.addView(pickBtn)
        root.addView(row3)

        panelLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        var dX = 0f
        var dY = 0f
        var startX = 0
        var startY = 0
        header.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = panelLp.x
                    startY = panelLp.y
                    dX = ev.rawX
                    dY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelLp.x = (startX + (ev.rawX - dX)).toInt()
                    panelLp.y = (startY + (ev.rawY - dY)).toInt()
                    wm.updateViewLayout(root, panelLp)
                    true
                }
                else -> false
            }
        }

        wm.addView(root, panelLp)
        panelView = root
    }

    private fun loadFromUri(uri: Uri, name: String) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            rawFileBytes = bytes
            rawFileName = name
            isMidiFile = name.endsWith(".mid", true) || name.endsWith(".midi", true)
            reparseLoadedScore()
        } catch (e: Exception) {
            statusTv?.text = "加载失败: ${e.message}"
        }
    }

    private fun reparseLoadedScore() {
        val bytes = rawFileBytes ?: return
        try {
            val sc = if (isMidiFile) {
                MidiParser.parse(bytes, preset, melodyOnly)
            } else {
                JianpuParser.parse(String(bytes, Charsets.UTF_8), preset)
            }
            score = sc
            player?.stop()
            player = PianoPlayer(
                score = sc,
                preset = preset,
                calibration = calibration,
                bpm = sc.bpm * speeds[speedIdx],
                loop = looping,
                sustain = sustain,
                dispatchChord = { pts, holdMs ->
                    AutoPianoAccessibilityService.instance?.tapChord(pts, holdMs)
                },
                onProgress = { cur, tot ->
                    mainHandler.post {
                        if (!seekingByUser) {
                            val ratio = speeds[speedIdx]
                            seekPosSec = cur * ratio
                            durationSec = tot * ratio
                            updateTimeLabel()
                        }
                    }
                },
                onState = { st ->
                    mainHandler.post {
                        playBtn?.text = when (st) {
                            "playing" -> "❚❚ 暂停"
                            "paused" -> "▶ 继续"
                            else -> "▶ 开始"
                        }
                    }
                }
            )
            durationSec = sc.lengthBeats * 60.0 / sc.bpm
            seekPosSec = 0.0
            updateTimeLabel()
            statusTv?.text = "$rawFileName · ${sc.notes.size} 音"
        } catch (e: Exception) {
            statusTv?.text = "解析失败: ${e.message}"
        }
    }

    private fun togglePlay() {
        if (AutoPianoAccessibilityService.instance == null) {
            statusTv?.text = "请先在系统设置开启无障碍服务！"
            return
        }
        val p = player ?: return
        when (p.state) {
            "playing" -> p.pause()
            "paused" -> p.resume()
            else -> p.start(seekPosSec / speeds[speedIdx])
        }
    }

    private fun stopPlay() {
        player?.stop()
        seekPosSec = 0.0
        updateTimeLabel()
    }

    private fun seekBy(deltaSec: Double) {
        if (durationSec <= 0) return
        seekToDisplay((seekPosSec + deltaSec).coerceIn(0.0, durationSec))
    }

    private fun seekToDisplay(displaySec: Double) {
        val p = player
        val ratio = speeds[speedIdx]
        if (p != null && (p.state == "playing" || p.state == "paused")) {
            seekPosSec = p.seek(displaySec / ratio) * ratio
        } else {
            seekPosSec = displaySec
        }
        updateTimeLabel()
    }

    private fun cyclePreset() {
        presetIdx = (presetIdx + 1) % Keymaps.PRESETS.size
        preset = Keymaps.PRESETS[presetIdx]
        val (sw, sh) = screenSize()
        calibration = CalibrationProfile.load(this, preset, sw, sh)
        modeBtn?.text = preset.displayName
        reparseLoadedScore()
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        val sp = speeds[speedIdx]
        speedBtn?.text = "${(sp * 100).toInt()}%"
        val sc = score ?: return
        player?.setTempo(sc.bpm * sp)
    }

    private fun toggleLoop() {
        looping = !looping
        player?.loop = looping
        loopBtn?.text = if (looping) "循环:开" else "循环:关"
    }

    private fun openCalibration() {
        CalibrationOverlay(this, wm, preset, calibration) { saved ->
            calibration = saved
            player?.calibration = saved
            statusTv?.text = "${preset.displayName} 坐标已保存"
        }.show()
    }

    private fun updateTimeLabel() {
        fun fmt(s: Double): String {
            val sec = s.toInt().coerceAtLeast(0)
            return "%02d:%02d".format(sec / 60, sec % 60)
        }
        timeTv?.text = "${fmt(seekPosSec)} / ${fmt(durationSec)}"
        if (durationSec > 0) {
            seekBar?.progress = ((seekPosSec / durationSec) * 1000.0).toInt().coerceIn(0, 1000)
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
    }
}
