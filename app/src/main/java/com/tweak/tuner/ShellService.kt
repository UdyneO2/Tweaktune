package com.tweak.tuner

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

const val SVLOG = "/data/local/tmp/tt_service.log"

/** Berjalan di proses Shizuku (uid shell). */
class ShellService : IShellService.Stub() {

    private val max = 100_000
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        log("service dimulai uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
    }

    @Synchronized private fun log(m: String) {
        try {
            val f = File(SVLOG)
            if (f.length() > 200_000) f.writeText("")
            f.appendText("${fmt.format(Date())} $m\n")
        } catch (e: Exception) { /* abaikan */ }
    }

    override fun destroy() { log("destroy"); exitProcess(0) }

    override fun exec(script: String): String {
        val quiet = script.startsWith("#nolog")   // dipakai app untuk membaca log, supaya tidak ikut tercatat
        val t0 = System.currentTimeMillis()
        val f = File("/data/local/tmp/tt_exec_${System.nanoTime()}.sh")
        return try {
            f.writeText(script)
            val p = ProcessBuilder("sh", f.path)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectErrorStream(true).start()
            val sb = StringBuilder()
            var cut = false
            p.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    val room = max - sb.length
                    if (room > 0) sb.append(buf, 0, minOf(n, room))
                    if (n > room) cut = true
                }
            }
            val code = p.waitFor()
            if (!quiet) log("exec len=${script.length} exit=$code out=${sb.length}${if (cut) " (dipotong)" else ""} ${System.currentTimeMillis() - t0}ms")
            if (cut) sb.append("\n…(output dipotong)")
            sb.toString()
        } catch (e: Exception) {
            log("exec ERR ${e.javaClass.simpleName}: ${e.message}")
            "ERR: ${e.message}"
        } finally {
            f.delete()
        }
    }
}