package com.tweak.tuner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Overlay FPS + API grafis + GPU. Jalan sebagai foreground service; pengukuran dilakukan oleh
 * script pemantau (Native.buildMonitor) yang berjalan di proses shell lewat Shizuku.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.tweak.tuner.STOP_OVERLAY"
        private const val CH = "fps_overlay"
        private const val D = "/data/local/tmp"
        private const val HB = "$D/tt_fps.hb"
        private const val OUT = "$D/tt_fps.out"
        private const val STOP = "$D/tt_fps.stop"
        private const val PID = "$D/tt_fps.pid"
        private const val SCRIPT = "$D/tt_fps.sh"

        @Volatile var running = false

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var box: LinearLayout? = null
    private lateinit var tvMain: TextView
    private lateinit var tvSub: TextView
    @Volatile private var alive = false
    private var lastSig = ""
    private var lastLog = 0L
    private var noLine = 0
    private var lastWait = 0L
    private var badCount = 0
    private var diagSig = ""
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tweak", MODE_PRIVATE) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (box == null) addOverlay()
        if (!alive) {
            alive = true; running = true
            DebugLog.d("overlay: dimulai")
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Penghitung FPS", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FPS counter aktif")
            .setContentText("Ketuk untuk membuka aplikasi")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Matikan", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun addOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bg = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(12).toFloat() }
        tvMain = TextView(this).apply {
            text = "FPS : --"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        tvSub = TextView(this).apply { textSize = 10f; setTextColor(0xFFB8B8B8.toInt()); visibility = android.view.View.GONE }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(tvMain); addView(tvSub)
        }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ov_x", dp(12)); y = prefs.getInt("ov_y", dp(48))
        }
        // geser dengan jari; posisi disimpan
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    try { wm.updateViewLayout(b, lp) } catch (e: Exception) {}
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("ov_x", lp.x).putInt("ov_y", lp.y).apply()
            }
            true
        }
        wm.addView(b, lp)
        box = b
    }

    private fun show(main: String, sub: String, color: Int = Color.WHITE) = ui.post {
        if (box == null) return@post
        tvMain.text = main; tvMain.setTextColor(color)
        tvSub.text = sub; tvSub.visibility = if (sub.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Catat ke debug log: saat paket/layer/catatan berubah, atau tiap 15 detik. */
    private fun logFps(p: List<String>) {
        val sig = "${p.getOrElse(0) { "" }}|${p.getOrElse(1) { "" }}|${p.getOrElse(4) { "" }}|${p.getOrElse(6) { "" }}"
        val now = System.currentTimeMillis()
        if (sig == lastSig && now - lastLog < 15000) return
        lastSig = sig; lastLog = now
        DebugLog.d("fps: paket=${p.getOrElse(0) { "" }.ifEmpty { "-" }} api=${p.getOrElse(1) { "-" }} fps=${p.getOrElse(2) { "0" }} " +
            "gpu=${p.getOrElse(3) { "" }} layer=${p.getOrElse(4) { "" }.ifEmpty { "-" }} sampel=${p.getOrElse(5) { "0" }} catatan=${p.getOrElse(6) { "-" }}")
    }

    /** Latar belakang: pastikan script pemantau hidup, kirim detak, baca hasil tiap detik. */
    private fun loop() {
        var started = false
        val poll = "#nolog\necho 1 > $HB\ncat $OUT 2>/dev/null\n"
        while (alive) {
            val s = ShellHub.get(applicationContext)
            if (s == null) {
                show("Menunggu Shizuku…", "")
                if (System.currentTimeMillis() - lastWait > 10000) { lastWait = System.currentTimeMillis(); DebugLog.d("overlay: menunggu Shizuku/service") }
                sleep(1000); continue
            }
            try {
                if (!started) {
                    val sc = Native.buildMonitor()
                    if (sc == null) { DebugLog.d("overlay: buildMonitor null (${Native.lastError()})"); show("Native gagal dimuat", ""); break }
                    val r = s.exec("#nolog\n" +
                        "echo 1 > $HB\n" +
                        "if [ -f $PID ] && kill -0 \$(cat $PID) 2>/dev/null; then echo started; else\n" +
                        "cat > $SCRIPT <<'TT_MON_EOF'\n$sc\nTT_MON_EOF\n" +
                        "rm -f $STOP\n" +
                        "S=\"\"; command -v setsid >/dev/null 2>&1 && S=setsid\n" +
                        "( \$S sh $SCRIPT </dev/null >/dev/null 2>&1 & ) </dev/null >/dev/null 2>&1\n" +
                        "echo started\nfi\n")
                    started = r.contains("started")
                    DebugLog.d("overlay: start monitor -> ${if (started) "ok" else "gagal: ${r.take(120)}"}")
                    if (!started) { show("Gagal memulai: ${r.take(60)}", ""); sleep(2000); continue }
                    noLine = 0
                }
                val line = s.exec(poll).trim().lineSequence().firstOrNull { it.contains('|') }
                if (line == null) {
                    show("FPS : --", "")
                    if (++noLine == 4) {          // monitor belum menulis apa pun: catat diagnosis dan coba mulai ulang
                        val d = s.exec("#nolog\nls -l $D 2>&1 | grep tt_fps\n[ -f $PID ] && kill -0 \$(cat $PID) 2>&1 && echo monitor-hidup || echo monitor-mati\n")
                        DebugLog.d("overlay: tidak ada keluaran monitor\n${d.trim().take(400)}")
                        started = false; noLine = 0
                    }
                } else {
                    noLine = 0
                    val p = line.split('|')
                    logFps(p)
                    val pkgOut = p.getOrElse(0) { "" }
                    val noteOut = p.getOrElse(6) { "" }
                    if (pkgOut.isNotEmpty() && (noteOut == "---" || noteOut == "---")) {
                        if (++badCount == 8 && diagSig != pkgOut) { diagSig = pkgOut; diagnose(s, pkgOut, p.getOrElse(4) { "" }) }
                    } else badCount = 0
                    val api = p.getOrElse(1) { "-" }.let { if (it == "-") "GPU" else it }
                    val fps = p.getOrElse(2) { "0" }.toIntOrNull() ?: 0
                    val gpu = p.getOrElse(3) { "" }
                    val note = p.getOrElse(6) { "" }
                    val color = when {
                        fps >= 55 -> 0xFFC6F135.toInt()
                        fps >= 30 -> 0xFFFFC857.toInt()
                        else -> 0xFFFF6B6B.toInt()
                    }
                    val sub = listOf(gpu, if (note.isNotEmpty() && note != "ok") note else "").filter { it.isNotEmpty() }.joinToString(" · ")
                    show("$api : $fps FPS", sub, color)
                }
            } catch (e: Exception) {
                started = false
                DebugLog.d("overlay loop error: ${e.javaClass.simpleName}: ${e.message}")
            }
            sleep(1000)
        }
    }

    /** Satu kali per paket: catat apa yang dikembalikan SurfaceFlinger supaya mudah dilacak di Debug log. */
    private fun diagnose(s: IShellService, pkg: String, layer: String) {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(pkg)) return
        val l = layer.replace("'", "")
        try {
            val out = s.exec("#nolog\n" +
                "echo '--- latency'; dumpsys SurfaceFlinger --latency '$l' </dev/null 2>&1 | head -n 4\n" +
                "echo '--- timestats'; dumpsys SurfaceFlinger --timestats -dump </dev/null 2>&1 | grep -E 'layerName|packageName|totalFrames' | grep -F -A2 '$pkg' | head -n 9\n" +
                "echo '--- layer'; dumpsys SurfaceFlinger --list </dev/null 2>&1 | grep -F '$pkg' | head -n 5\n")
            DebugLog.d("fps diagnosis $pkg:\n${out.trim().take(700)}")
        } catch (e: Exception) { DebugLog.d("fps diagnosis gagal: ${e.message}") }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}

    override fun onDestroy() {
        alive = false; running = false
        DebugLog.d("overlay: dihentikan")
        box?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        box = null
        val s = ShellHub.svc
        if (s != null) Thread { try { s.exec("#nolog\ntouch $STOP") } catch (e: Exception) {} }.start()
        super.onDestroy()
    }
}
