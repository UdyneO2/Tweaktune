package com.tweak.tuner

/** Jembatan ke core C (tweak_core.c). */
object Native {
    init { System.loadLibrary("tweakcore") }

    // Mode
    const val GAMING = 1
    const val DAILY = 2
    const val STANDARD = 3

    // Modul (bit), harus sama dengan TC_M_* di tweak_core.h
    const val M_TWEAK = 1
    const val M_OVERLAY = 2
    const val M_FPS = 4
    const val M_DISPLAY = 8
    const val M_WM = 16
    const val M_RENDERER = 32

    // Renderer, harus sama dengan TC_R_*
    const val R_DEFAULT = 0
    const val R_NATIVE = 1
    const val R_ANGLE = 2
    const val R_GAMEDRV = 3
    const val R_PRERELEASE = 4
    const val DETECT_VULKAN = 1 shl 8

    /** modules 0 = bawaan sesuai mode. renderers: "pkg=angle,pkg2=native". */
    @JvmStatic external fun buildApply(
        profile: Int, modules: Int, fps: Int, scale: Double, angle: Boolean,
        w: Int, h: Int, dpi: Int, games: String, renderers: String
    ): String?
    
    
    @JvmStatic external fun cli(cmd: String): String

    /** modules 0 = semua modul + backup lama v1.5. */
    @JvmStatic external fun buildRevert(modules: Int): String?
    @JvmStatic external fun buildDetect(): String?
    /** "PROFILE x" + "MOD nama=0|1": profil aktif dan modul yang sedang diterapkan. */
    @JvmStatic external fun buildStatus(): String?
    /** Script pemantau FPS yang berjalan di latar belakang (dijalankan lewat Shizuku). */
    @JvmStatic external fun buildMonitor(): String?
    @JvmStatic external fun parseRenderers(output: String): Int
    @JvmStatic external fun lastError(): String

    val RENDERER_NAMES = arrayOf("default", "native", "angle", "gamedriver", "prerelease")
    val RENDERER_LABELS = arrayOf("Bawaan sistem", "Native (driver sistem)", "ANGLE", "Driver game", "Driver game prarilis")
}
