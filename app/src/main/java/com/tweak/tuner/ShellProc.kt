package com.tweak.tuner

import rikka.shizuku.Shizuku

/** Cadangan: jalankan script lewat Shizuku.newProcess (tanpa UserService). */
object ShellProc {
    private const val MAX = 100_000

    private fun spawn(cmd: Array<String>): Process {
        // newProcess bersifat private di Shizuku API 13, jadi dipanggil lewat refleksi
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    fun exec(script: String): String = try {
        val tmp = "/data/local/tmp/tt_p_${System.nanoTime()}.sh"

        // 1) tulis script ke file (stdin), supaya perintah di dalamnya tidak memakan script
        val w = spawn(arrayOf("sh", "-c", "cat > $tmp"))
        w.outputStream.use { it.write(script.toByteArray()) }
        w.waitFor()

        // 2) jalankan, lalu hapus
        val p = spawn(arrayOf("sh", "-c", "sh $tmp </dev/null 2>&1; r=\$?; rm -f $tmp; exit \$r"))
        val sb = StringBuilder()
        var cut = false
        p.inputStream.bufferedReader().use { r ->
            val buf = CharArray(4096)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                val room = MAX - sb.length
                if (room > 0) sb.append(buf, 0, minOf(n, room))
                if (n > room) cut = true
            }
        }
        p.waitFor()
        if (cut) sb.append("\n…(output dipotong)")
        sb.toString()
    } catch (e: Throwable) {
        "ERR: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
    }
}