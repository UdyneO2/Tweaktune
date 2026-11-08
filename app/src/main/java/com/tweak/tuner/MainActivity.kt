package com.tweak.tuner

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.widget.BaseAdapter
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DIR = "/sdcard/tweak"
private const val LIVE = "/data/local/tmp/tt_live"

class MainActivity : Activity() {

    private data class App(val pkg: String, val label: String)

    // ───── View ─────
    private lateinit var list: ListView
    private lateinit var tvLog: TextView
    private lateinit var tvLogState: TextView
    private lateinit var logDot: View
    private lateinit var logPanel: View
    private lateinit var rowStatus: View
    private lateinit var tvShizuku: TextView
    private lateinit var tvProfile: TextView
    private lateinit var tvProfSub: TextView
    private lateinit var tvSelBig: TextView
    private lateinit var tvSelOf: TextView
    private lateinit var tvCount: TextView
    private var profile = Native.STANDARD            // profil aktif (dibaca dari perangkat saat dibuka)
    private var activeMods: List<String> = emptyList()
    private lateinit var pbSel: ProgressBar
    private lateinit var etFps: EditText
    private lateinit var etScale: EditText
    private lateinit var etSize: EditText
    private lateinit var etDpi: EditText
    private lateinit var llModules: LinearLayout
    private var llRend: LinearLayout? = null
    private lateinit var swAll: Switch
    private lateinit var adapter: GameAdapter
    private val icons = ConcurrentHashMap<String, Drawable>()
    private var swFps: Switch? = null
    private var syncingFps = false

    // ───── State ─────
    private var items: List<App> = emptyList()
    private val selected = linkedSetOf<String>()
    private var svc: IShellService? = null
    private var binding = false
    private var bindAt = 0L
    private var bindTries = 0
    private var who = ""
    private var mode = "-"            // "service" atau "process"
    private var lastBindErr = ""
    private var lastState = ""
    @Volatile private var liveRunning = false

    // ───── Renderer ─────
    private var rdMask = 3                 // default + native selalu ada
    private var rdDetected = false
    @Volatile private var rdBusy = false
    private val rdKv = hashMapOf<String, String>()
    private val rend = linkedMapOf<String, Int>()   // pkg -> renderer pilihan eksplisit

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tweak", Context.MODE_PRIVATE) }

    private val watcher = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, 2000)
        }
    }

    // Pengganti service lokal: dipakai kalau UserService Shizuku tidak tersambung
    private val local = object : IShellService.Stub() {
        override fun exec(script: String): String = ShellProc.exec(script)
        override fun destroy() {}
    }

    // ───── Shizuku ─────
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshState() } }
    private val deadListener = Shizuku.OnBinderDeadListener { svc = null; binding = false; runOnUiThread { refreshState() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshState() } }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = IShellService.Stub.asInterface(binder)
            ShellHub.svc = svc
            binding = false; mode = "service"
            DebugLog.d("service tersambung")
            detectWho()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            svc = null; binding = false; who = ""; bindTries = 0; mode = "-"
            DebugLog.d("service terputus")
        }
    }

    private val userServiceArgs by lazy {
        val stamp = try {
            (packageManager.getPackageInfo(packageName, 0).lastUpdateTime / 1000L).toInt()
        } catch (e: Exception) { 3 }
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(false).version(stamp)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Night.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            prefs.edit().putString("last_crash", android.util.Log.getStackTraceString(e)).commit()
            oldHandler?.uncaughtException(t, e)
        }
        DebugLog.d("Aplikasi dibuka")
        DebugLog.d("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        try {
            Native.lastError()
            DebugLog.d("native tweakcore dimuat")
        } catch (e: Throwable) {
            DebugLog.d("native GAGAL: ${e.javaClass.simpleName}: ${e.message}")
        }

        list = findViewById(R.id.list)
        list.itemsCanFocus = true          // supaya EditText di header bisa fokus dan keyboard stabil
        tvLog = findViewById(R.id.tvLog)
        tvLogState = findViewById(R.id.tvLogState)
        logDot = findViewById(R.id.logDot)
        logPanel = findViewById(R.id.logPanel)
        findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEEE, d MMM", Locale("id", "ID")).format(Date())
        findViewById<Button>(R.id.btnCloseLog).setOnClickListener {
            stopLogAnim()
            logPanel.visibility = View.GONE
        }

        val header = LayoutInflater.from(this).inflate(R.layout.header, list, false)
        list.addHeaderView(header, null, false)
        val footer = LayoutInflater.from(this).inflate(R.layout.footer_game, list, false)
        list.addFooterView(footer, null, false)
        rowStatus = header.findViewById(R.id.rowStatus)
        tvShizuku = header.findViewById(R.id.tvShizuku)
        ObjectAnimator.ofFloat(header.findViewById<View>(R.id.dot), "alpha", 1f, 0.3f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }.start()

        tvProfile = header.findViewById(R.id.tvProfile)
        tvProfSub = header.findViewById(R.id.tvProfSub)
        tvSelBig = header.findViewById(R.id.tvSelBig)
        tvSelOf = header.findViewById(R.id.tvSelOf)
        tvCount = header.findViewById(R.id.tvCount)
        pbSel = header.findViewById(R.id.pbSel)
        etFps = header.findViewById(R.id.etFps)
        etScale = header.findViewById(R.id.etScale)
        etSize = header.findViewById(R.id.etSize)
        etDpi = header.findViewById(R.id.etDpi)
        header.findViewById<View>(R.id.cardProfile).setOnClickListener { showProfileDialog() }
        llModules = header.findViewById(R.id.llModules)
        swAll = header.findViewById(R.id.swAll)

        // Daftar game bergaya Pengaturan Apps: ikon, nama, paket, kotak centang
        adapter = GameAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val i = pos - list.headerViewsCount
            if (i in items.indices) {
                val pkg = items[i].pkg
                if (!selected.add(pkg)) selected.remove(pkg)
                adapter.notifyDataSetChanged()
                updateSummary()
            }
        }

        loadPrefs()

        footer.findViewById<Button>(R.id.btnAll).setOnClickListener { selected.addAll(items.map { it.pkg }); applyChecks() }
        footer.findViewById<Button>(R.id.btnNone).setOnClickListener { selected.clear(); applyChecks() }
        swAll.setOnCheckedChangeListener { _, _ -> reload() }
        buildModuleCards()
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val i = pos - list.headerViewsCount
            if (i in items.indices) pickRenderer(listOf(items[i].pkg), items[i].label)
            true
        }

        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSummary() }
        }
        etFps.addTextChangedListener(tw)
        etScale.addTextChangedListener(tw)

        // Tombol "Selesai" di keyboard: tutup keyboard dan lepas fokus
        val done = TextView.OnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
            }
            false
        }
        etDpi.setOnEditorActionListener(done)
        etSize.setOnEditorActionListener(done)

        setupPages()

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        reload()
        refreshState()
        updateSummary()
    }

    override fun onResume() {
        super.onResume()
        swFps?.let { syncingFps = true; it.isChecked = OverlayService.running; syncingFps = false }
        handler.post(watcher)
    }

    override fun onPause() {
        handler.removeCallbacks(watcher)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    // ───── Ringkasan di header ─────
    private fun updateSummary() {
        val p = profile
        tvProfile.text = when (p) { Native.GAMING -> "Gaming"; Native.DAILY -> "Harian"; else -> "Standar" }
        val status = when (p) {
            Native.GAMING -> "Aktif · ${etFps.text.toString().ifBlank { "-" }} FPS · skala ${etScale.text.toString().ifBlank { "-" }}"
            Native.DAILY -> "Aktif · hemat baterai"
            else -> if (activeMods.isEmpty()) "Bawaan sistem" else "Modul aktif: ${activeMods.joinToString(", ")}"
        }
        tvProfSub.text = "$status\nKetuk untuk ganti"
        tvSelBig.text = selected.size.toString()
        tvSelOf.text = " dari ${items.size}"
        pbSel.max = maxOf(items.size, 1)
        pbSel.progress = minOf(selected.size, pbSel.max)
        tvCount.text = selected.size.toString()
    }

    // ───── Profil aktif ─────
    private fun profileName(p: Int) = when (p) { Native.GAMING -> "Gaming"; Native.DAILY -> "Harian"; else -> "Standar (bawaan sistem)" }

    private fun showProfileDialog() {
        val ps = listOf(Native.GAMING, Native.DAILY, Native.STANDARD)
        val labels = ps.map { (if (it == profile) "●  " else "     ") + profileName(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Pilih profil (langsung aktif)")
            .setItems(labels) { _, i -> activateProfile(ps[i]) }
            .setNegativeButton("Batal", null).show()
    }

    /** Memilih profil = menerapkan tweaknya; Standar = kembalikan semua ke bawaan. Status dibaca ulang setelah selesai. */
    private fun activateProfile(p: Int) {
        if (liveRunning) return toast("Masih ada proses yang berjalan")
        val s = buildApply(0, p) ?: return
        savePrefs()
        DebugLog.d("aktifkan profil ${profileName(p)}")
        runLive(s)
    }

    /** Baca profil aktif dari perangkat (penanda + nilai tweak + backup modul). Dipanggil saat dibuka dan setelah tiap proses. */
    private fun checkStatus() {
        val s = svc ?: ShellHub.svc ?: return
        Thread {
            val script = try { Native.buildStatus() } catch (e: Throwable) { null } ?: return@Thread
            val out = try { s.exec("#nolog\n$script") } catch (e: Exception) {
                DebugLog.d("cek status gagal: ${e.message}"); return@Thread
            }
            var p = Native.STANDARD
            val mods = mutableListOf<String>()
            out.lines().forEach { l ->
                when {
                    l.startsWith("PROFILE ") -> p = when (l.substringAfter(' ').trim()) {
                        "gaming" -> Native.GAMING; "daily" -> Native.DAILY; else -> Native.STANDARD
                    }
                    l.startsWith("MOD ") && l.endsWith("=1") -> mods += l.substring(4, l.length - 2)
                }
            }
            DebugLog.d("status: profil aktif=${profileName(p)}, modul=$mods | ${out.trim().replace("\n", " / ").take(300)}")
            runOnUiThread { profile = p; activeMods = mods; savePrefs(); updateSummary() }
        }.start()
    }

    // ───── Menu ─────
    private fun box(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 16, 48, 0)
        v.forEach { addView(it) }
    }

    private fun saveProfile() {
        val script = buildApply() ?: return
        val et = EditText(this).apply { hint = "Nama profil"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Simpan profil").setView(box(et))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val name = et.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "profil" }
                val body = scriptMeta(name, "Profil ${profileName(profile)}") + script.removePrefix("#!/system/bin/sh\n") + "\n"
                run("mkdir -p $DIR\ncat > $DIR/$name.sh <<'TWEAK_EOF'\n$body\nTWEAK_EOF\necho \"Tersimpan: $DIR/$name.sh\"\n")
                handler.postDelayed({ if (page == 1) loadScripts() }, 2500)
            }.show()
    }

    /** Simpan satu file .sh per modul + satu file reset, supaya bisa dijalankan terpisah. */
    private fun saveModules() {
        val et = EditText(this).apply { hint = "Awalan nama file"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Simpan script per modul").setView(box(et))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val name = et.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "tweak" }
                val prof = currentProfile().let { if (it == Native.STANDARD) Native.GAMING else it }
                val sb = StringBuilder("mkdir -p $DIR\n")
                val files = mutableListOf<String>()
                fun add(file: String, body: String) {
                    sb.append("cat > $DIR/$file <<'TWEAK_EOF'\n$body\nTWEAK_EOF\n")
                    files += file
                }
                for ((bit, mod) in MODULES) {
                    val s = buildApply(bit, prof) ?: return@setPositiveButton
                    add("${name}_$mod.sh", scriptMeta("${name}_$mod", "Modul $mod") + s.removePrefix("#!/system/bin/sh\n"))
                }
                Native.buildRevert(0)?.let { add("${name}_reset.sh", scriptMeta("${name}_reset", "Reset semua modul") + it.removePrefix("#!/system/bin/sh\n")) }
                sb.append("echo \"Tersimpan di $DIR:\"\nls -1 $DIR/${name}_*.sh\n")
                run(sb.toString())
                handler.postDelayed({ if (page == 1) loadScripts() }, 2500)
            }.show()
    }

    private fun scriptCmd(f: String) =
        "F=\"$f\"\n" +
        "T=/data/local/tmp/tt_custom.sh\n" +
        "if [ ! -r \"\$F\" ]; then echo \"Tidak bisa membaca \$F\"; ls -l \"\$F\" 2>&1; exit 1; fi\n" +
        "tr -d '\\r' < \"\$F\" > \$T || { echo 'Gagal menyalin script'; exit 1; }\n" +
        "echo \"Menjalankan \$F\"\n" +
        "sh \$T </dev/null 2>&1\n" +
        "echo \"[selesai, kode keluar \$?]\"\n"

    // ───── Debug ─────
    private fun showDebug() {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextIsSelectable(true); setPadding(0, 16, 0, 0)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        val clear = Button(this, null, 0, R.style.Tweak_Button_Tonal).apply { text = "Hapus log" }
        val b = box(clear, sv)
        val h = (420 * resources.displayMetrics.density).toInt()
        sv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)

        var report = ""
        fun load() {
            tv.text = "Memuat…"
            buildReport { r ->
                report = r
                runOnUiThread { tv.text = r; sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
            }
        }

        val dlg = AlertDialog.Builder(this).setTitle("Debug log").setView(b)
            .setPositiveButton("Ekspor", null)
            .setNeutralButton("Muat ulang", null)
            .setNegativeButton("Tutup", null).show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { exportDebug(report) }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { load() }
        clear.setOnClickListener { clearLogs { load() } }
        load()
    }

    private fun buildReport(cb: (String) -> Unit) {
        val granted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val crash = prefs.getString("last_crash", null)
        val head = buildString {
            appendLine("== Info ==")
            appendLine("Aplikasi: ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Perangkat: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Shizuku berjalan: ${Shizuku.pingBinder()}")
            appendLine("Versi Shizuku: ${try { Shizuku.getVersion() } catch (e: Throwable) { "?" }}")
            appendLine("Izin: $granted")
            appendLine("Service tersambung: ${svc != null} $who")
            appendLine("Mode eksekusi: $mode")
            if (lastBindErr.isNotEmpty()) appendLine("Error bind terakhir: $lastBindErr")
            if (crash != null) { appendLine(); appendLine("== Crash terakhir =="); appendLine(crash) }
            appendLine()
            appendLine("== Log aplikasi ==")
            appendLine(DebugLog.dump())
            appendLine()
            appendLine("== Log service Shizuku ==")
        }
        val s = svc
        if (s == null || mode == "process") {
            cb(head + "(tidak ada proses service di mode ini, log service tidak tersedia)\n")
            return
        }
        Thread {
            val sl = try { s.exec("#nolog\ntail -c 20000 $SVLOG 2>&1") } catch (e: Exception) { "ERR: ${e.message}" }
            cb(head + sl.ifBlank { "(kosong)" } + "\n")
        }.start()
    }

    private fun clearLogs(done: () -> Unit) {
        DebugLog.clear()
        prefs.edit().remove("last_crash").apply()
        val s = svc
        if (s == null) { done(); return }
        Thread {
            try { s.exec("#nolog\n: > $SVLOG") } catch (e: Exception) { }
            runOnUiThread { done() }
        }.start()
    }

    private fun exportDebug(report: String) {
        if (report.isEmpty()) return toast("Log belum dimuat")
        val name = "debug_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val s = svc
        if (s != null) {
            Thread {
                val out = try {
                    s.exec("#nolog\nmkdir -p $DIR\ncat > $DIR/$name <<'TT_DBG_EOF'\n$report\nTT_DBG_EOF\nls -l $DIR/$name 2>&1")
                } catch (e: Exception) { "ERR: ${e.message}" }
                val ok = out.contains(name) && !out.contains("No such")
                runOnUiThread { toast(if (ok) "Diekspor: $DIR/$name" else "Gagal ekspor: ${out.take(120)}") }
            }.start()
        } else {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = java.io.File(dir, name)
                f.writeText(report)
                toast("Diekspor: ${f.absolutePath}")
            } catch (e: Exception) {
                toast("Gagal ekspor: ${e.message}")
            }
        }
    }

    // ───── Shizuku: sambungan service ─────
    private fun detectWho() = Thread {
        val u = try { svc?.exec("#nolog\nid -u")?.trim() } catch (e: Exception) { null }
        who = when (u) { "0" -> "root"; "2000" -> "shell"; else -> "" }
        DebugLog.d("akses: ${who.ifEmpty { "?" }} (mode $mode)")
        runOnUiThread { refreshState(); checkStatus(); if (!rdDetected) detectRenderers(true) }
    }.start()

    private fun useFallback(why: String) {
        if (svc != null) return
        svc = local; ShellHub.svc = local; binding = false; mode = "process"
        DebugLog.d("fallback ke Shizuku.newProcess: $why")
        detectWho()
    }

    private fun ensureBind() {
        if (svc != null || !Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        val lama = try { Shizuku.isPreV11() || Shizuku.getVersion() < 10 } catch (e: Throwable) { false }
        if (lama) { useFallback("Shizuku terlalu lama untuk UserService"); return }

        if (binding && System.currentTimeMillis() - bindAt < 5000) return   // tunggu callback
        if (bindTries >= 1) { useFallback("bind tidak tersambung dalam 5 detik"); return }

        binding = true
        bindAt = System.currentTimeMillis()
        bindTries++
        DebugLog.d("bindUserService dipanggil")
        try {
            Shizuku.bindUserService(userServiceArgs, conn)
        } catch (e: Throwable) {
            lastBindErr = e.message ?: e.javaClass.simpleName
            DebugLog.d("bind error: $lastBindErr")
            useFallback("bind error")
        }
    }

    private fun refreshState() {
        val running = Shizuku.pingBinder()
        val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

        val st = "berjalan=$running izin=$granted service=${svc != null}"
        if (st != lastState) { lastState = st; DebugLog.d("status: $st") }

        if (!running || (svc != null && svc?.asBinder()?.pingBinder() != true)) {
            svc = null; binding = false; who = ""; bindTries = 0; mode = "-"
        }

        rowStatus.visibility = if (running) View.VISIBLE else View.GONE
        tvShizuku.text = when {
            !granted -> "Shizuku aktif, izin belum diberikan"
            svc == null -> "Shizuku aktif · menghubungkan layanan…"
            who.isNotEmpty() -> "Shizuku aktif · $who"
            else -> "Shizuku aktif"
        }
        if (granted) ensureBind()
    }

    /** Jalankan block setelah service siap; menunggu sampai 10 detik. */
    private fun withService(block: (IShellService) -> Unit) {
        svc?.let { block(it); return }
        if (!Shizuku.pingBinder()) return toast("Shizuku tidak berjalan")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Izin Shizuku belum diberikan")
        logStart()
        ensureBind()
        val t0 = System.currentTimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                val s = svc
                when {
                    s != null -> block(s)
                    System.currentTimeMillis() - t0 > 10000 -> { DebugLog.d("timeout menunggu service"); log(diag()) }
                    else -> { ensureBind(); handler.postDelayed(this, 300) }
                }
            }
        })
    }

    private fun diag(): String = buildString {
        appendLine("Service Shizuku tidak tersambung.")
        appendLine("Shizuku berjalan: ${Shizuku.pingBinder()}")
        appendLine("Izin: ${Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED}")
        appendLine("Versi Shizuku: ${try { Shizuku.getVersion() } catch (e: Throwable) { "?" }}")
        if (lastBindErr.isNotEmpty()) appendLine("Error bind: $lastBindErr")
        append("Tanyakan ke udyne @https://t.me/mnrdnn")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───── Panel log ─────
    private var typer: ValueAnimator? = null
    private var pulse: ObjectAnimator? = null

    private fun stopLogAnim() {
        typer?.cancel()
        pulse?.cancel()
        logDot.alpha = 1f
    }

    private fun setState(s: String, running: Boolean) {
        tvLogState.text = s
        pulse?.cancel()
        if (running) {
            pulse = ObjectAnimator.ofFloat(logDot, "alpha", 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else logDot.alpha = 1f
    }

    /** Perintah mulai: panel terbuka, lampu berkedip. */
    private fun logStart() {
        stopLogAnim()
        logPanel.visibility = View.VISIBLE
        tvLog.text = ""
        setState("Menjalankan…", true)
    }

    /** Output yang masuk saat proses masih berjalan (live). */
    private fun logLive(text: String) {
        tvLog.text = text
        (tvLog.parent as? ScrollView)?.post { (tvLog.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun logEnd(state: String) = setState(state, false)

    /** Hasil akhir perintah singkat: diketik pelan. */
    private fun log(s: String) {
        stopLogAnim()
        logPanel.visibility = View.VISIBLE
        setState(if (s.startsWith("ERR")) "Gagal" else "Selesai", false)
        if (s.length > 4000) { tvLog.text = s; return }
        val sv = tvLog.parent as? ScrollView
        typer = ValueAnimator.ofInt(0, s.length).apply {
            duration = (s.length * 6L).coerceIn(300L, 1800L)
            addUpdateListener {
                val n = it.animatedValue as Int
                tvLog.text = if (n < s.length) s.substring(0, n) + "▌" else s
                sv?.fullScroll(View.FOCUS_DOWN)
            }
            start()
        }
    }

    // ───── Eksekusi ─────
    /** Perintah singkat: satu panggilan, hasil diketik. */
    private fun run(script: String) = withService { s ->
        logStart()
        DebugLog.d("run: script ${script.length} karakter")
        Thread {
            val out = try { s.exec(script) } catch (e: Exception) { "ERR: ${e.message}" }
            DebugLog.d("selesai: output ${out.length} karakter" + if (out.startsWith("ERR")) " ($out)" else "")
            runOnUiThread { log(out.ifBlank { "(tanpa output)" }) }
        }.start()
    }

    /** Proses panjang: jalan di latar belakang, log dibaca tiap 0,5 detik dan tampil langsung. */
    private fun runLive(script: String) {
        if (liveRunning) return toast("Masih ada proses yang berjalan")
        withService { s ->
            liveRunning = true
            logStart()
            DebugLog.d("runLive: script ${script.length} karakter")
            val start = "#nolog\n" +
                "cat > $LIVE.sh <<'TT_EOF'\n$script\nTT_EOF\n" +
                "rm -f $LIVE.log $LIVE.done\n" +
                "( sh $LIVE.sh </dev/null > $LIVE.log 2>&1; echo \$? > $LIVE.done ) </dev/null >/dev/null 2>&1 &\n" +
                "echo started\n"
            val poll = "#nolog\n" +
                "tail -c 20000 $LIVE.log 2>/dev/null\n" +
                "[ -f $LIVE.done ] && printf '\\n__DONE__%s' \"\$(cat $LIVE.done)\"\n"
            Thread {
                try {
                    val first = s.exec(start)
                    if (!first.contains("started")) {
                        DebugLog.d("runLive gagal mulai: ${first.take(200)}")
                        runOnUiThread { logLive("Gagal memulai:\n$first"); logEnd("Gagal") }
                        return@Thread
                    }
                    var last = ""
                    var n = 0
                    while (n++ < 1200) {
                        Thread.sleep(500)
                        val r = s.exec(poll)
                        val m = Regex("__DONE__(-?\\d+)\\s*$").find(r)
                        val text = (if (m != null) r.substring(0, m.range.first) else r).trimEnd()
                        if (text != last) { last = text; runOnUiThread { logLive(text) } }
                        if (m != null) {
                            val code = m.groupValues[1]
                            DebugLog.d("runLive selesai, kode $code")
                            runOnUiThread { logEnd(if (code == "0") "Selesai ✔" else "Selesai · kode $code") }
                            checkStatus()
                            return@Thread
                        }
                    }
                    runOnUiThread { logEnd("Masih berjalan di latar belakang") }
                } catch (e: Exception) {
                    DebugLog.d("runLive error: ${e.message}")
                    runOnUiThread { logLive("ERR: ${e.message}"); logEnd("Gagal") }
                } finally {
                    liveRunning = false
                }
            }.start()
        }
    }

    private fun currentProfile() = profile

    /** Pilihan eksplisit (jika masih tersedia di perangkat), selain itu bawaan sistem. */
    private fun rendererOf(pkg: String): Int {
        val r = rend[pkg] ?: return Native.R_DEFAULT
        return if (rdMask and (1 shl r) != 0) r else Native.R_DEFAULT
    }

    /** Hanya game yang dipilih eksplisit lewat tekan lama; game lain tidak disentuh. */
    private fun rendererArg() = rend.keys.joinToString(",") { "$it=${Native.RENDERER_NAMES[rendererOf(it)]}" }

    /** modules 0 = bawaan sesuai mode; selain itu hanya modul tersebut. */
    private fun buildApply(modules: Int = 0, profile: Int = currentProfile()): String? {
        val fps = etFps.text.toString().trim().toIntOrNull() ?: 0
        val scale = etScale.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val dpi = etDpi.text.toString().trim().toIntOrNull() ?: 0
        var w = 0; var h = 0
        val size = etSize.text.toString().trim()
        if (size.isNotEmpty()) {
            val m = Regex("""^(\d{3,4})[xX](\d{3,4})$""").matchEntire(size)
                ?: run { toast("Format ukuran: 1080x2400"); return null }
            w = m.groupValues[1].toInt(); h = m.groupValues[2].toInt()
        }
        return try {
            Native.buildApply(profile, modules, fps, scale, false, w, h, dpi, selected.joinToString(","), rendererArg())
                ?: run { toast(Native.lastError()); null }
        } catch (e: Throwable) {
            DebugLog.d("buildApply gagal: ${e.javaClass.simpleName}: ${e.message}")
            toast("Library native gagal dimuat (cek ABI)")
            null
        }
    }

    // ───── Modul: kartu dengan Terapkan / Reset sendiri ─────
    private val MODULES = listOf(
        Native.M_TWEAK to "tweak", Native.M_OVERLAY to "overlay", Native.M_FPS to "fps",
        Native.M_DISPLAY to "display", Native.M_WM to "wm", Native.M_RENDERER to "renderer"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun applyModule(mask: Int) {
        val prof = if (profile == Native.STANDARD) Native.GAMING else profile
        buildApply(mask, prof)?.let { savePrefs(); runLive(it) }
    }

    private fun resetModule(mask: Int) {
        val s = try { Native.buildRevert(mask) } catch (e: Throwable) {
            DebugLog.d("buildRevert gagal: ${e.javaClass.simpleName}: ${e.message}")
            toast("Library native gagal dimuat (cek ABI)"); null
        }
        s?.let { runLive(it) }
    }

    private fun newCard(title: String, desc: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.t_on_surface))
        })
        card.addView(TextView(this).apply {
            text = desc; textSize = 12f; setPadding(0, dp(2), 0, dp(8))
            setTextColor(getColor(R.color.t_on_variant))
        })
        return card
    }

    private fun addModule(title: String, desc: String, mask: Int, extra: ((LinearLayout) -> Unit)? = null) {
        val card = newCard(title, desc)
        extra?.invoke(card)
        fun btn(label: String, style: Int, click: () -> Unit) = Button(this, null, 0, style).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4); marginStart = dp(4) }
            setOnClickListener { click() }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Terapkan", R.style.Tweak_Button_Filled) { applyModule(mask) })
            addView(btn("Reset", R.style.Tweak_Button_Tonal) { resetModule(mask) })
        })
        llModules.addView(card)
    }

    private fun buildModuleCards() {
        addModule("Tweak sistem", "Tweak device_config sesuai mode (Gaming / Harian)", Native.M_TWEAK)
        addModule("Game overlay", "FPS maks + skala resolusi + mode performa untuk game terpilih", Native.M_OVERLAY)
        addModule("Kunci FPS / refresh rate", "Kunci frame rate dan refresh rate sesuai kolom FPS", Native.M_FPS)
        addModule("Display", "Refresh rate tidak turun saat idle / daya rendah", Native.M_DISPLAY)
        addModule("Ukuran layar & DPI", "wm size dan wm density dari kolom di atas", Native.M_WM)
        val card = newCard("Renderer per game",
            "Info driver grafis. Tekan lama sebuah game di daftar untuk memilih renderer-nya (langsung diterapkan).")
        llRend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        card.addView(llRend)
        updateRendererCard()
        fun b(label: String, style: Int, click: () -> Unit) = Button(this, null, 0, style).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4); marginStart = dp(4) }
            setOnClickListener { click() }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(b("Deteksi ulang", R.style.Tweak_Button_Tonal) { detectRenderers(false) })
            addView(b("Reset", R.style.Tweak_Button_Tonal) {
                resetModule(Native.M_RENDERER); rend.clear(); savePrefs(); refreshRows()
            })
        })
        llModules.addView(card)
    }

    private fun startOverlay() {
        fun off() { syncingFps = true; swFps?.isChecked = false; syncingFps = false }
        if (!Settings.canDrawOverlays(this)) {
            off()
            toast("Izinkan \"Tampil di atas aplikasi lain\" lalu nyalakan lagi")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!ShellHub.ready()) { off(); return toast("Shizuku belum siap / izin belum diberikan") }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        try { OverlayService.start(this) } catch (e: Exception) {
            off(); toast("Gagal memulai overlay: ${e.message}")
        }
    }

    private fun infoRow(key: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        addView(TextView(this@MainActivity).apply {
            text = key; textSize = 13f
            setTextColor(getColor(R.color.t_on_variant))
            layoutParams = LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(this@MainActivity).apply {
            text = value; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.t_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    /** Hanya yang tersedia, rata dua kolom: OpenGL ES, Vulkan, GPU, renderer. */
    private fun updateRendererCard() {
        val box = llRend ?: return
        box.removeAllViews()
        if (!rdDetected) {
            box.addView(TextView(this).apply {
                text = "Belum terdeteksi (perlu Shizuku)"; textSize = 13f
                setTextColor(getColor(R.color.t_on_variant))
            })
            return
        }
        val gl = rdKv["glstr"].orEmpty().removePrefix("OpenGL ES ").ifBlank { rdKv["gles"].orEmpty() }
        if (gl.isNotBlank()) box.addView(infoRow("OpenGL ES", gl))
        if (rdMask and Native.DETECT_VULKAN != 0) {
            val v = listOf(rdKv["vulkan"].orEmpty(), rdKv["vkhw"].orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            box.addView(infoRow("Vulkan", v.ifBlank { "tersedia" }))
        }
        rdKv["gpu"]?.takeIf { it.isNotBlank() }?.let { box.addView(infoRow("GPU", it)) }
        val short = listOf("", "Native", "ANGLE", "Driver game", "Prarilis")
        val rs = (1 until short.size).filter { rdMask and (1 shl it) != 0 }.joinToString(", ") { short[it] }
        if (rs.isNotEmpty()) box.addView(infoRow("Renderer", rs))
    }

    /** Jalankan script deteksi lewat service lalu simpan renderer yang tersedia. */
    private fun detectRenderers(quiet: Boolean, after: (() -> Unit)? = null) {
        if (rdBusy) return
        val go = { s: IShellService ->
            rdBusy = true
            Thread {
                try {
                    val script = try { Native.buildDetect() } catch (e: Throwable) { null }
                    val out = if (script == null) "" else
                        try { s.exec("#nolog\n$script") } catch (e: Exception) { "" }
                    if (out.isNotEmpty()) {
                        rdMask = try { Native.parseRenderers(out) } catch (e: Throwable) { 3 }
                        rdKv.clear()
                        out.lines().filter { it.startsWith("INFO ") }.forEach {
                            val kv = it.removePrefix("INFO ").split("=", limit = 2)
                            if (kv.size == 2) rdKv[kv[0]] = kv[1].trim()
                        }
                        rdDetected = true
                        DebugLog.d("renderer terdeteksi, mask=$rdMask")
                    }
                } finally { rdBusy = false }
                runOnUiThread { updateRendererCard(); after?.invoke() }
            }.start()
        }
        val s = svc
        if (s != null) go(s) else if (!quiet) withService { go(it) }
    }

    /** Dialog pilih renderer untuk satu game (tahan lama) atau banyak game (tombol Atur). */
    private fun pickRenderer(pkgs: List<String>, title: String) {
        if (!rdDetected) { detectRenderers(false) { if (rdDetected) pickRenderer(pkgs, title) }; return }
        val ids = (0 until Native.RENDERER_LABELS.size).filter { rdMask and (1 shl it) != 0 }
        val cur = if (pkgs.size == 1) ids.indexOf(rendererOf(pkgs[0])).coerceAtLeast(0) else -1
        var sel = cur
        AlertDialog.Builder(this).setTitle("Renderer: $title")
            .setSingleChoiceItems(ids.map { Native.RENDERER_LABELS[it] }.toTypedArray(), cur) { _, which -> sel = which }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Terapkan") { _, _ ->
                if (sel < 0) return@setPositiveButton
                pkgs.forEach { rend[it] = ids[sel] }
                savePrefs(); refreshRows()
                applyModule(Native.M_RENDERER)
            }.show()
    }

    // ───── Daftar game ─────
    private fun isGame(a: ApplicationInfo): Boolean =
        a.category == ApplicationInfo.CATEGORY_GAME || (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    private fun reload() {
        val showAll = swAll.isChecked
        Thread {
            val pm = packageManager
            val r = pm.getInstalledApplications(0)
                .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .filter { showAll || isGame(it) }
                .map { App(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                items = r
                adapter.notifyDataSetChanged()
                updateSummary()
                loadIcons(r)
            }
        }.start()
    }

    private fun refreshRows() = adapter.notifyDataSetChanged()

    private fun applyChecks() {
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    /** Ikon dimuat di latar belakang supaya daftar langsung tampil. */
    private fun loadIcons(apps: List<App>) {
        Thread {
            val pm = packageManager
            apps.forEach { a ->
                if (!icons.containsKey(a.pkg)) try { icons[a.pkg] = pm.getApplicationIcon(a.pkg) } catch (e: Exception) { }
            }
            runOnUiThread { adapter.notifyDataSetChanged() }
        }.start()
    }

    /** Baris menyatu dengan kepala dan kaki card; antar baris dipisah garis tipis. */
    private inner class GameAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_game, parent, false)
            val a = items[position]
            val sel = a.pkg in selected
            v.findViewById<View>(R.id.row).background = GradientDrawable().apply {
                setColor(getColor(if (sel) R.color.t_container else R.color.t_card))
            }
            v.findViewById<View>(R.id.divider).visibility = if (position == items.size - 1) View.GONE else View.VISIBLE
            v.findViewById<TextView>(R.id.title).text = a.label
            val r = rend[a.pkg]
            v.findViewById<TextView>(R.id.sub).text =
                a.pkg + if (r != null) " · ${Native.RENDERER_LABELS[r]}" else ""
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(icons[a.pkg])
            v.findViewById<CheckBox>(R.id.check).isChecked = sel
            return v
        }
    }

    // ───── Simpan/muat pengaturan ─────
    private fun savePrefs() = prefs.edit()
        .putString("fps", etFps.text.toString()).putString("scale", etScale.text.toString())
        .putString("size", etSize.text.toString()).putString("dpi", etDpi.text.toString())
        .putInt("profile", currentProfile())
        .putString("rend", rend.entries.joinToString(",") { "${it.key}=${it.value}" })
        .putStringSet("games", selected.toSet()).apply()

    private fun loadPrefs() {
        etFps.setText(prefs.getString("fps", "120")); etScale.setText(prefs.getString("scale", "1.00"))
        etSize.setText(prefs.getString("size", "")); etDpi.setText(prefs.getString("dpi", ""))
        profile = prefs.getInt("profile", Native.STANDARD)
        prefs.getString("rend", "")?.split(',')?.forEach {
            val kv = it.split('=')
            val v = kv.getOrNull(1)?.toIntOrNull()
            if (kv.size == 2 && v != null && v in Native.RENDERER_NAMES.indices) rend[kv[0]] = v
        }
        selected.addAll(prefs.getStringSet("games", emptySet()) ?: emptySet())
    }

    // ───── Halaman & navigasi bawah ─────
    private var page = 0                       // 0 beranda, 1 script, 2 terminal, 3 tentang, 4 pengaturan
    private lateinit var tvTitle: TextView
    private lateinit var btnTheme: ImageView
    private lateinit var tvTermOut: TextView
    private lateinit var etTerm: EditText
    private lateinit var rgDns: RadioGroup
    private lateinit var etDnsHost: EditText
    private lateinit var tvDnsState: TextView
    private val termHistory = mutableListOf<String>()
    private var termIdx = 0
    @Volatile private var termBusy = false

    private val titles = listOf("Tweak Tuner", "Script launcher", "Terminal", "Tentang", "Pengaturan")
    private val dnsOptions = listOf(
        "Otomatis (bawaan)" to "auto", "Mati" to "off", "Google · dns.google" to "dns.google",
        "Cloudflare · one.one.one.one" to "one.one.one.one",
        "AdGuard · dns.adguard.com" to "dns.adguard.com", "Kustom" to "custom"
    )
    private val CHANGELOG =
        "2.0  Final changes.\n" +
        "1.9  Navigasi bawah 4 halaman (Script, Terminal, Tentang, Setelan), tombol tema malam, DNS, perbaikan status profil dan FPS counter (Android 14+).\n" +
        "1.8  Profil lewat card + status aktif, mode debug, satu card game.\n" +
        "1.7  FPS counter overlay, info GPU, daftar game ala Pengaturan.\n" +
        "1.6  Modul terpisah, renderer per game, reset diperbaiki.\n" +
        "1.5  Versi dasar."

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun setupPages() {
        tvTitle = findViewById(R.id.tvTitle)
        btnTheme = findViewById(R.id.btnTheme)
        
        // Pastikan nama file prefs sama dengan yang di Night.wrap ("tweak")
        val prefs = getSharedPreferences("tweak", Context.MODE_PRIVATE)
        
        // 0 = System, 1 = Light, 2 = Dark
        val currentMode = prefs.getInt("ikuti sistem", 0)
    
        // Atur ikon tombol
        btnTheme.setImageResource(
            when (currentMode) {
                1 -> R.drawable.ic_theme_sun
                2 -> R.drawable.ic_theme_moon
                else -> if (isDark()) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun 
            }
        )
        btnTheme.setColorFilter(getColor(R.color.t_on_surface))
        listOf(R.id.navLauncher, R.id.navTerminal, R.id.navAbout, R.id.navSettings).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener { showPage(if (page == i + 1) 0 else i + 1) }
        }
        setupLauncherPage(); setupTerminalPage(); setupSettingsPage()
        showPage(prefs.getInt("page_restore", 0))
        prefs.edit().remove("page_restore").apply()
    }

    private fun showPage(p: Int) {
        page = p
        list.visibility = if (p == 0) View.VISIBLE else View.GONE
        listOf(R.id.pageLauncher, R.id.pageTerminal, R.id.pageAbout, R.id.pageSettings).forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (p == i + 1) View.VISIBLE else View.GONE
        }
        tvTitle.text = titles[p]
        listOf(
            Triple(R.id.navLauncherIcon, R.id.navLauncherText, 1), Triple(R.id.navTerminalIcon, R.id.navTerminalText, 2),
            Triple(R.id.navAboutIcon, R.id.navAboutText, 3), Triple(R.id.navSettingsIcon, R.id.navSettingsText, 4)
        ).forEach { (ic, tx, idx) ->
            val c = getColor(if (p == idx) R.color.t_accent_text else R.color.t_on_variant)
            findViewById<ImageView>(ic).setColorFilter(c)
            findViewById<TextView>(tx).setTextColor(c)
        }
        when (p) { 1 -> loadScripts(); 3 -> fillAbout(); 4 -> loadDnsState() }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (page != 0) showPage(0) else super.onBackPressed() }

    /** Jalankan skrip singkat tanpa panel log; hasil dikirim ke cb di UI thread. */
    private fun execQuiet(tag: String, script: String, cb: (String) -> Unit) {
        val s = svc ?: ShellHub.svc ?: return toast("Shizuku belum siap / izin belum diberikan")
        Thread {
            val out = try { s.exec("#nolog\n$script") } catch (e: Exception) { "ERR: ${e.message}" }
            DebugLog.d("$tag: ${out.trim().replace('\n', ' ').take(200)}")
            runOnUiThread { cb(out) }
        }.start()
    }

    // ───── Halaman Script launcher ─────
    private data class Script(val path: String, val meta: Map<String, String>, val size: Long, val mtime: Long)

    private fun setupLauncherPage() {
        findViewById<View>(R.id.btnSaveProfile).setOnClickListener { saveProfile() }
        findViewById<View>(R.id.btnSaveModules).setOnClickListener { saveModules() }
        findViewById<View>(R.id.btnReloadScripts).setOnClickListener { loadScripts() }
    }

    private fun safePath(p: String) = p.startsWith("$DIR/") && Regex("^[A-Za-z0-9_./ -]+$").matches(p)

    private fun loadScripts() {
        val s = svc ?: ShellHub.svc
        if (s == null) {
            findViewById<LinearLayout>(R.id.llScripts).removeAllViews()
            findViewById<TextView>(R.id.tvScriptsEmpty).text = "Shizuku belum siap, script tidak bisa dibaca."
            return
        }
        Thread {
            val out = try {
                s.exec("#nolog\n" +
                    "mkdir -p $DIR 2>/dev/null\n" +
                    "for f in $DIR/*.sh; do\n" +
                    "  [ -f \"\$f\" ] || continue\n" +
                    "  echo \"@@FILE \$f\"\n" +
                    "  echo \"@@STAT \$(stat -c '%s %Y' \"\$f\" 2>/dev/null)\"\n" +
                    "  head -n 15 \"\$f\" | grep -E '^# (Name|Author|Created|Profile|Description|Generator):'\n" +
                    "done\n")
            } catch (e: Exception) { DebugLog.d("launcher gagal: ${e.message}"); "" }
            val found = mutableListOf<Script>()
            var path = ""; var size = 0L; var mt = 0L; var meta = mutableMapOf<String, String>()
            fun flush() { if (path.isNotEmpty()) found += Script(path, meta, size, mt); path = ""; meta = mutableMapOf(); size = 0L; mt = 0L }
            out.lines().forEach { l ->
                when {
                    l.startsWith("@@FILE ") -> { flush(); path = l.removePrefix("@@FILE ").trim() }
                    l.startsWith("@@STAT ") -> {
                        val p = l.removePrefix("@@STAT ").trim().split(' ')
                        size = p.getOrNull(0)?.toLongOrNull() ?: 0L; mt = p.getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                    l.startsWith("# ") && l.contains(':') -> meta[l.substring(2, l.indexOf(':'))] = l.substringAfter(':').trim()
                }
            }
            flush()
            DebugLog.d("launcher: ${found.size} script di $DIR")
            runOnUiThread { renderScripts(found.sortedByDescending { it.mtime }) }
        }.start()
    }

    private fun renderScripts(items: List<Script>) {
        val box = findViewById<LinearLayout>(R.id.llScripts)
        box.removeAllViews()
        findViewById<TextView>(R.id.tvScriptsEmpty).text =
            if (items.isEmpty()) "Belum ada script di $DIR.\nSimpan profil atau modul dengan tombol di atas." else ""
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id"))
        items.forEach { sc ->
            val file = sc.path.substringAfterLast('/')
            val title = sc.meta["Name"]?.ifBlank { null } ?: file.removeSuffix(".sh")
            val created = sc.meta["Created"] ?: if (sc.mtime > 0) fmt.format(Date(sc.mtime * 1000)) else "-"
            val kb = String.format(Locale.US, "%.1f KB", sc.size / 1024.0)
            val card = newCard(title, "$file · $kb")
            card.addView(infoRow("Author", sc.meta["Author"] ?: "-"))
            card.addView(infoRow("Dibuat", created))
            sc.meta["Generator"]?.let { card.addView(infoRow("Dibuat oleh", it)) }
            sc.meta["Description"]?.takeIf { it.isNotBlank() }?.let { card.addView(infoRow("Deskripsi", it)) }
            fun b(label: String, style: Int, click: () -> Unit) = Button(this, null, 0, style).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4); marginStart = dp(4) }
                setOnClickListener { click() }
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(b("Jalankan", R.style.Tweak_Button_Filled) {
                    if (safePath(sc.path)) runLive(scriptCmd(sc.path)) else toast("Nama file tidak aman")
                })
                addView(b("Lihat", R.style.Tweak_Button_Tonal) { viewScript(sc.path) })
                addView(b("Hapus", R.style.Tweak_Button_Tonal) { confirmDelete(sc.path, title) })
            })
            box.addView(card)
        }
    }

    private fun viewScript(path: String) {
        if (!safePath(path)) return toast("Nama file tidak aman")
        execQuiet("lihat script", "head -c 6000 \"$path\"") { out ->
            val tv = TextView(this).apply {
                text = out; typeface = Typeface.MONOSPACE; textSize = 11f
                setTextIsSelectable(true); setPadding(0, 16, 0, 0)
            }
            AlertDialog.Builder(this).setTitle(path.substringAfterLast('/'))
                .setView(ScrollView(this).apply { addView(tv); setPadding(40, 0, 40, 0) })
                .setPositiveButton("Tutup", null).show()
        }
    }

    private fun confirmDelete(path: String, title: String) {
        if (!safePath(path)) return toast("Nama file tidak aman")
        AlertDialog.Builder(this).setTitle("Hapus script?").setMessage(title)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                execQuiet("hapus script", "rm -f \"$path\"; echo selesai") { loadScripts() }
            }.show()
    }

    // ───── Halaman Terminal (langsung ke tweakcore) ─────
    private fun setupTerminalPage() {
        tvTermOut = findViewById(R.id.tvTermOut)
        etTerm = findViewById(R.id.etTerm)
        tvTermOut.text = "tweakcore — ketik help untuk daftar perintah.\n\n"
        findViewById<View>(R.id.btnTermRun).setOnClickListener { submitTerm() }
        etTerm.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submitTerm(); true } else false
        }
        findViewById<View>(R.id.btnTermHist).setOnClickListener {
            if (termHistory.isNotEmpty()) {
                termIdx = (termIdx - 1).coerceAtLeast(0)
                etTerm.setText(termHistory[termIdx]); etTerm.setSelection(etTerm.text.length)
            }
        }
        val chips = findViewById<LinearLayout>(R.id.llChips)
        listOf("help", "status", "detect", "apply gaming --dry", "revert", "sh device_config list | grep game_overlay", "clear").forEach { c ->
            chips.addView(Button(this, null, 0, R.style.Tweak_Button_Tonal).apply {
                text = c; textSize = 12f; minHeight = 0; minimumHeight = dp(36)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener { etTerm.setText(c); etTerm.setSelection(c.length) }
            })
        }
    }

    private fun submitTerm() {
        val c = etTerm.text.toString().trim()
        etTerm.setText("")
        if (c.isNotEmpty()) termRun(c)
    }

    private fun termAppend(s: String) {
        tvTermOut.append(s)
        if (tvTermOut.length() > 30000) tvTermOut.text = tvTermOut.text.toString().takeLast(24000)
        findViewById<ScrollView>(R.id.svTerm).post { findViewById<ScrollView>(R.id.svTerm).fullScroll(View.FOCUS_DOWN) }
    }

    private fun termRun(cmd: String) {
        termHistory.add(cmd); termIdx = termHistory.size
        termAppend("$ $cmd\n")
        DebugLog.d("terminal: $cmd")
        when {
            cmd == "clear" -> tvTermOut.text = ""
            cmd.startsWith("sh ") -> termExec(cmd.removePrefix("sh ").trim(), false)
            else -> {
                val r = try { Native.cli(cmd) } catch (e: Throwable) { "Elibrary native gagal dimuat: ${e.message}" }
                when (r.firstOrNull()) {
                    'T' -> termAppend(r.substring(1).trimEnd() + "\n\n")
                    'S' -> termExec(r.substring(1), true)
                    else -> termAppend("error: ${r.drop(1)}\n\n")
                }
            }
        }
    }

    private fun termExec(script: String, refresh: Boolean) {
        val s = svc ?: ShellHub.svc ?: return termAppend("Shizuku belum siap / izin belum diberikan\n\n")
        if (termBusy) return termAppend("(masih ada perintah berjalan, tunggu)\n\n")
        termBusy = true
        termAppend("menjalankan…\n")
        Thread {
            val out = try { s.exec("#nolog\n$script") } catch (e: Exception) { "ERR: ${e.message}" }
            runOnUiThread {
                termAppend(out.trimEnd().ifEmpty { "(tanpa output)" } + "\n\n")
                termBusy = false
                if (refresh) checkStatus()
            }
        }.start()
    }

    // ───── Halaman Tentang ─────
    private fun fillAbout() {
        val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        @Suppress("DEPRECATION")
        val code = pi?.versionCode ?: 0
        findViewById<TextView>(R.id.tvAboutVer).text = "Versi ${pi?.versionName ?: "?"}"
        findViewById<TextView>(R.id.tvAboutInfo).text = buildString {
            appendLine("Android   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Perangkat : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI       : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            appendLine("Shizuku   : " + if (Shizuku.pingBinder())
                "berjalan, versi ${try { Shizuku.getVersion() } catch (e: Throwable) { "?" }}" else "tidak berjalan")
            appendLine("Akses     : ${who.ifEmpty { "-" }} (mode $mode)")
            appendLine("Author    : mnrdnn")
            append("Script    : $DIR")
        }
        findViewById<TextView>(R.id.tvAboutLog).text = CHANGELOG
    }

    // ───── Halaman Pengaturan ─────
        private fun setupSettingsPage() {
    // tema
        val rgTheme = findViewById<RadioGroup>(R.id.rgTheme)
        
        // 1. Gunakan key yang SAMA persis dengan yang ada di Night.wrap ("ikuti sistem")
        val themeKey = "ikuti sistem"
        val cur = prefs.getInt(themeKey, 0) // 0 = default (ikuti sistem)
        
        listOf("Ikuti sistem", "Terang", "Gelap").forEachIndexed { i, n ->
            rgTheme.addView(RadioButton(this).apply {
                id = 3000 + i
                text = n
                setTextColor(getColor(R.color.t_on_surface))
                isChecked = i == cur
            })
        }
        
        rgTheme.setOnCheckedChangeListener { _, id ->
            val sel = id - 3000
            // 2. Gunakan themeKey saat mengecek nilai sebelumnya
            if (sel in 0..2 && sel != prefs.getInt(themeKey, 0)) {
                // 3. Gunakan themeKey saat menyimpan nilai baru
                prefs.edit().putInt(themeKey, sel).putInt("page_restore", page).commit()
                recreate()
            }
        }
    
        // DNS
        rgDns = findViewById(R.id.rgDns); etDnsHost = findViewById(R.id.etDnsHost); tvDnsState = findViewById(R.id.tvDnsState)
        dnsOptions.forEachIndexed { i, o ->
            rgDns.addView(RadioButton(this).apply { id = 4000 + i; text = o.first; setTextColor(getColor(R.color.t_on_surface)) })
        }
        rgDns.setOnCheckedChangeListener { _, id -> etDnsHost.isEnabled = dnsOptions.getOrNull(id - 4000)?.second == "custom" }
        findViewById<View>(R.id.btnDnsApply).setOnClickListener { applyDns() }
        // FPS counter
        val sw = findViewById<Switch>(R.id.swFpsSet)
        sw.isChecked = OverlayService.running
        sw.setOnCheckedChangeListener { _, on ->
            if (syncingFps) return@setOnCheckedChangeListener
            if (on) startOverlay() else OverlayService.stop(this)
        }
        swFps = sw
        // lainnya
        val swDebug = findViewById<Switch>(R.id.swDebug)
        val btnLog = findViewById<View>(R.id.btnDebugLog)
        swDebug.isChecked = prefs.getBoolean("debug", false)
        btnLog.visibility = if (swDebug.isChecked) View.VISIBLE else View.GONE
        swDebug.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("debug", on).apply()
            btnLog.visibility = if (on) View.VISIBLE else View.GONE
        }
        btnLog.setOnClickListener { showDebug() }
        val et = findViewById<EditText>(R.id.etAuthor)
        et.setText(prefs.getString("author", ""))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("author", s.toString().trim().replace(Regex("[\\r\\n]"), " ")).apply()
            }
        })
    }

    private fun applyDns() {
        val v = dnsOptions.getOrNull(rgDns.checkedRadioButtonId - 4000)?.second ?: return toast("Pilih opsi DNS")
        val host = if (v == "custom") etDnsHost.text.toString().trim() else v
        val script = when {
            v == "auto" -> "settings put global private_dns_mode opportunistic\nsettings delete global private_dns_specifier\n"
            v == "off" -> "settings put global private_dns_mode off\n"
            !Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$").matches(host) || !host.contains('.') ->
                return toast("Nama host DNS tidak valid")
            else -> "settings put global private_dns_mode hostname\nsettings put global private_dns_specifier '$host'\n"
        }
        execQuiet("dns terapkan", script) { toast("DNS diterapkan"); loadDnsState() }
    }

    private fun loadDnsState() {
        val s = svc ?: ShellHub.svc
        if (s == null) { tvDnsState.text = "Shizuku belum siap"; return }
        execQuiet("dns baca",
            "echo \"M=\$(settings get global private_dns_mode)\"\necho \"H=\$(settings get global private_dns_specifier)\"\n") { out ->
            val mode = Regex("M=(.*)").find(out)?.groupValues?.get(1)?.trim() ?: "?"
            val host = Regex("H=(.*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
            tvDnsState.text = "Saat ini: " + when (mode) {
                "off" -> "Mati"; "hostname" -> host; "opportunistic", "null", "" -> "Otomatis"; else -> mode
            }
            val idx = when (mode) {
                "off" -> 1
                "hostname" -> dnsOptions.indexOfFirst { it.second == host }.let { if (it >= 0) it else 5 }
                else -> 0
            }
            rgDns.check(4000 + idx)
            if (idx == 5) etDnsHost.setText(host)
        }
    }

    // ───── Metadata script (Author, Dibuat, dll) ─────
    private fun scriptMeta(name: String, desc: String): String {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val author = prefs.getString("author", "").orEmpty().ifBlank { "udyneos" }
        return "#!/system/bin/sh\n# Name: $name\n# Author: $author\n# Created: $now\n" +
            "# Generator: Tweak Tuner $ver\n# Description: $desc\n"
    }
}
