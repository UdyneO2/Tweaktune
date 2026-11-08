package com.tweak.tuner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class SetupActivity : Activity() {

    private lateinit var tvStep1: TextView
    private lateinit var tvStep2: TextView
    private lateinit var tvProgress: TextView
    private lateinit var badge1: TextView
    private lateinit var badge2: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnGrant: Button
    private lateinit var cardId: View
    private lateinit var idResult: View
    private lateinit var tvScore: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvRows: TextView
    private lateinit var btnSkip: Button
    private lateinit var btnIdentify: Button
    private var launched = false

    private val prefs by lazy { getSharedPreferences("tweak", Context.MODE_PRIVATE) }

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Night.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        tvStep1 = findViewById(R.id.tvStep1)
        tvStep2 = findViewById(R.id.tvStep2)
        tvProgress = findViewById(R.id.tvProgress)
        badge1 = findViewById(R.id.badge1)
        badge2 = findViewById(R.id.badge2)
        btnOpen = findViewById(R.id.btnOpen)
        btnGrant = findViewById(R.id.btnGrant)
        cardId = findViewById(R.id.cardId)
        idResult = findViewById(R.id.idResult)
        tvScore = findViewById(R.id.tvScore)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvRows = findViewById(R.id.tvRows)
        btnSkip = findViewById(R.id.btnSkip)
        btnIdentify = findViewById(R.id.btnIdentify)

        if (prefs.getBoolean("id_skip", false)) cardId.visibility = View.GONE
        btnSkip.setOnClickListener {
            prefs.edit().putBoolean("id_skip", true).apply()
            cardId.visibility = View.GONE
        }
        btnIdentify.setOnClickListener { identify() }

        btnOpen.setOnClickListener {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i)
            else Toast.makeText(this, "Aplikasi Shizuku belum terpasang", Toast.LENGTH_SHORT).show()
        }
        btnGrant.setOnClickListener {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(100)
        }

        listOf(R.id.hero, R.id.title, R.id.sub, R.id.tvProgress, R.id.cardId, R.id.card1, R.id.card2)
            .forEachIndexed { i, id ->
                findViewById<View>(id).apply {
                    alpha = 0f
                    translationY = 48f
                    animate().alpha(1f).translationY(0f)
                        .setStartDelay(i * 90L).setDuration(380).start()
                }
            }

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun identify() {
        val r = DeviceCheck.run(this)
        val total = r.sumOf { it.score }
        tvScore.text = "$total/10"
        tvVerdict.text = when {
            total >= 8 -> "Sangat didukung"
            total >= 5 -> "Cukup didukung"
            else -> "Dukungan terbatas"
        }
        tvRows.text = r.joinToString("\n\n") { "${it.name}: ${it.score}/2\n${it.note}" }
        idResult.visibility = View.VISIBLE
        btnIdentify.text = "Recheck"
        btnSkip.text = "Tutup"
    }

    private fun setBadge(v: TextView, n: String, ok: Boolean) {
        v.text = if (ok) "✔" else n
        v.setBackgroundResource(if (ok) R.drawable.bg_badge_ok else R.drawable.bg_badge)
        v.setTextColor(getColor(if (ok) R.color.t_on_ok else R.color.t_on_primary))
    }

    private fun refresh() {
        val running = Shizuku.pingBinder()
        val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

        setBadge(badge1, "1", running)
        setBadge(badge2, "2", granted)

        tvStep1.text = if (running) "Shizuku berjalan" else "Belum berjalan. Buka Shizuku lalu mulai layanannya."
        tvStep2.text = when {
            granted -> "Izin diberikan"
            running -> "Izinkan Tweak Tuner memakai Shizuku."
            else -> "Menunggu Shizuku berjalan."
        }
        tvProgress.text = when {
            granted -> "Siap"
            running -> "Langkah 2 dari 2"
            else -> "Langkah 1 dari 2"
        }
        btnOpen.visibility = if (running) View.GONE else View.VISIBLE
        btnGrant.visibility = if (granted) View.GONE else View.VISIBLE
        btnGrant.isEnabled = running && !granted

        if (granted && !launched) {
            launched = true
            tvProgress.postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }, 600)
        }
    }
}