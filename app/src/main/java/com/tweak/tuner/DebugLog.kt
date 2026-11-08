package com.tweak.tuner

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log aplikasi di memori (maks 500 baris). */
object DebugLog {
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized fun d(msg: String) {
        if (buf.size >= 500) buf.removeFirst()
        buf.addLast("${fmt.format(Date())} $msg")
    }

    @Synchronized fun dump(): String = if (buf.isEmpty()) "(kosong)" else buf.joinToString("\n")

    @Synchronized fun clear() = buf.clear()
}